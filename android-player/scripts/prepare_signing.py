"""Restore the private release key from one Actions secret; never generate a replacement in CI."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
info = json.loads((root / 'release-info.json').read_text())
bundle = os.environ.get('TVATLAS_SIGNING_BUNDLE', '')
output = os.environ.get('GITHUB_OUTPUT')
if not bundle:
    if output:
        with open(output, 'a') as f:
            f.write('signed=false\n')
    print('Release key unavailable: build signing input only; no installable update will be published.')
    sys.exit(0)
try:
    data = json.loads(bundle)
    for name in ('keystore_base64', 'store_password', 'key_password', 'alias'):
        if not isinstance(data.get(name), str) or not data[name] or any(c in data[name] for c in '\r\n'):
            raise ValueError('Invalid signing bundle')
    key = base64.b64decode(data['keystore_base64'], validate=True)
    if not 1024 <= len(key) <= 1024 * 1024:
        raise ValueError('Invalid keystore length')
    folder = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'tvatlas-signing'
    folder.mkdir(mode=0o700, exist_ok=True)
    folder.chmod(0o700)
    key_path = folder / 'release.jks'
    key_path.write_bytes(key)
    key_path.chmod(0o600)
    env = dict(os.environ, TVATLAS_STORE_PASSWORD=data['store_password'])
    cert = subprocess.run(['keytool', '-exportcert', '-keystore', str(key_path), '-alias', data['alias'],
        '-storepass:env', 'TVATLAS_STORE_PASSWORD'], env=env, check=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE).stdout
    fingerprint = hashlib.sha256(cert).hexdigest()
    if fingerprint != info['signingCertificateSha256']:
        raise ValueError('Release certificate differs from pinned identity')
    def escape(value):
        return value.replace('\\', '\\\\').replace(':', '\\:').replace('=', '\\=').replace(' ', '\\ ')
    props = folder / 'signing.properties'
    props.write_text('\n'.join(k + '=' + escape(v) for k,v in {
        'storeFile':str(key_path), 'storePassword':data['store_password'],
        'keyAlias':data['alias'], 'keyPassword':data['key_password']}.items()) + '\n')
    props.chmod(0o600)
    if os.environ.get('GITHUB_ENV'):
        with open(os.environ['GITHUB_ENV'], 'a') as f:
            f.write('TVATLAS_SIGNING_PROPERTIES=' + str(props) + '\n')
    if output:
        with open(output, 'a') as f:
            f.write('signed=true\n')
    print('Pinned release certificate:', fingerprint)
except Exception:
    print('Release signing setup failed. Verify the private bundle; credentials omitted.', file=sys.stderr)
    sys.exit(1)
