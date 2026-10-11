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
test_apk = root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
def install(path, test=False):
    command = ['adb', 'install', '-r'] + (['-t'] if test else []) + [str(path)]
    output = subprocess.check_output(command, text=True)
    assert 'Success' in output, 'APK install failed'
def instrumentation(phase):
    run = subprocess.run(['adb','shell','am','instrument','-w','-r','-e','class',
        'com.tvatlas.player.UpgradeInstallTest','-e','upgradePhase',phase,
        'com.tvatlas.player.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True)
    print(run.stdout)
    if run.stderr: print(run.stderr)
    assert run.returncode == 0 and 'OK (1 test)' in run.stdout and 'FAILURES' not in run.stdout, 'Upgrade instrumentation failed'
# AGP cleans up installed test packages after connected tests. Seed explicitly using
# the baseline APK and instrumentation APK so this check is independent of that cleanup.
install(apk)
install(test_apk, test=True)
instrumentation('seed')
subprocess.run(['gradle', ':app:assembleDebug', f'-PtvatlasVersionCode={next_code}', '--stacktrace'], cwd=root, check=True)
assert certificate() == before, 'Signer changed across builds'
install(apk)
print(f'UPGRADE: same-certificate adb install -r succeeded for versionCode {base_code} -> {next_code}')
install(test_apk, test=True)
instrumentation('verify')
print('UPGRADE: private file, selected-node preference and previous version persisted')
