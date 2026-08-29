#!/usr/bin/env python3
"""Build, install and launch Crux on a real phone over adb.

    python3 scripts/device.py                  # debug build, the usual case
    python3 scripts/device.py --release        # the signed artefact CI ships
    python3 scripts/device.py --no-build       # install whatever Gradle produced last
    python3 scripts/device.py --if-connected   # do nothing, quietly, when no phone is around

The launcher icons are stamped with the build version before the build and restored after, so
the home screen says which build is on the phone and the working tree stays clean either way.

Pairing is a one-time thing done on the phone: Developer options -> Wireless debugging -> Pair
device with pairing code, then `adb pair <host>:<port> <code>`. Or just plug it in with USB
debugging turned on.
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app/build.gradle.kts"
APPLICATION_ID = "casa.crux.app"
LAUNCH_ACTIVITY = ".MainActivity"

SDK = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
           or Path.home() / "Android/Sdk")
ADB = SDK / "platform-tools/adb"
# adb ships two mDNS backends and the default one can come up dead -- `adb mdns check` prints
# nothing and discovery silently finds zero devices on a network where the phone is plainly
# advertising. Openscreen works. Forcing it costs nothing and removes a failure that looks
# exactly like "the phone is not on the network".
ADB_ENV = {**os.environ, "ADB_MDNS_OPENSCREEN": "1"}


def adb(*args, check=True, capture=True):
    binary = str(ADB) if ADB.exists() else "adb"
    return subprocess.run([binary, *args], env=ADB_ENV, check=check, text=True,
                          capture_output=capture)


def devices():
    out = adb("devices").stdout.splitlines()[1:]
    return [line.split("\t")[0] for line in out if line.strip().endswith("\tdevice")]


def gradle_version():
    text = GRADLE.read_text()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1)
    code = re.search(r"versionCode\s*=\s*(\d+)", text).group(1)
    return name, code


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", action="store_true")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--if-connected", action="store_true")
    args = ap.parse_args()

    if not ADB.exists() and not shutil_which("adb"):
        return skip(args, f"adb not found under {SDK}")

    found = devices()
    if not found:
        return skip(args, "no device in `adb devices`")
    if len(found) > 1:
        print(f"[device] {len(found)} devices attached, using {found[0]}")
    serial = found[0]

    version, code = gradle_version()
    variant = "release" if args.release else "debug"
    # The debug build carries an applicationId suffix, so it coexists with a release install.
    package = APPLICATION_ID if args.release else f"{APPLICATION_ID}.debug"

    stamped = False
    try:
        if not args.no_build:
            run(["python3", "scripts/stamp-icon.py", "--version", f"{version} {code}"])
            stamped = True
            task = "assembleRelease" if args.release else "assembleDebug"
            run(["./gradlew", f":app:{task}", "--console=plain"])
    finally:
        if stamped:
            run(["python3", "scripts/stamp-icon.py", "--restore"])

    apks = sorted((ROOT / f"app/build/outputs/apk/{variant}").glob("*.apk"))
    apks = [a for a in apks if "unsigned" not in a.name]
    if not apks:
        sys.exit(f"[device] no {variant} APK found — build first")
    apk = apks[0]

    print(f"[device] installing {apk.name} on {serial}")
    result = adb("-s", serial, "install", "-r", str(apk), check=False)
    if result.returncode != 0 or "Success" not in (result.stdout or ""):
        # A signature clash is the one failure with an obvious, destructive-if-silent fix, so
        # name it rather than dumping adb's output and leaving it to the reader.
        detail = (result.stdout or "") + (result.stderr or "")
        if "SIGNATURES" in detail or "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in detail:
            sys.exit(f"[device] {package} is installed with a different signing key.\n"
                     f"         Uninstall it first: adb -s {serial} uninstall {package}")
        sys.exit(f"[device] install failed:\n{detail.strip()}")

    adb("-s", serial, "shell", "am", "start", "-n", f"{package}/{APPLICATION_ID}{LAUNCH_ACTIVITY}")
    print(f"[device] launched {package} ({version} build {code})")


def run(cmd):
    subprocess.run(cmd, cwd=ROOT, check=True)


def skip(args, why):
    if args.if_connected:
        print(f"[device] {why} — skipping the device install")
        return 0
    sys.exit(f"[device] {why}")


def shutil_which(name):
    from shutil import which
    return which(name)


if __name__ == "__main__":
    sys.exit(main())
