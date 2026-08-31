#!/usr/bin/env python3
"""
Upload Firmware Build to Supabase Storage & Database
===================================================
Tự động build (PlatformIO), tính SHA256/MD5, upload binary .bin lên Supabase Storage
(bucket: firmwares) và ghi nhận bản release vào PostgreSQL (bảng: firmware_releases).

Sử dụng:
    python tools/upload_firmware.py --env remote_switch_esp8266 --build
    python tools/upload_firmware.py --env all --build
    python tools/upload_firmware.py --dry-run
"""

import argparse
import configparser
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def load_env(repo_root: Path) -> dict:
    """Đọc các biến môi trường từ file .env ở root repo."""
    env_file = repo_root / ".env"
    env_vars = {}
    if env_file.exists():
        with open(env_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, _, v = line.partition("=")
                env_vars[k.strip()] = v.strip().strip('"').strip("'")

    # Môi trường hệ thống có độ ưu tiên cao hơn
    for k in ["SUPABASE_URL", "SUPABASE_SERVICE_KEY", "SUPABASE_ANON_KEY"]:
        if os.environ.get(k):
            env_vars[k] = os.environ[k]

    return env_vars


def parse_platformio_ini(firmware_dir: Path) -> dict:
    """Trích xuất danh sách environments từ platformio.ini."""
    ini_path = firmware_dir / "platformio.ini"
    if not ini_path.exists():
        raise FileNotFoundError(f"Không tìm thấy file: {ini_path}")

    config = configparser.ConfigParser()
    config.read(ini_path, encoding="utf-8")

    envs = {}
    for section in config.sections():
        if section.startswith("env:"):
            env_name = section[4:]
            platform = config.get(section, "platform", fallback="")
            build_flags = config.get(section, "build_flags", fallback="")

            # Xác định profile
            profile = "unknown"
            if "PROFILE_PUMP" in build_flags:
                profile = "pump"
            elif "PROFILE_REMOTE_SWITCH" in build_flags:
                profile = "remote_switch"
            elif "PROFILE_SWITCH" in build_flags:
                profile = "switch"
            elif "remote_switch" in env_name:
                profile = "remote_switch"
            elif "switch" in env_name:
                profile = "switch"
            elif "pump" in env_name:
                profile = "pump"

            # Xác định chip
            chip = "unknown"
            if "libretiny" in platform or "ln882h" in env_name:
                chip = "ln882h"
            elif "espressif8266" in platform or "esp8266" in env_name:
                chip = "esp8266"
            elif "espressif32" in platform or "esp32" in env_name:
                chip = "esp32"

            envs[env_name] = {
                "name": env_name,
                "platform": platform,
                "profile": profile,
                "chip": chip,
                "bin_path": firmware_dir / ".pio" / "build" / env_name / "firmware.bin",
            }
    return envs


def read_build_time_header(firmware_dir: Path) -> dict:
    """Đọc BUILD_UNIX_TIME và BUILD_STR từ include/build_time.h nếu có."""
    header_path = firmware_dir / "include" / "build_time.h"
    res = {
        "unix_time": int(time.time()),
        "build_str": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
    }
    if header_path.exists():
        with open(header_path, "r", encoding="utf-8") as f:
            content = f.read()
            m_ts = re.search(r"#define\s+BUILD_UNIX_TIME\s+(\d+)u?", content)
            if m_ts:
                res["unix_time"] = int(m_ts.group(1))
            m_str = re.search(r'#define\s+BUILD_STR\s+"([^"]+)"', content)
            if m_str:
                res["build_str"] = m_str.group(1)
    return res


def compute_hashes(file_path: Path) -> tuple[str, str, int]:
    """Tính SHA256, MD5 và lấy kích thước file."""
    sha256 = hashlib.sha256()
    md5 = hashlib.md5()
    size = 0
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
            md5.update(chunk)
            size += len(chunk)
    return sha256.hexdigest(), md5.hexdigest(), size


def run_platformio_build(firmware_dir: Path, env_name: str) -> bool:
    """Chạy lệnh pio run -e <env_name>."""
    print(f"\n[PIO] Đang build firmware cho env '{env_name}'...")
    cmd = ["pio", "run", "-e", env_name]
    try:
        proc = subprocess.run(cmd, cwd=firmware_dir, check=True)
        return proc.returncode == 0
    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Build thất bại với mã lỗi {e.returncode}")
        return False
    except FileNotFoundError:
        print("[ERROR] Không tìm thấy lệnh 'pio'. Hãy đảm bảo PlatformIO CLI đã được cài đặt hoặc kích hoạt môi trường.")
        return False


def upload_to_supabase_storage(
    supabase_url: str,
    service_key: str,
    bucket: str,
    storage_path: str,
    file_bytes: bytes,
) -> str:
    """Upload binary file lên Supabase Storage qua REST API."""
    url_path = urllib.parse.quote(storage_path)
    url = f"{supabase_url.rstrip('/')}/storage/v1/object/{bucket}/{url_path}"

    headers = {
        "Authorization": f"Bearer {service_key}",
        "apikey": service_key,
        "Content-Type": "application/octet-stream",
        "x-upsert": "true",
    }

    req = urllib.request.Request(url, data=file_bytes, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            if resp.status in (200, 201):
                # Public URL
                return f"{supabase_url.rstrip('/')}/storage/v1/object/public/{bucket}/{url_path}"
            raise RuntimeError(f"Storage upload returned status {resp.status}")
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"Supabase Storage error {e.code}: {err_msg}") from e


def insert_firmware_release(
    supabase_url: str,
    service_key: str,
    release_data: dict,
) -> dict:
    """Insert bản release mới vào bảng public.firmware_releases."""
    url = f"{supabase_url.rstrip('/')}/rest/v1/firmware_releases"
    headers = {
        "Authorization": f"Bearer {service_key}",
        "apikey": service_key,
        "Content-Type": "application/json",
        "Prefer": "return=representation",
    }
    payload = json.dumps(release_data).encode("utf-8")
    req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            resp_bytes = resp.read()
            data = json.loads(resp_bytes.decode("utf-8"))
            if isinstance(data, list) and len(data) > 0:
                return data[0]
            return data
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"Supabase DB insert error {e.code}: {err_msg}") from e


def process_single_env(
    env_info: dict,
    firmware_dir: Path,
    env_vars: dict,
    custom_version: str | None,
    changelog: str,
    do_build: bool,
    dry_run: bool,
) -> bool:
    """Xử lý build, hash và upload cho 1 môi trường."""
    env_name = env_info["name"]
    bin_path = env_info["bin_path"]
    profile = env_info["profile"]
    chip = env_info["chip"]

    print(f"\n{'='*60}")
    print(f"▶ XỬ LÝ: {env_name} (Profile: {profile}, Chip: {chip})")
    print(f"{'='*60}")

    if do_build or not bin_path.exists():
        if not run_platformio_build(firmware_dir, env_name):
            return False

    if not bin_path.exists():
        print(f"[ERROR] Không tìm thấy file binary sau khi build: {bin_path}")
        return False

    # Đọc build time & hash
    bt = read_build_time_header(firmware_dir)
    sha256, md5, size = compute_hashes(bin_path)

    unix_ts = bt["unix_time"]
    version_str = custom_version if custom_version else f"v{time.strftime('%Y.%m.%d', time.localtime(unix_ts))}.{unix_ts % 100000}"
    file_name = f"{env_name}_{unix_ts}.bin"
    storage_path = f"releases/{profile}/{chip}/{file_name}"

    supabase_url = env_vars.get("SUPABASE_URL", "")
    service_key = env_vars.get("SUPABASE_SERVICE_KEY", "")

    public_url = f"{supabase_url.rstrip('/')}/storage/v1/object/public/firmwares/{storage_path}"

    release_record = {
        "profile": profile,
        "env": env_name,
        "target_chip": chip,
        "version": version_str,
        "build_timestamp": unix_ts,
        "file_name": file_name,
        "storage_path": storage_path,
        "file_url": public_url,
        "file_size": size,
        "checksum_sha256": sha256,
        "checksum_md5": md5,
        "is_latest": True,
        "changelog": changelog or f"Build {bt['build_str']}",
    }

    print(f"  • File:       {bin_path.name} ({size:,} bytes / {size / 1024:.1f} KB)")
    print(f"  • Version:    {version_str}")
    print(f"  • Build time: {bt['build_str']} (UNIX: {unix_ts})")
    print(f"  • SHA256:     {sha256}")
    print(f"  • MD5:        {md5}")
    print(f"  • S3 Path:    firmwares/{storage_path}")
    print(f"  • Public URL: {public_url}")

    if dry_run:
        print("\n[DRY RUN] Bỏ qua bước upload lên Supabase.")
        return True

    if not supabase_url or not service_key:
        print("\n[ERROR] Thiếu SUPABASE_URL hoặc SUPABASE_SERVICE_KEY trong file .env hoặc env vars.")
        return False

    print("\n[1/2] Đang tải file binary lên Supabase Storage (bucket: firmwares)...")
    with open(bin_path, "rb") as f:
        file_bytes = f.read()

    try:
        final_url = upload_to_supabase_storage(supabase_url, service_key, "firmwares", storage_path, file_bytes)
        print(f"      ✔ Đã upload thành công lên Storage!")
    except Exception as e:
        print(f"      ✖ Lỗi upload Storage: {e}")
        return False

    print("[2/2] Đang lưu thông tin release vào Supabase Database (public.firmware_releases)...")
    try:
        inserted = insert_firmware_release(supabase_url, service_key, release_record)
        release_id = inserted.get("id", "N/A")
        print(f"      ✔ Đã tạo bản ghi release ID: {release_id}")
    except Exception as e:
        print(f"      ✖ Lỗi insert Database: {e}")
        return False

    print("\n" + "─"*60)
    print("✅ HOÀN TẤT UPLOAD FIRMWARE THÀNH CÔNG!")
    print(f"Download URL: {final_url}")
    print(f"\nVí dụ Payload lệnh otaUrl (gửi qua MQTT hoặc App):")
    ota_cmd = {
        "cmd": "otaUrl",
        "payload": {
            "url": final_url
        }
    }
    print(json.dumps(ota_cmd, indent=2))
    print("─"*60)
    return True


def main():
    parser = argparse.ArgumentParser(description="Upload Firmware Build lên Supabase Storage & Database")
    parser.add_argument("-e", "--env", help="Tên PlatformIO environment (ví dụ: remote_switch_esp8266, pump-ln882h, switch_esp32, hoặc 'all')")
    parser.add_argument("-b", "--build", action="store_true", help="Chạy pio run để build lại firmware trước khi upload")
    parser.add_argument("-v", "--version", help="Chỉ định chuỗi version tùy biến (mặc định tự tạo theo ngày và timestamp)")
    parser.add_argument("-c", "--changelog", default="", help="Nội dung ghi chú thay đổi (changelog)")
    parser.add_argument("--dry-run", action="store_true", help="Chỉ hiển thị thông tin build/hash mà không upload lên Supabase")
    parser.add_argument("--list", action="store_true", help="Liệt kê danh sách các môi trường có sẵn trong platformio.ini")

    args = parser.parse_args()

    # Tìm đường dẫn repo
    current_dir = Path(__file__).resolve().parent
    repo_root = current_dir.parent
    firmware_dir = repo_root / "firmware"

    env_vars = load_env(repo_root)
    envs = parse_platformio_ini(firmware_dir)

    if args.list:
        print("\nDanh sách PlatformIO Environments:")
        for name, info in envs.items():
            print(f"  • {name:<26} [Profile: {info['profile']:<14} Chip: {info['chip']:<8} Platform: {info['platform']}]")
        return

    selected_env = args.env
    if not selected_env:
        # Hiển thị menu chọn interactive
        env_keys = list(envs.keys())
        print("\nChọn PlatformIO Environment để upload:")
        for idx, k in enumerate(env_keys, 1):
            print(f"  [{idx}] {k:<26} ({envs[k]['profile']} / {envs[k]['chip']})")
        print(f"  [{len(env_keys) + 1}] ALL (Upload tất cả các env)")
        print(f"  [0] Thoát")

        try:
            choice = input("\nNhập số lựa chọn: ").strip()
            if not choice or choice == "0":
                print("Đã hủy.")
                return
            c_int = int(choice)
            if c_int == len(env_keys) + 1:
                selected_env = "all"
            elif 1 <= c_int <= len(env_keys):
                selected_env = env_keys[c_int - 1]
            else:
                print("Lựa chọn không hợp lệ.")
                return
        except (ValueError, KeyboardInterrupt):
            print("\nĐã hủy.")
            return

    if selected_env == "all":
        success_count = 0
        total = len(envs)
        for env_name, env_info in envs.items():
            ok = process_single_env(
                env_info=env_info,
                firmware_dir=firmware_dir,
                env_vars=env_vars,
                custom_version=args.version,
                changelog=args.changelog,
                do_build=args.build,
                dry_run=args.dry_run,
            )
            if ok:
                success_count += 1
        print(f"\nTổng kết: {success_count}/{total} environments đã xử lý thành công.")
    else:
        if selected_env not in envs:
            print(f"[ERROR] Environment '{selected_env}' không tồn tại trong platformio.ini.")
            print(f"Các env khả dụng: {', '.join(envs.keys())}")
            sys.exit(1)

        ok = process_single_env(
            env_info=envs[selected_env],
            firmware_dir=firmware_dir,
            env_vars=env_vars,
            custom_version=args.version,
            changelog=args.changelog,
            do_build=args.build,
            dry_run=args.dry_run,
        )
        if not ok:
            sys.exit(1)


if __name__ == "__main__":
    main()
