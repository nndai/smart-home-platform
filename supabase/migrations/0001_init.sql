-- ============================================================
-- 0001_init.sql — Smart Home Platform: schema + RLS + claim_device
-- Cách dùng: Supabase → SQL Editor → dán toàn bộ → Run.
-- (Không cần supabase CLI / Docker.)
-- ============================================================

-- ── Extensions ──
create extension if not exists pgcrypto;

-- ── Bảng: devices ──
create table public.devices (
    id          uuid primary key default gen_random_uuid(),
    device_id   text not null unique,          -- "dev-" + 12 hex (từ anchor)
    profile     text not null,                 -- pump / switch / ...
    name        text not null default 'Thiết bị',
    owner_id    uuid references auth.users(id) on delete set null,
    status      text not null default 'active',
    control_key bytea,                         -- 32B; member đọc để ký envelope (§3.2)
    created_at  timestamptz not null default now()
);

-- ── Bảng: device_members (mọi người được truy cập thiết bị) ──
create table public.device_members (
    device_id  uuid not null references public.devices(id) on delete cascade,
    user_id    uuid not null references auth.users(id) on delete cascade,
    role       text not null check (role in ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    created_at timestamptz not null default now(),
    primary key (device_id, user_id)
);

-- ── Bảng: invites (P6 — chia sẻ; để sẵn schema) ──
create table public.invites (
    code       text primary key default encode(gen_random_bytes(9), 'hex'),
    device_id  uuid not null references public.devices(id) on delete cascade,
    role       text not null check (role in ('ADMIN', 'MEMBER', 'VIEWER')),
    created_by uuid references auth.users(id),
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

-- ── Row Level Security ──
alter table public.devices         enable row level security;
alter table public.device_members  enable row level security;
alter table public.invites         enable row level security;

-- devices: select — mọi member; update — OWNER/ADMIN
create policy devices_select on public.devices for select
    using (
        exists (select 1 from public.device_members m
                where m.device_id = devices.id and m.user_id = auth.uid())
    );

create policy devices_update on public.devices for update
    using (
        exists (select 1 from public.device_members m
                where m.device_id = devices.id and m.user_id = auth.uid()
                  and m.role in ('OWNER', 'ADMIN'))
    );

-- devices: insert/delete CHỈ qua claim_device (SECURITY DEFINER) — không policy nào khác.

-- device_members: select — member của cùng thiết bị; delete — chỉ OWNER
create policy members_select on public.device_members for select
    using (
        exists (select 1 from public.device_members me
                where me.device_id = device_members.device_id
                  and me.user_id = auth.uid())
    );

create policy members_delete on public.device_members for delete
    using (
        exists (select 1 from public.device_members me
                where me.device_id = device_members.device_id
                  and me.user_id = auth.uid() and me.role = 'OWNER')
    );

-- invites: select/delete — chỉ OWNER của thiết bị (P6 sẽ thêm member-invite)
create policy invites_select on public.invites for select
    using (
        exists (select 1 from public.device_members m
                where m.device_id = invites.device_id
                  and m.user_id = auth.uid() and m.role = 'OWNER')
    );

create policy invites_delete on public.invites for delete
    using (
        exists (select 1 from public.device_members m
                where m.device_id = invites.device_id
                  and m.user_id = auth.uid() and m.role = 'OWNER')
    );

-- ── Function: claim_device (thay edge fn — RPC, không cần CLI) ──
-- App gọi: supabase.rpc('claim_device', { p_device_id, p_profile, p_name, p_control_key })
-- Chỉ người đã đăng nhập; thiết bị chưa có owner → claim được;
-- đã có owner → từ chối (chống claim kép — §4.3). Re-claim sau revocation
-- (owner null) vẫn được phép.
create or replace function public.claim_device(
    p_device_id   text,
    p_profile     text,
    p_name        text default null,
    p_control_key bytea default null
) returns public.devices
language plpgsql
security definer
set search_path = public
as $$
declare
    v_dev public.devices;
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;

    select * into v_dev from public.devices where device_id = p_device_id;
    if found and v_dev.owner_id is not null then
        raise exception 'device_already_claimed';
    end if;

    if not found then
        insert into public.devices (device_id, profile, name, owner_id, control_key)
        values (p_device_id, p_profile, coalesce(p_name, p_profile), auth.uid(), p_control_key)
        returning * into v_dev;
    else
        update public.devices
           set owner_id = auth.uid(),
               profile  = coalesce(p_profile, profile),
               name     = coalesce(p_name, name),
               control_key = coalesce(p_control_key, control_key)
         where id = v_dev.id
        returning * into v_dev;
    end if;

    insert into public.device_members (device_id, user_id, role)
    values (v_dev.id, auth.uid(), 'OWNER')
    on conflict (device_id, user_id) do nothing;

    return v_dev;
end;
$$;

-- Chỉ user đã xác thực mới được gọi claim_device
revoke all on function public.claim_device(text, text, text, bytea) from public, anon;
grant execute on function public.claim_device(text, text, text, bytea) to authenticated;
