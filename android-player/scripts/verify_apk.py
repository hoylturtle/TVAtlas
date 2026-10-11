"""Verify release identity; unsigned signing inputs are never advertised as updates."""
import argparse
import re
import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--unsigned-input', action='store_true')
args = parser.parse_args()
apk = root / ("app/build/outputs/apk/release/app-release-unsigned.apk" if args.unsigned_input else "app/build/outputs/apk/release/app-release.apk")
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tools = sdk / "build-tools/35.0.0"
badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
assert "name='com.tvatlas.player'" in badging
assert "application-debuggable" not in badging
assert "sdkVersion:'23'" in badging
assert "targetSdkVersion:'35'" in badging
info = json.loads((root / "release-info.json").read_text())
assert f"versionName='{info["versionName"]}'" in badging
assert f"versionCode='{info["versionCode"]}'" in badging
assert f"sdkVersion:'{info["minSdk"]}'" in badging
if args.unsigned_input:
    signing = "UNSIGNED SIGNING INPUT — not installable; not an update artifact"
else:
    signing = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})", signing)
    assert certificate and certificate.group(1) == info["signingCertificateSha256"], "Signing identity changed"
    assert "Signer #2" not in signing, "Unexpected additional signer"
with zipfile.ZipFile(apk) as archive:
    assert "AndroidManifest.xml" in archive.namelist()
    assert "classes.dex" in archive.namelist()
    for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
        assert f"lib/{abi}/libmihomo.so" in archive.namelist(), f"Missing core for {abi}"
dist = root / "dist"
dist.mkdir(exist_ok=True)
named = dist / (f"TVAtlas-v{info['versionName']}-unsigned.apk" if args.unsigned_input else f"TVAtlas-Player-v{info['versionName']}-release.apk")
shutil.copyfile(apk, named)
digest = hashlib.sha256(named.read_bytes()).hexdigest()
counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for module, task in [("core", "test"), ("app", "testDebugUnitTest")]:
    results = list((root / module / "build/test-results" / task).glob("TEST-*.xml"))
    assert results, f"Missing {module} test results"
    for result in results:
        suite = ET.parse(result).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, "0"))
assert counts["tests"] >= 28 and counts["failures"] == counts["errors"] == counts["skipped"] == 0, counts
(dist / "SHA256SUMS.txt").write_text(f"{digest}  {named.name}\n")
(dist / "build-info.txt").write_text(
    f"TVAtlas Player v{info['versionName']} ({'unsigned signing input' if args.unsigned_input else 'fixed release signature'})\nCommit: {os.environ.get('GITHUB_SHA', 'local')}\n"
    f"SHA-256: {digest}\nTests: {counts}\n\n{badging}\n{signing}"
)
print(badging.splitlines()[0])
print(signing)
print(f"Test results: {counts}")
print(f"Verified APK: {named.name}; bytes={named.stat().st_size}; sha256={digest}")
