-- ============================================================
-- 0002_control_key_access.sql — control_key chỉ OWNER/ADMIN đọc
-- Cập nhật theo docs ECOSYSTEM_PLAN §6: VIEWER không lấy được key
-- → không ký được lệnh điều khiển.
-- Cách dùng: Supabase → SQL Editor → dán toàn bộ → Run.
-- ============================================================

-- ── 1) Column-level: member không đọc được control_key qua SELECT thường ──
-- (RLS theo dòng không phân biệt được cột; phải dùng column privileges)
revoke select on public.devices from anon;
revoke select on public.devices from authenticated;
grant select (id, device_id, profile, name, owner_id, status, created_at)
    on public.devices to authenticated;
grant select (id, device_id, profile, name, owner_id, status, control_key, created_at)
    on public.devices to service_role;

-- ── 2) RPC get_control_key — chỉ OWNER/ADMIN (SECURITY DEFINER) ──
-- Phase 2: Edge Function bridge lấy key để ký envelope lệnh
-- (server-side; app client không gọi hàm này).
create or replace function public.get_control_key(p_device_id text)
returns bytea
language plpgsql
security definer
set search_path = public
as $$
declare
    v_dev_id uuid;
    v_role   text;
begin
    select id into v_dev_id from public.devices where device_id = p_device_id;
    if v_dev_id is null then
        raise exception 'device_not_found';
    end if;

    select role into v_role from public.device_members
     where device_id = v_dev_id and user_id = auth.uid();
    if v_role is null or v_role not in ('OWNER', 'ADMIN') then
        raise exception 'permission_denied';
    end if;

    return (select control_key from public.devices where id = v_dev_id);
end;
$$;

revoke all on function public.get_control_key(text) from public, anon;
grant execute on function public.get_control_key(text) to authenticated;
