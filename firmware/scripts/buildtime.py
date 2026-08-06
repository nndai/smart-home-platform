import os
from datetime import datetime

Import("env")


def generate(env):
    header = os.path.join(env.subst("$PROJECT_DIR"), "include", "build_time.h")
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


generate(env)
