"""Run storage fault/recovery tests without an MCU or network connection."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def kotlin_check(root, output, gradle_cache, java, fixture):
    modules = gradle_cache / "caches/modules-2/files-2.1"

    def jar(group, artifact):
        files = [p for p in (modules / group / artifact).rglob("*.jar")
                 if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if not files:
            raise RuntimeError(f"Missing cached Kotlin dependency: {artifact}")
        return sorted(files, reverse=True)[0]

    compiler = jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib")
    dependencies = [compiler, stdlib,
                    jar("org.jetbrains.kotlin", "kotlin-reflect"),
                    jar("org.jetbrains.kotlin", "kotlin-script-runtime"),
                    jar("org.jetbrains.intellij.deps", "trove4j"),
                    jar("org.jetbrains", "annotations"),
                    jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")]
    classpath = os.pathsep.join(map(str, dependencies))
    classes = output / "kotlin"
    run(java, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-Xskip-metadata-version-check", "-classpath", stdlib,
        "-d", classes,
        root / "app/app/src/main/java/com/nndai/myhome/data/local/LogRecordCodec.kt",
        root / "app/app/src/test/java/com/nndai/myhome/protocol/LogRecordCodecHostTest.kt")
    run(java, "-cp", os.pathsep.join(map(str, [classes, stdlib])),
        "com.nndai.myhome.protocol.LogRecordCodecHostTestKt", fixture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=shutil.which("g++") or "g++")
    parser.add_argument("--kotlin", action="store_true")
    parser.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle")
    parser.add_argument("--java", default=shutil.which("java") or "java")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = root / ".cache/log-storage-checks"
    output.mkdir(parents=True, exist_ok=True)
    executable = output / ("log_storage_test.exe" if os.name == "nt" else "log_storage_test")
    run(args.cxx, "-std=c++17", "-Wall", "-Wextra", "-Werror",
        "-I" + str(root / "firmware/tests/host"), "-I" + str(root / "firmware/src"),
        root / "firmware/tests/host/log_storage_test.cpp",
        root / "firmware/src/core/log/LogStorageManager.cpp",
        root / "firmware/src/core/log/LogRecordCodec.cpp", "-o", executable)
    with tempfile.TemporaryDirectory(prefix="store-", dir=output) as directory:
        store = Path(directory).resolve()
        assert store.parent == output.resolve()
        run(executable, store, "main")
        run(executable, store, "reboot")
        # Recover again with both checkpoints damaged and an interrupted final record.
        for slot in store.glob("logs/v2/*/catalog.*"):
            slot.write_bytes(b"interrupted checkpoint")
        newest = sorted(store.glob("logs/v2/sys/*.seg"))[-1]
        with newest.open("ab") as file:
            file.write(b"\x52\x02\x01\x00\xff")
        run(executable, store, "reboot")
        run(executable, store, "codec")
        if args.kotlin:
            kotlin_check(root, output, args.gradle_cache, args.java, store / "codec.bin")
    print("Storage checks passed.")


if __name__ == "__main__":
    main()
