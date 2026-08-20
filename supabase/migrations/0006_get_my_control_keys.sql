-- ============================================================
-- 0006_get_my_control_keys.sql
-- RPC lấy hàng loạt control_key cho App Client khi khôi phục thiết bị.
-- Hàm trả về danh sách { device_id, control_key } (Hex encoded)
-- cho tất cả các thiết bị mà user hiện tại có quyền OWNER hoặc ADMIN.
-- ============================================================

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
      and m.role in ('OWNER', 'ADMIN');
end;
$$;

revoke all on function public.get_device_control_keys() from public, anon;
grant execute on function public.get_device_control_keys() to authenticated;
