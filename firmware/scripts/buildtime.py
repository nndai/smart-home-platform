import os
from datetime import datetime

Import("env")


def _read_env_secret(project_dir):
    """Read FW_SECRET from the repo-root .env (one level above the firmware dir).

    .env is git-ignored, so the secret never lands in the repo. Returns "" when
    the key is missing or empty so builds keep working without a secret
    (firmware then falls back to seed = deviceId only, i.e. old behavior).
    """
    env_path = os.path.normpath(os.path.join(project_dir, "..", ".env"))
    try:
        with open(env_path, "r", encoding="utf-8") as f:
            for raw in f:
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                if key.strip() == "FW_SECRET":
                    return value.strip()
    except OSError:
        pass
    return ""


def generate(env):
    project_dir = env.subst("$PROJECT_DIR")
    header = os.path.join(project_dir, "include", "build_time.h")
    now = datetime.now()
    build_str = now.strftime("%Y-%m-%d %H:%M:%S")
    content = (
        "#pragma once\n"
        f"#define BUILD_UNIX_TIME {int(now.timestamp())}u\n"
        f'#define BUILD_STR "{build_str}"\n'
    )
    if not os.path.exists(header):
        old = None
    else:
        with open(header, "r", encoding="utf-8") as f:
            old = f.read()
    if old != content:
        with open(header, "w", encoding="utf-8") as f:
            f.write(content)

    # Build-time secret → DEFINE FW_SECRET="..." (dùng chung seed mã hóa mqttPass:
    # key = SHA-256(deviceId + FW_SECRET), xem main.cpp). Chỉ gắn flag khi có giá
    # trị (giá trị rỗng → main.cpp tự fallback FW_SECRET=""); giá trị phải là
    # hex/alnum không có dấu nháy — được nhúng thẳng vào build flag.
    secret = _read_env_secret(project_dir)
    if secret:
        env.Append(BUILD_FLAGS=[f'-DFW_SECRET=\\"{secret}\\"'])


generate(env)
