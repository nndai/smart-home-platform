-- ============================================================
-- 0010_app_releases.sql — Quản lý In-App Updates trên Supabase
--
-- Thành phần:
--   1) Storage Bucket: `app-releases` (public = true cho phép download APK qua HTTPS)
--   2) Bảng: `app_versions` (lưu version_code, version_name, URL, release_notes, is_mandatory)
--   3) RPC `get_latest_app_version`: App truy vấn bản cập nhật mới nhất
--
-- Cách dùng: Supabase → SQL Editor → dán toàn bộ → Run. Hoặc dùng Supabase CLI.
-- ============================================================

-- ── 1) Storage Bucket `app-releases` ──
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'app-releases',
    'app-releases',
    true,
    104857600, -- 100MB limit (APK size thường 20MB - 50MB)
    array['application/vnd.android.package-archive']
)
on conflict (id) do update
set public = true,
    file_size_limit = 104857600,
    allowed_mime_types = array['application/vnd.android.package-archive'];

-- Storage Policy: Cho phép mọi người (public/anon/authenticated) đọc/tải APK
drop policy if exists "Public Access for app-releases" on storage.objects;
create policy "Public Access for app-releases"
    on storage.objects for select
    using (bucket_id = 'app-releases');

-- Storage Policy: Chỉ service_role (CLI upload tool) được upload/sửa/xóa file
drop policy if exists "Service role manage app-releases" on storage.objects;
create policy "Service role manage app-releases"
    on storage.objects for all
    using (bucket_id = 'app-releases' and (auth.role() = 'service_role'));

-- ── 2) Bảng `public.app_versions` ──
create table if not exists public.app_versions (
    id              uuid primary key default gen_random_uuid(),
    version_code    int unique not null,
    version_name    text not null,
    download_url    text not null,
    release_notes   text,
    is_mandatory    boolean default false,
    created_at      timestamptz default now()
);

-- Index tra cứu nhanh
create index if not exists idx_app_versions_code
    on public.app_versions (version_code desc);

-- ── 3) Row Level Security ──
alter table public.app_versions enable row level security;

-- Cho phép authenticated (app Android) và anon đọc danh sách app versions
drop policy if exists "app_versions_select" on public.app_versions;
create policy "app_versions_select"
    on public.app_versions for select
    using (true);

-- Ghi/sửa/xóa chỉ thực hiện qua service_role (CLI upload tool), 
-- không cần tạo policy vì service_role mặc định bypass RLS.

-- ── 4) RPC: get_latest_app_version ──
drop function if exists public.get_latest_app_version();

create or replace function public.get_latest_app_version()
returns setof public.app_versions
language sql
security definer
set search_path = public
as $$
    select * from public.app_versions order by version_code desc limit 1;
$$;

grant execute on function public.get_latest_app_version() to authenticated, anon;
