"""
Upload Android APK update directly to Supabase Storage & Log to DB.
Uses standard library urllib.request to avoid Windows httpx / httpcore socket timeout issues (WinError 10054).
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def load_env(repo_root: Path) -> dict:
    """Read environment variables from root .env file and os.environ."""
    env_vars = {}
    env_file = repo_root / ".env"
    if env_file.exists():
        with open(env_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, _, v = line.partition("=")
                env_vars[k.strip()] = v.strip().strip('"').strip("'")

    # System environment variables take precedence
    for k in ["SUPABASE_URL", "SUPABASE_SERVICE_KEY", "SUPABASE_ANON_KEY"]:
        if os.environ.get(k):
            env_vars[k] = os.environ[k]

    return env_vars


def check_version_exists(supabase_url: str, service_key: str, version_code: int) -> bool:
    """Check if version_code already exists in table public.app_versions."""
    url = f"{supabase_url.rstrip('/')}/rest/v1/app_versions?version_code=eq.{version_code}&select=id"
    headers = {
        "apikey": service_key,
        "Authorization": f"Bearer {service_key}",
    }
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return len(data) > 0
    except Exception as e:
        print(f"Warning: Could not check version_code existence: {e}")
        return False


def upload_apk_to_storage(
    supabase_url: str,
    service_key: str,
    bucket: str,
    storage_path: str,
    apk_path: Path,
) -> str:
    """Upload APK file to Supabase Storage using REST API."""
    url_path = urllib.parse.quote(storage_path, safe="/")
    url = f"{supabase_url.rstrip('/')}/storage/v1/object/{bucket}/{url_path}"

    with open(apk_path, "rb") as f:
        file_bytes = f.read()

    headers = {
        "Authorization": f"Bearer {service_key}",
        "apikey": service_key,
        "Content-Type": "application/vnd.android.package-archive",
        "x-upsert": "true",
    }

    req = urllib.request.Request(url, data=file_bytes, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            if resp.status in (200, 201):
                return f"{supabase_url.rstrip('/')}/storage/v1/object/public/{bucket}/{url_path}"
            raise RuntimeError(f"Storage upload returned status {resp.status}")
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"Supabase Storage error {e.code}: {err_msg}") from e


def insert_app_version(
    supabase_url: str,
    service_key: str,
    payload: dict,
) -> dict:
    """Insert app version record into public.app_versions table."""
    url = f"{supabase_url.rstrip('/')}/rest/v1/app_versions"
    headers = {
        "Authorization": f"Bearer {service_key}",
        "apikey": service_key,
        "Content-Type": "application/json",
        "Prefer": "return=representation",
    }
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if isinstance(data, list) and len(data) > 0:
                return data[0]
            return data
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"Supabase DB insert error {e.code}: {err_msg}") from e


def main():
    parser = argparse.ArgumentParser(description="Upload Android APK update directly to Supabase Storage & Log to DB")
    parser.add_argument("--apk", required=True, help="Path to the APK file")
    parser.add_argument("--version-code", required=True, type=int, help="Version code (e.g. 2)")
    parser.add_argument("--version-name", required=True, help="Version name (e.g. 1.0.1)")
    parser.add_argument("--notes", required=True, help="Release notes")
    parser.add_argument("--mandatory", required=True, choices=["true", "false"], help="Is this a mandatory update?")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    env_vars = load_env(repo_root)

    supa_url = env_vars.get("SUPABASE_URL")
    supa_key = env_vars.get("SUPABASE_SERVICE_KEY")

    if not supa_url or not supa_key:
        print("Error: SUPABASE_URL and SUPABASE_SERVICE_KEY must be set in .env")
        sys.exit(1)

    apk_path = Path(args.apk)
    if not apk_path.is_absolute():
        apk_path = (repo_root / apk_path).resolve()

    if not apk_path.exists():
        print(f"Error: APK file not found at {apk_path}")
        sys.exit(1)

    # 1. Check if version_code already exists
    if check_version_exists(supa_url, supa_key, args.version_code):
        print(f"Error: Version code {args.version_code} already exists in Supabase table 'app_versions'. Please increment version-code.")
        sys.exit(1)

    # 2. Upload APK to Supabase Storage
    bucket_name = "app-releases"
    file_name = apk_path.name
    destination_path = f"{args.version_code}_{file_name}"

    file_size_mb = apk_path.stat().st_size / (1024 * 1024)
    print(f"Uploading {apk_path} ({file_size_mb:.2f} MB) to Supabase bucket '{bucket_name}' as '{destination_path}'...")

    try:
        download_url = upload_apk_to_storage(supa_url, supa_key, bucket_name, destination_path, apk_path)
    except Exception as e:
        print(f"Error uploading to Supabase Storage: {e}")
        sys.exit(1)

    print(f"Upload complete. Public download URL: {download_url}")

    # 3. Insert into Supabase table
    is_mandatory = args.mandatory.lower() == "true"
    record = {
        "version_code": args.version_code,
        "version_name": args.version_name,
        "download_url": download_url,
        "release_notes": args.notes,
        "is_mandatory": is_mandatory,
    }

    print(f"Inserting version {args.version_code} into Supabase app_versions table...")
    try:
        res = insert_app_version(supa_url, supa_key, record)
        print("Success! App update published directly to Supabase:")
        print(json.dumps(res, indent=2))
    except Exception as e:
        print(f"Error inserting record into app_versions: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
