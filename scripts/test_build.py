import contextlib
import io
import unittest
from unittest.mock import patch
import build


class PlaylistHealthTest(unittest.TestCase):
    def entry(self, url, priority=1):
        return {"url": url.encode(), "source_id": "fixture", "priority": priority,
                "extinf": b"#EXTINF:-1,fixture", "extras": []}

    def test_json_and_html_are_not_media_even_when_large(self):
        for data in (b'{"error":"expired"}' * 100, b'<!doctype html>' * 100, b'random text' * 100):
            with self.subTest(data=data[:20]), patch.object(build, 'curl_bytes', return_value=(0, data)):
                self.assertFalse(build.check_stream(self.entry('https://fixture.test/live'))[0])
        self.assertFalse(build.is_media_sample(b'G' + b'x' * 1000))
        self.assertTrue(build.is_media_sample((b'G' + b'\0' * 187) * 4))
        self.assertTrue(build.is_media_sample(b'\0\0\0\x18ftyp' + b'\0' * 200))

    def test_invalid_response_is_not_reintroduced_as_geo_fallback(self):
        bad = self.entry('https://fixture.test/bad')
        geo = self.entry('https://fixture.test/geo', 2)
        result = {"channel": {"id": "fixture", "name": "fixture", "region": "HK"}, "candidates": [bad, geo]}
        with patch.object(build, 'check_stream', side_effect=lambda e: (False, 1, 'html' if e == bad else 'timeout')):
            with contextlib.redirect_stdout(io.StringIO()):
                build.probe_candidates([result])
        self.assertEqual([geo], result['ranked'])
        self.assertEqual(geo, result['selected'])
        self.assertEqual('fallback-unverified', result['health'])

    def test_audio_incompatible_source_cannot_be_eligible(self):
        entry = self.entry('http://r.jdshipin.com/GeWKr')
        self.assertFalse(build.eligible_fallback(entry, {entry['url']: (False, 0, 'player-audio-incompatible')}))

    def test_standard_hong_kong_playlist_keeps_bounded_alternate_sources(self):
        entries = [self.entry(f'https://fixture.test/{n}') for n in range(5)]
        result = {"channel": {"id": "fixture", "name": "fixture", "region": "HK", "group": "香港 · 综合"},
                  "selected": entries[0], "ranked": entries}
        output, logical, _ = build.build_playlist([result])
        self.assertEqual(1, logical)
        self.assertEqual(3, sum(line.startswith(b'https://') for line in output))


if __name__ == '__main__':
    unittest.main()
