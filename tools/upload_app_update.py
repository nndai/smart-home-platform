import argparse
import os
import sys
from dotenv import load_dotenv
from supabase import create_client, Client

def main():
    parser = argparse.ArgumentParser(description="Upload Android APK update directly to Supabase Storage & Log to DB")
    parser.add_argument("--apk", required=True, help="Path to the APK file")
    parser.add_argument("--version-code", required=True, type=int, help="Version code (e.g. 2)")
    parser.add_argument("--version-name", required=True, help="Version name (e.g. 1.0.1)")
    parser.add_argument("--notes", required=True, help="Release notes")
    parser.add_argument("--mandatory", required=True, choices=["true", "false"], help="Is this a mandatory update?")
    args = parser.parse_args()

    # Load environment variables
    env_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".env")
    load_dotenv(env_path)

    supa_url: str = os.environ.get("SUPABASE_URL")
    supa_key: str = os.environ.get("SUPABASE_SERVICE_KEY")

    if not supa_url or not supa_key:
        print("Error: SUPABASE_URL and SUPABASE_SERVICE_KEY must be set in .env")
        sys.exit(1)

    if not os.path.exists(args.apk):
        print(f"Error: APK file not found at {args.apk}")
        sys.exit(1)

    supabase: Client = create_client(supa_url, supa_key)

    # 1. Check if version_code already exists in Supabase
    try:
        res = supabase.table("app_versions").select("id").eq("version_code", args.version_code).execute()
        if res.data and len(res.data) > 0:
            print(f"Error: Version code {args.version_code} already exists in Supabase table 'app_versions'. Please increment version-code.")
            sys.exit(1)
    except Exception as e:
        print(f"Warning: Could not check version_code existence: {e}")

    # 2. Upload APK to Supabase Storage
    bucket_name = "app-releases"
    file_name = os.path.basename(args.apk)
    destination_path = f"{args.version_code}_{file_name}"

    file_size_mb = os.path.getsize(args.apk) / (1024 * 1024)
    print(f"Uploading {args.apk} ({file_size_mb:.2f} MB) to Supabase bucket '{bucket_name}' as '{destination_path}'...")

    try:
        with open(args.apk, "rb") as f:
            supabase.storage.from_(bucket_name).upload(
                path=destination_path,
                file=f,
                file_options={
                    "content-type": "application/vnd.android.package-archive",
                    "upsert": "true"
                }
            )
    except Exception as e:
        print(f"Error uploading to Supabase Storage: {e}")
        sys.exit(1)

    download_url = supabase.storage.from_(bucket_name).get_public_url(destination_path)
    print(f"Upload complete. Public download URL: {download_url}")

    # 3. Insert into Supabase table
    is_mandatory = args.mandatory == "true"

    print(f"Inserting version {args.version_code} into Supabase app_versions table...")
    try:
        supabase.table("app_versions").insert({
            "version_code": args.version_code,
            "version_name": args.version_name,
            "download_url": download_url,
            "release_notes": args.notes,
            "is_mandatory": is_mandatory
        }).execute()
    except Exception as e:
        print(f"Error inserting record into app_versions: {e}")
        sys.exit(1)

    print("Success! App update published directly to Supabase.")

if __name__ == "__main__":
    main()
