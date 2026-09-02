import argparse
import os
import sys
from dotenv import load_dotenv
from supabase import create_client, Client
from github import Github
from github import Auth

def main():
    parser = argparse.ArgumentParser(description="Upload Android APK update to GitHub Releases & Log to Supabase")
    parser.add_argument("--apk", required=True, help="Path to the APK file")
    parser.add_argument("--version-code", required=True, type=int, help="Version code (e.g. 15)")
    parser.add_argument("--version-name", required=True, help="Version name (e.g. 1.0.5)")
    parser.add_argument("--notes", required=True, help="Release notes")
    parser.add_argument("--mandatory", required=True, choices=["true", "false"], help="Is this a mandatory update?")
    args = parser.parse_args()

    # Load environment variables
    env_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".env")
    load_dotenv(env_path)

    supa_url: str = os.environ.get("SUPABASE_URL")
    supa_key: str = os.environ.get("SUPABASE_SERVICE_KEY")
    github_token: str = os.environ.get("GITHUB_PAT")
    github_repo: str = os.environ.get("GITHUB_REPO") # e.g. "nndai/smart-home-platform"

    if not supa_url or not supa_key:
        print("Error: SUPABASE_URL and SUPABASE_SERVICE_KEY must be set in .env")
        sys.exit(1)
        
    if not github_token or not github_repo:
        print("Error: GITHUB_PAT and GITHUB_REPO must be set in .env")
        sys.exit(1)

    if not os.path.exists(args.apk):
        print(f"Error: APK file not found at {args.apk}")
        sys.exit(1)

    # 1. Upload to GitHub Releases
    auth = Auth.Token(github_token)
    g = Github(auth=auth)
    
    try:
        repo = g.get_repo(github_repo)
    except Exception as e:
        print(f"Error accessing GitHub repo '{github_repo}': {e}")
        sys.exit(1)
        
    tag_name = f"app-v{args.version_name}-b{args.version_code}"
    
    # Check if tag already exists on GitHub
    try:
        repo.get_release(tag_name)
        print(f"Error: Version '{tag_name}' already exists on GitHub Releases. Please increment version-code.")
        sys.exit(1)
    except Exception:
        pass # Release doesn't exist, we can proceed
        
    # Check if version_code already exists on Supabase
    supabase: Client = create_client(supa_url, supa_key)
    try:
        res = supabase.table("app_versions").select("id").eq("version_code", args.version_code).execute()
        if res.data and len(res.data) > 0:
            print(f"Error: Version code {args.version_code} already exists in Supabase database. Please increment version-code.")
            sys.exit(1)
    except Exception as e:
        pass # If error fetching, just continue and let the insert fail later if needed
        
    print(f"Creating GitHub Release {tag_name}...")
    try:
        release = repo.create_git_release(
            tag=tag_name,
            name=f"Android App v{args.version_name}",
            message=args.notes,
            draft=False,
            prerelease=False
        )
    except Exception as e:
        print(f"Error creating GitHub Release (Does tag already exist?): {e}")
        sys.exit(1)
        
    print(f"Uploading {args.apk} to GitHub Release {tag_name}...")
    file_name = os.path.basename(args.apk)
    try:
        asset = release.upload_asset(path=args.apk, name=file_name)
        download_url = asset.browser_download_url
        print(f"Upload complete. Public URL: {download_url}")
    except Exception as e:
        print(f"Error uploading asset: {e}")
        sys.exit(1)

    # 2. Insert into Supabase
    is_mandatory = args.mandatory == "true"
    
    print(f"Inserting version {args.version_code} into Supabase app_versions table...")
    data, count = supabase.table("app_versions").insert({
        "version_code": args.version_code,
        "version_name": args.version_name,
        "download_url": download_url,
        "release_notes": args.notes,
        "is_mandatory": is_mandatory
    }).execute()

    print("Success! App update published via GitHub Releases.")

if __name__ == "__main__":
    main()
