#!/usr/bin/env python3
"""Build and verify a signed beta APK on Windows, macOS, or Linux.

Requires Python 3.9+, JDK 17, Android SDK platform 35 and Build Tools 35.0.0.
Set ANDROID_HOME (or ANDROID_SDK_ROOT) to the SDK directory. Signing uses
EVEN_COMPANION_SIGNING_PROPERTIES, defaulting to
~/.android/even-companion-signing.properties. Keep that properties file and its
keystore outside the checkout; never pass passwords as command-line arguments.

The properties file defines storeFile, storePassword, keyAlias, keyPassword.
Use an absolute storeFile path or a path relative to the properties file.
On Windows, Java properties paths should use forward slashes.

Run: python3 scripts/build_release.py (Windows: py -3 scripts/build_release.py).
The helper runs unit tests and lint, verifies the release signature and packaged
security controls, then writes the versioned APK and SHA256SUMS to artifacts/.
It does not create signing keys, install on devices, or publish anything.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS = "35.0.0"


def outside_checkout(path):
    try:
        path.resolve().relative_to(ROOT)
        return False
    except ValueError:
        return True


def run(args, capture=False):
    # Arguments are passed separately; never interpolate paths or credentials into a shell.
    return subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None).stdout


def verify_manifest(manifest, badging):
    """Fail closed if the packaged release loses its Android security controls."""
    for name in ("usesCleartextTraffic", "allowBackup"):
        if not re.search(r"android:" + name + r"\(0x[0-9a-f]+\)=\(type 0x12\)0x0\b", manifest):
            raise ValueError(f"Release manifest must explicitly disable {name}.")
    if ("application-debuggable" in badging or
            re.search(r"android:debuggable\(0x[0-9a-f]+\)=\(type 0x12\)(?!0x0\b)", manifest)):
        raise ValueError("Refusing a debuggable APK.")
    if "E: receiver " in manifest or "DebugNotificationReceiver" in manifest or "DebugReplyReceiver" in manifest:
        raise ValueError("Release APK must not contain simulation receivers.")
    if "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" not in manifest:
        raise ValueError("Notification listener must retain its Android permission boundary.")
    if "package: name='dev.evenbridge.companion'" not in badging:
        raise ValueError("Unexpected Android application ID.")


def sdk_tools():
    sdk_path = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_path:
        raise ValueError("Set ANDROID_HOME to your Android SDK directory.")
    directory = Path(sdk_path).expanduser() / "build-tools" / BUILD_TOOLS
    aapt = directory / ("aapt.exe" if os.name == "nt" else "aapt")
    # Invoke the jar directly to avoid Windows .bat shell argument handling.
    signer = directory / "lib" / "apksigner.jar"
    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java") if java_home else shutil.which("java")
    if not aapt.is_file() or not signer.is_file() or not java:
        raise ValueError("Install JDK 17 and Android SDK Build Tools 35.0.0 first.")
    return java, signer, aapt


def verify_apk(apk):
    java, signer, aapt = sdk_tools()
    signature = run([java, "-jar", signer, "verify", "--verbose", "--print-certs", apk], capture=True)
    if "CN=Android Debug" in signature:
        raise ValueError("Refusing an APK signed with an Android debug certificate.")
    manifest = run([aapt, "dump", "xmltree", apk, "AndroidManifest.xml"], capture=True)
    badging = run([aapt, "dump", "badging", apk], capture=True)
    verify_manifest(manifest, badging)
    with ZipFile(apk) as archive:
        for notice in ("assets/LICENSE.txt", "assets/THIRD_PARTY_NOTICES.txt", "okhttp3/internal/publicsuffix/NOTICE"):
            if notice not in archive.namelist() or not archive.read(notice).strip():
                raise ValueError(f"Release APK is missing license notice: {notice}")
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", signature)
    if not match:
        raise ValueError("Could not identify the release signing certificate.")
    print(f"Verified release certificate SHA-256: {match.group(1)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify-apk", type=Path, help="Verify an existing signed release APK without rebuilding")
    args = parser.parse_args()
    if args.verify_apk:
        verify_apk(args.verify_apk.resolve())
        return
    signing = Path(os.environ.get("EVEN_COMPANION_SIGNING_PROPERTIES", str(Path.home() / ".android" / "even-companion-signing.properties"))).expanduser().resolve()
    if not signing.is_file() or not outside_checkout(signing):
        raise ValueError("Provide an existing signing properties file outside the checkout; see --help.")
    if os.name != "nt" and signing.stat().st_mode & 0o077:
        raise ValueError("Restrict signing properties to their owner (chmod 600) before building.")
    sdk_tools()
    # Calling the checked-in wrapper JAR directly is portable and avoids a Windows shell.
    java = sdk_tools()[0]
    run([java, "-classpath", ROOT / "gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain",
         "--no-daemon", ":app:testDebugUnitTest", ":app:testReleaseUnitTest", ":app:lintDebug",
         ":app:lintRelease", ":app:assembleRelease"])
    output = ROOT / "app/build/outputs/apk/release"
    metadata = json.loads((output / "output-metadata.json").read_text())
    elements = metadata["elements"]
    if len(elements) != 1:
        raise ValueError("Expected one universal release APK.")
    element = elements[0]
    apk = output / element["outputFile"]
    verify_apk(apk)
    version = element["versionName"]
    if not re.fullmatch(r"[0-9A-Za-z.+-]+", version):
        raise ValueError("Invalid release version.")
    artifacts = ROOT / "artifacts"
    artifacts.mkdir(exist_ok=True)
    destination = artifacts / f"even-phone-companion-{version}-release.apk"
    shutil.copyfile(apk, destination)
    checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
    # Keep each version's checksum next to its APK; SHA256SUMS describes this build.
    checksum_line = f"{checksum}  {destination.name}\n"
    (artifacts / "SHA256SUMS").write_text(checksum_line)
    destination.with_suffix(".apk.sha256").write_text(checksum_line)
    mapping = ROOT / "app/build/outputs/mapping/release/mapping.txt"
    if mapping.is_file():
        shutil.copyfile(mapping, artifacts / f"even-phone-companion-{version}-mapping.txt")
    print(f"Release ready: {destination}\nSHA-256: {checksum}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)
