"""Run on an emulator: install newer same-signer build without uninstalling, verify persistent data."""
import json
import os
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
apksigner = sdk / 'build-tools/35.0.0/apksigner'
def certificate():
    result = subprocess.check_output([str(apksigner), 'verify', '--print-certs', str(apk)], text=True)
    return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})', result).group(1)
before = certificate()
base_code = json.loads((root / "release-info.json").read_text())["versionCode"]
next_code = base_code + 1
# connectedDebugAndroidTest seeded a private marker on the current installed app.
subprocess.run(['gradle', ':app:assembleDebug', f'-PtvatlasVersionCode={next_code}', '--stacktrace'], cwd=root, check=True)
assert certificate() == before, 'Signer changed across builds'
install = subprocess.check_output(['adb', 'install', '-r', str(apk)], text=True)
assert 'Success' in install, 'Overwrite install failed'
print(f'UPGRADE: same-certificate adb install -r succeeded for versionCode {base_code} -> {next_code}')
result = subprocess.check_output(['adb','shell','am','instrument','-w','-r','-e','class',
    'com.tvatlas.player.UpgradeInstallTest','-e','upgradePhase','verify',
    'com.tvatlas.player.test/androidx.test.runner.AndroidJUnitRunner'], text=True)
print(result)
assert 'OK (1 test)' in result and 'FAILURES' not in result, 'Data retention check failed'
print('UPGRADE: private file, selected-node preference and previous version persisted')
