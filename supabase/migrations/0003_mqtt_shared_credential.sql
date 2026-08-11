-- ============================================================
-- 0003_mqtt_shared_credential.sql — credential MQTT shared cho gia đình
-- HiveMQ Cloud free không có REST API tạo credential → dùng 1 credential
-- shared `device-family` (permission devices/+/#), tạo 1 lần trong console,
-- seed vào bảng app_secrets (service_role). App open-source không nhúng
-- user/pass vào APK/BuildConfig — đọc qua RPC get_mqtt_credential() khi chạy.
-- Cách dùng: Supabase → SQL Editor → dán toàn bộ → Run.
-- Sau đó seed 1 lần: tools/seed_mqtt_credential.ps1
-- ============================================================

-- ── 1) Bảng app_secrets — chỉ service_role đọc/ghi ──
-- Supabase default cấp "all on all tables in public" cho anon/authenticated
-- → bắt buộc RLS + revoke tường minh. service_role có BYPASSRLS nên
-- seed script vẫn ghi/đọc được dù không có policy.
create table if not exists public.app_secrets (
    key         text primary key,
    value       text not null,
    description text not null default '',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

alter table public.app_secrets enable row level security;

revoke all on public.app_secrets from anon;
revoke all on public.app_secrets from authenticated;

-- ── 2) RPC get_mqtt_credential — app (đã đăng nhập) lấy credential shared ──
-- SECURITY DEFINER: chạy với quyền owner (postgres, bypass RLS) để đọc
-- app_secrets; chỉ grant cho authenticated — anon không có quyền.
-- LƯU Ý: trả kiểu table (KHÔNG phải json) — PostgREST bọc mảng [{...}]
-- đúng format supabase-kt decodeSingle() mong đợi (giống claim_device).
-- Drop trước vì Postgres không cho CREATE OR REPLACE đổi return type (42P13).
drop function if exists public.get_mqtt_credential();

create or replace function public.get_mqtt_credential()
returns table (username text, password text)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user text;
    v_pass text;
begin
    select value into v_user from public.app_secrets where key = 'mqtt_user';
    select value into v_pass from public.app_secrets where key = 'mqtt_pass';
    if v_user is null or v_pass is null then
        raise exception 'mqtt_credential_not_configured';
    end if;
    return query select v_user, v_pass;
end;
$$;

revoke all on function public.get_mqtt_credential() from public, anon;
grant execute on function public.get_mqtt_credential() to authenticated;
