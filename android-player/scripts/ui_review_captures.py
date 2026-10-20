"""Expose bounded synthetic emulator previews in CI logs for visual review.

Only the named UI fixture screenshots are included; never capture a real account.
The usual image artifacts remain the primary archive.
"""
import base64
from pathlib import Path

root = Path('app/build/ui-previews')
for name in ['home-tv.jpg', 'home-phone.jpg', 'home-short.jpg', 'ui-short-1.png', 'ui-short-2.png', 'ui-short-3.png', 'ui-short-4.png', 'ui-tv-1.png', 'ui-tv-2.png', 'ui-tv-3.png', 'ui-tv-4.png',
             'ui-phone-2.png', 'ui-phone-3.png', 'ui-phone-4.png']:
    files = list(root.rglob(name))
    if len(files) != 1 or files[0].stat().st_size > 300_000:
        print('UI_REVIEW_UNAVAILABLE', name)
        continue
    payload = base64.b64encode(files[0].read_bytes()).decode('ascii')
    print('UI_REVIEW_BEGIN', name)
    for offset in range(0, len(payload), 4096):
        print('UI_REVIEW_CHUNK', payload[offset:offset + 4096])
    print('UI_REVIEW_END', name)
