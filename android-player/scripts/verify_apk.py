"""Verify and name the installable v0.1.0 debug APK after Gradle checks pass."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
apk = root / "app/build/outputs/apk/debug/app-debug.apk"
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tools = sdk / "build-tools/35.0.0"
badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
assert "name='com.tvatlas.player'" in badging
assert "versionName='0.1.0'" in badging
assert "sdkVersion:'23'" in badging
assert "targetSdkVersion:'35'" in badging
signing = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
with zipfile.ZipFile(apk) as archive:
    assert "AndroidManifest.xml" in archive.namelist()
    assert "classes.dex" in archive.namelist()
dist = root / "dist"
dist.mkdir(exist_ok=True)
named = dist / "TVAtlas-Player-v0.1.0-debug.apk"
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
assert counts["tests"] >= 18 and counts["failures"] == counts["errors"] == counts["skipped"] == 0, counts
(dist / "SHA256SUMS.txt").write_text(f"{digest}  {named.name}\n")
(dist / "build-info.txt").write_text(
    f"TVAtlas Player v0.1.0 (debug signed)\nCommit: {os.environ.get('GITHUB_SHA', 'local')}\n"
    f"SHA-256: {digest}\nTests: {counts}\n\n{badging}\n{signing}"
)
print(badging.splitlines()[0])
print(signing)
print(f"Test results: {counts}")
print(f"Verified APK: {named.name}; bytes={named.stat().st_size}; sha256={digest}")
