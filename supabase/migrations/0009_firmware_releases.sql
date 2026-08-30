-- ============================================================
-- 0009_firmware_releases.sql — Quản lý Firmware & OTA trên Supabase
--
-- Thành phần:
--   1) Storage Bucket: `firmwares` (public = true cho phép download OTA qua HTTPS)
--   2) Bảng: `firmware_releases` (lưu profile, env, chip, version, URL, checksum SHA256/MD5)
--   3) Trigger: tự động đồng bộ cờ `is_latest` cho mỗi profile + env
--   4) RPC `get_latest_firmware`: App/Thiết bị truy vấn bản build mới nhất
--
-- Cách dùng: Supabase → SQL Editor → dán toàn bộ → Run. IDEMPOTENT.
-- ============================================================

-- ── 1) Storage Bucket `firmwares` ──
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'firmwares',
    'firmwares',
    true,
    10485760, -- 10MB limit (firmware size thường 500KB - 2MB)
    array['application/octet-stream', 'application/macbinary', 'application/x-binary', 'application/bin']
)
on conflict (id) do update
set public = true,
    file_size_limit = 10485760;

-- Storage Policy: Cho phép mọi người (public/anon/authenticated) đọc/tải binary OTA
drop policy if exists "Public Access for firmwares" on storage.objects;
create policy "Public Access for firmwares"
    on storage.objects for select
    using (bucket_id = 'firmwares');

-- Storage Policy: Chỉ service_role (CLI upload tool) được upload/sửa/xóa file
drop policy if exists "Service role manage firmwares" on storage.objects;
create policy "Service role manage firmwares"
    on storage.objects for all
    using (bucket_id = 'firmwares' and (auth.role() = 'service_role'));

-- ── 2) Bảng `public.firmware_releases` ──
create table if not exists public.firmware_releases (
    id              uuid primary key default gen_random_uuid(),
    profile         text not null,                 -- pump, switch, remote_switch...
    env             text not null,                 -- remote_switch_esp8266, pump-ln882h, switch_esp32...
    target_chip     text not null,                 -- esp8266, esp32, ln882h...
    version         text not null,                 -- v1.0.0 hoặc timestamp YYYY.MM.DD...
    build_timestamp bigint not null,               -- unix timestamp (seconds)
    file_name       text not null,                 -- remote_switch_esp8266_1725000000.bin
    storage_path    text not null,                 -- releases/remote_switch/esp8266/firmware.bin
    file_url        text not null,                 -- public https download URL
    file_size       bigint not null,               -- dung lượng byte
    checksum_sha256 text not null,                 -- 64 hex chars
    checksum_md5    text,                          -- 32 hex chars (cho MCU check nhanh)
    is_latest       boolean not null default true, -- bản mới nhất của (profile, env)
    changelog       text not null default '',      -- ghi chú cập nhật
    created_at      timestamptz not null default now()
);

-- Index tra cứu nhanh
create index if not exists idx_firmware_releases_lookup
    on public.firmware_releases (profile, env, is_latest);

create index if not exists idx_firmware_releases_ts
    on public.firmware_releases (build_timestamp desc);

-- ── 3) Row Level Security ──
alter table public.firmware_releases enable row level security;

-- Cho phép authenticated (app Android) và anon đọc danh sách firmware
drop policy if exists "firmware_releases_select" on public.firmware_releases;
create policy "firmware_releases_select"
    on public.firmware_releases for select
    using (true);

-- Ghi/sửa/xóa chỉ thực hiện qua service_role (CLI upload tool)

-- ── 4) Trigger tự động cập nhật `is_latest` ──
create or replace function public.handle_firmware_release_latest()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if new.is_latest = true then
        update public.firmware_releases
           set is_latest = false
         where profile = new.profile
           and env = new.env
           and id <> new.id
           and is_latest = true;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_firmware_release_latest on public.firmware_releases;
create trigger trg_firmware_release_latest
    before insert or update of is_latest
    on public.firmware_releases
    for each row
    execute function public.handle_firmware_release_latest();

-- ── 5) RPC: get_latest_firmware ──
-- App Android / Edge Function gọi để lấy bản firmware mới nhất
drop function if exists public.get_latest_firmware(text, text);

create or replace function public.get_latest_firmware(
    p_profile text,
    p_env     text default null
)
returns table (
    id              uuid,
    profile         text,
    env             text,
    target_chip     text,
    version         text,
    build_timestamp bigint,
    file_name       text,
    file_url        text,
    file_size       bigint,
    checksum_sha256 text,
    checksum_md5    text,
    changelog       text,
    created_at      timestamptz
)
language plpgsql
security definer
set search_path = public
as $$
begin
    return query
    select
        f.id,
        f.profile,
        f.env,
        f.target_chip,
        f.version,
        f.build_timestamp,
        f.file_name,
        f.file_url,
        f.file_size,
        f.checksum_sha256,
        f.checksum_md5,
        f.changelog,
        f.created_at
    from public.firmware_releases f
    where f.profile = p_profile
      and (p_env is null or f.env = p_env)
      and f.is_latest = true
    order by f.build_timestamp desc
    limit 1;
end;
$$;

grant execute on function public.get_latest_firmware(text, text) to authenticated, anon;
