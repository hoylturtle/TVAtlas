"""Export only synthetic Android UI fixtures for connector-based visual review.

No live app, subscription, URL, token or private user configuration is captured.
"""
import base64
from pathlib import Path

root = Path('app/build/reports/androidTests')
for name in ('tv-layout.png', 'phone-proxy-layout.png', 'phone-settings-layout.png', 'phone-recovery-layout.png', 'fullscreen-layout.png', 'fullscreen-stopped-layout.png', 'mobile-failure-layout.png'):
    path = root / name
    if not path.is_file():
        raise SystemExit(f'Missing audit fixture: {name}')
    print(f'TVATLAS_AUDIT_IMAGE_BEGIN:{name}')
    print(base64.b64encode(path.read_bytes()).decode('ascii'))
    print(f'TVATLAS_AUDIT_IMAGE_END:{name}')
