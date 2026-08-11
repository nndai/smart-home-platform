-- ============================================================
-- 0004_fix_members_rls.sql — fix "infinite recursion detected in policy
-- for relation device_members" (SQLSTATE 42P17)
--
-- Nguyên nhân: policy members_select (0001) tự query chính bảng
-- device_members → Postgres báo recursion khi app SELECT devices
-- (devices_select → device_members → members_select → device_members ...).
-- Fix: members_select chỉ cần "user thấy member row của chính mình";
-- members_delete kiểm tra OWNER qua devices.owner_id (chuỗi không lặp).
--
-- Cách dùng: Supabase → SQL Editor → dán toàn bộ → Run.
-- ============================================================

drop policy if exists members_select on public.device_members;
create policy members_select on public.device_members for select
    using (user_id = auth.uid());

drop policy if exists members_delete on public.device_members;
create policy members_delete on public.device_members for delete
    using (
        exists (select 1 from public.devices d
                where d.id = device_members.device_id and d.owner_id = auth.uid())
    );
