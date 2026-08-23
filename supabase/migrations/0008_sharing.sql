-- ============================================================
-- 0008_sharing.sql — Chia sẻ thiết bị (P6): profiles + invite/member RPCs
--
-- Thiết kế nghiệp vụ (đã chốt):
--   • Role: OWNER > ADMIN > MEMBER > VIEWER
--   • MEMBER có controlKey (sửa get_device_control_keys) → điều khiển được
--   • VIEWER không có key → thật sự read-only ở tầng firmware (envelope HMAC)
--   • Invite: KHÔNG hết hạn (expires_at = 'infinity'), SINGLE-USE
--     (xóa ngay khi redeem), Owner/Admin thu hồi được
--   • Bất biến: không sửa/xóa row OWNER qua API; owner cuối không bị remove;
--     ADMIN chỉ thao tác trên MEMBER/VIEWER (chống leo thang)
--   • Mọi ghi đổi đi qua RPC SECURITY DEFINER — tránh recursion 42P17
--     (bài học 0004), RLS bảng gốc giữ nguyên hướng hiện có.
--
-- Cách dùng: Supabase → SQL Editor → dán toàn bộ → Run. IDEMPOTENT.
-- ============================================================

-- ── 1) Bảng profiles — danh tính hiển thị trong danh sách thành viên ──
create table if not exists public.profiles (
    id         uuid primary key references auth.users(id) on delete cascade,
    email      text not null default '',
    created_at timestamptz not null default now()
);

alter table public.profiles enable row level security;

-- Không cho client đọc trực tiếp (email của mọi user) — email chỉ chảy qua
-- list_device_members() (SECURITY DEFINER). Trigger handle_new_user là
-- definer nên vẫn hoạt động bình thường.
revoke all on public.profiles from anon;
revoke all on public.profiles from authenticated;

-- Trigger: user mới đăng ký → tự tạo profile
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, email)
    values (new.id, coalesce(new.email, ''))
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- Backfill user đã tồn tại trước migration
insert into public.profiles (id, email)
select u.id, coalesce(u.email, '')
from auth.users u
on conflict (id) do nothing;

-- ── 2) Helper: role của caller trên một thiết bị ──
create or replace function public.my_device_role(p_device_id uuid)
returns text
language sql
security definer
set search_path = public
stable
as $$
    select m.role from public.device_members m
    where m.device_id = p_device_id and m.user_id = auth.uid();
$$;

-- ── 3) create_invite — Owner/Admin sinh mã chia sẻ (không hết hạn) ──
create or replace function public.create_invite(
    p_device_id uuid,
    p_role      text
) returns table (code text, device_name text)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_role     text;
    v_dev      public.devices;
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;

    v_role := upper(trim(p_role));
    if v_role not in ('ADMIN', 'MEMBER', 'VIEWER') then
        raise exception 'invalid_role';          -- không invite được OWNER
    end if;

    select * into v_dev from public.devices where id = p_device_id;
    if not found then
        raise exception 'device_not_found';
    end if;

    if public.my_device_role(p_device_id) not in ('OWNER', 'ADMIN') then
        raise exception 'permission_denied';
    end if;

    -- Mã 18 hex chars (9 bytes ngẫu nhiên) — đủ dài, dễ nhập tay
    return query
    insert into public.invites (device_id, role, created_by, expires_at)
    values (
        p_device_id,
        v_role,
        auth.uid(),
        'infinity'::timestamptz                  -- không hết hạn, thu hồi tay
    )
    returning invites.code, v_dev.name as device_name;
end;
$$;

-- ── 4) redeem_invite — nhập mã → thành member (single-use, idempotent) ──
create or replace function public.redeem_invite(
    p_code text
) returns table (device_id text, device_name text, profile text, role text)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_inv    public.invites;
    v_dev    public.devices;
    v_existing text;
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;

    -- FOR UPDATE: serialize concurrent redeems của cùng một mã (single-use).
    -- Txn sau chờ txn trước commit DELETE → sẽ thấy not found → invalid_code.
    select * into v_inv from public.invites
    where lower(code) = lower(trim(p_code))
    for update;
    if not found then
        raise exception 'invalid_code';
    end if;

    if v_inv.expires_at < now() then             -- guard dù mặc định infinity
        raise exception 'invite_expired';
    end if;

    select * into v_dev from public.devices where id = v_inv.device_id;
    if not found then                            -- thiết bị đã bị xóa → dọn mã rác
        delete from public.invites where code = v_inv.code;
        raise exception 'invalid_code';
    end if;

    -- Đã là member → idempotent: báo role hiện có, vẫn tiêu thụ mã
    select m.role into v_existing from public.device_members m
    where m.device_id = v_inv.device_id and m.user_id = auth.uid();

    if v_existing is null then
        insert into public.device_members (device_id, user_id, role)
        values (v_inv.device_id, auth.uid(), v_inv.role);
        v_existing := v_inv.role;
    end if;

    delete from public.invites where code = v_inv.code;   -- single-use

    return query
    select v_dev.device_id, v_dev.name, v_dev.profile, v_existing;
end;
$$;

-- ── 5) list_device_members — danh sách thành viên + email từ profiles ──
create or replace function public.list_device_members(
    p_device_id uuid
) returns table (user_id uuid, email text, role text, joined_at timestamptz)
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;
    if public.my_device_role(p_device_id) is null then
        raise exception 'permission_denied';
    end if;

    return query
    select m.user_id, coalesce(p.email, ''), m.role, m.created_at
    from public.device_members m
    left join public.profiles p on p.id = m.user_id
    where m.device_id = p_device_id
    order by case m.role when 'OWNER' then 0 when 'ADMIN' then 1
                         when 'MEMBER' then 2 else 3 end,
             m.created_at;
end;
$$;

-- ── 6) update_member_role — đổi role theo ma trận quyền ──
-- OWNER  : sửa mọi row trừ OWNER (kể cả chính mình — owner tự đổi = chặn,
--          transfer ownership là phase sau)
-- ADMIN  : chỉ sửa MEMBER/VIEWER và chỉ gán MEMBER/VIEWER (chống leo thang)
create or replace function public.update_member_role(
    p_device_id uuid,
    p_user_id   uuid,
    p_new_role  text
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller  text;
    v_target  text;
    v_new_role text;
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;

    -- Validate role cho MỌI caller (defense-in-depth: bảng đã có CHECK từ
    -- 0001, nhưng validate sớm để trả lỗi sạch thay vì exception DB thô).
    v_new_role := upper(trim(p_new_role));
    if v_new_role not in ('ADMIN', 'MEMBER', 'VIEWER') then
        raise exception 'invalid_role';
    end if;

    v_caller := public.my_device_role(p_device_id);
    if v_caller not in ('OWNER', 'ADMIN') then
        raise exception 'permission_denied';
    end if;

    select m.role into v_target from public.device_members m
    where m.device_id = p_device_id and m.user_id = p_user_id;
    if v_target is null then
        raise exception 'member_not_found';
    end if;
    if v_target = 'OWNER' then                    -- bất biến #1
        raise exception 'cannot_modify_owner';
    end if;

    if v_caller = 'ADMIN'
       and v_target not in ('MEMBER', 'VIEWER') then   -- bất biến #3
        raise exception 'permission_denied';
    end if;

    update public.device_members m
       set role = v_new_role
     where m.device_id = p_device_id and m.user_id = p_user_id;
end;
$$;

-- ── 7) remove_member — xóa member theo ma trận quyền ──
-- OWNER : xóa mọi row trừ chính mình (tự xóa → dùng leave? owner bị chặn leave
--         nên phải transfer/xóa thiết bị — phase sau)
-- ADMIN : chỉ xóa MEMBER/VIEWER
create or replace function public.remove_member(
    p_device_id uuid,
    p_user_id   uuid
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller text;
    v_target text;
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;
    v_caller := public.my_device_role(p_device_id);
    if v_caller not in ('OWNER', 'ADMIN') then
        raise exception 'permission_denied';
    end if;
    if p_user_id = auth.uid() then                -- tự xóa dùng leave_device
        raise exception 'use_leave_instead';
    end if;

    select m.role into v_target from public.device_members m
    where m.device_id = p_device_id and m.user_id = p_user_id;
    if v_target is null then
        raise exception 'member_not_found';
    end if;
    if v_target = 'OWNER' then                    -- bất biến #1
        raise exception 'cannot_remove_owner';
    end if;
    if v_caller = 'ADMIN' and v_target not in ('MEMBER', 'VIEWER') then
        raise exception 'permission_denied';      -- bất biến #3
    end if;

    delete from public.device_members m
     where m.device_id = p_device_id and m.user_id = p_user_id;
end;
$$;

-- ── 8) leave_device — tự rời (owner cuối bị chặn) ──
create or replace function public.leave_device(
    p_device_id uuid
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_count int;
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;

    select count(*) into v_owner_count
    from public.device_members
    where device_id = p_device_id and role = 'OWNER';

    if v_owner_count = 0 then                     -- thiết bị mồ côi (legacy)
        delete from public.device_members
         where device_id = p_device_id and user_id = auth.uid();
        return;
    end if;

    if public.my_device_role(p_device_id) = 'OWNER' then   -- bất biến #2
        raise exception 'owner_cannot_leave';
    end if;

    delete from public.device_members
     where device_id = p_device_id and user_id = auth.uid();
end;
$$;

-- ── 9) list_invites / revoke_invite — quản lý mã đang hoạt động ──
create or replace function public.list_invites(
    p_device_id uuid
) returns table (code text, role text, created_at timestamptz)
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;
    if public.my_device_role(p_device_id) not in ('OWNER', 'ADMIN') then
        raise exception 'permission_denied';
    end if;

    return query
    select i.code, i.role, i.created_at
    from public.invites i
    where i.device_id = p_device_id
    order by i.created_at desc;
end;
$$;

create or replace function public.revoke_invite(
    p_code text
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_device uuid;
begin
    if auth.uid() is null then
        raise exception 'not_authenticated';
    end if;

    select device_id into v_device from public.invites
    where lower(code) = lower(trim(p_code));
    if not found then
        return;                                   -- đã hết/có người redeem
    end if;

    if public.my_device_role(v_device) not in ('OWNER', 'ADMIN') then
        raise exception 'permission_denied';
    end if;

    delete from public.invites
     where lower(code) = lower(trim(p_code));
end;
$$;

-- ── 10) get_my_devices — devices + vai trò của chính mình (flat, decode dễ) ──
-- Cột tên `role` để app decode thẳng vào model Device (đã có field role).
create or replace function public.get_my_devices()
returns table (
    id         uuid,
    device_id  text,
    profile    text,
    name       text,
    owner_id   uuid,
    status     text,
    role       text
)
language plpgsql
security definer
set search_path = public
as $$
begin
    return query
    select d.id, d.device_id, d.profile, d.name, d.owner_id, d.status,
           m.role
    from public.devices d
    join public.device_members m on m.device_id = d.id and m.user_id = auth.uid()
    order by d.name;
end;
$$;

-- ── 11) MEMBER cũng nhận controlKey để ký envelope MQTT ──
-- VIEWER vẫn bị loại → read-only thật sự ở tầng firmware.
create or replace function public.get_device_control_keys()
returns table(device_id text, control_key text)
language plpgsql
security definer
set search_path = public
as $$
begin
    return query
    select
        d.device_id,
        encode(d.control_key, 'hex') as control_key
    from public.devices d
    join public.device_members m on d.id = m.device_id
    where m.user_id = auth.uid()
      and m.role in ('OWNER', 'ADMIN', 'MEMBER');
end;
$$;

-- ── 12) Grants — revoke tường minh rồi grant đúng đối tượng ──
revoke all on function public.claim_device(text, text, text, bytea) from public, anon;
grant execute on function public.claim_device(text, text, text, bytea) to authenticated;

-- %I chỉ quote TÊN hàm; arglist giữ nguyên dạng text (%s) để Postgres parse
-- đúng signature "name(argtypes)".
do $$
declare
    r record;
begin
    for r in
        select * from (values
            ('create_invite',        'uuid, text'),
            ('redeem_invite',        'text'),
            ('list_device_members',  'uuid'),
            ('update_member_role',   'uuid, uuid, text'),
            ('remove_member',        'uuid, uuid'),
            ('leave_device',         'uuid'),
            ('list_invites',         'uuid'),
            ('revoke_invite',        'text'),
            ('get_my_devices',       ''),
            ('my_device_role',       'uuid'),
            ('get_device_control_keys', '')
        ) as t(fn_name, fn_args)
    loop
        execute format(
            'revoke all on function public.%I(%s) from public, anon',
            r.fn_name, r.fn_args
        );
        execute format(
            'grant execute on function public.%I(%s) to authenticated',
            r.fn_name, r.fn_args
        );
    end loop;
end $$;
