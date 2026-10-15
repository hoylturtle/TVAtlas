import contextlib
import io
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch
import build


class PlaylistHealthTest(unittest.TestCase):
    def test_rewrite_repairs_duration_comma_and_preserves_quoted_commas(self):
        malformed = '#EXTINF:-1,tvg-id="河北卫视" tvg-logo="https://fixture.test/logo?size=1,2" group-title="旧分类",河北卫视'.encode()
        result = build.rewrite_extinf(malformed, '河北卫视', '中国大陆 · 卫视')
        metadata, title = build.split_extinf(result)
        self.assertTrue(result.startswith(b'#EXTINF:-1 tvg-id='))
        self.assertEqual('河北卫视'.encode(), title)
        self.assertIn(b'https://fixture.test/logo?size=1,2', metadata)
        self.assertIn('group-title="中国大陆 · 卫视"'.encode(), metadata)
        valid = b'#EXTINF:0 group-title="News, World",News, English'
        self.assertEqual(b'News, English', build.get_name(valid))
        self.assertEqual(b'News, English', build.split_extinf(build.rewrite_extinf(valid, 'News, English', 'News'))[1])

    def test_validation_rejects_unrepaired_metadata_as_channel_title(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'list.m3u'
            path.write_text('#EXTM3U\n#EXTINF:-1,CCTV-1 综合\nhttps://fixture.test/cctv\n'
                '#EXTINF:-1,tvg-id="河北卫视" group-title="中国大陆",河北卫视\nhttps://fixture.test/hebei\n', encoding='utf-8')
            with patch.object(build, 'OUTPUT', path), patch.object(build, 'ORANGE_OUTPUT', path), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit):
                    build.validate()

    def test_validate_allows_cctv_backups_but_rejects_missing_or_excessive(self):
        with tempfile.TemporaryDirectory() as directory:
            standard = Path(directory) / 'standard.m3u'
            orange = Path(directory) / 'orange.m3u'
            with patch.object(build, 'OUTPUT', standard), patch.object(build, 'ORANGE_OUTPUT', orange):
                for count in (1, 2, 4, 0, 5):
                    text = '#EXTM3U\n' + ''.join(
                        f'#EXTINF:-1,CCTV-1 综合\nhttps://fixture.test/{n}\n' for n in range(count))
                    if count == 0:
                        text += '#EXTINF:-1,其他频道\nhttps://fixture.test/other\n'
                    standard.write_text(text, encoding='utf-8')
                    orange.write_text(text, encoding='utf-8')
                    with self.subTest(count=count), contextlib.redirect_stdout(io.StringIO()):
                        if count in (0, 5):
                            with self.assertRaises(SystemExit):
                                build.validate()
                        else:
                            build.validate()

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
