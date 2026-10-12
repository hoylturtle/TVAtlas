"""Offline validation of draft/upload/publish ordering; never contacts GitHub."""
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse

class PublishingTest(unittest.TestCase):
    def execute(self, bad_certificate=False, published=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            dist = root / "dist"
            dist.mkdir()
            script = root / "scripts/publish_release.py"
            script.write_text(Path(__file__).with_name("publish_release.py").read_text())
            certificate = "a" * 64
            commit = "b" * 40
            info = dict(versionName="0.1.13", versionCode=14, releaseTag="v0.1.13-beta.1",
                minSdk=23, notes="test", coreVersion="v1.19.32", signingCertificateSha256=certificate)
            (root / "mihomo-lock.json").write_text(json.dumps(dict(version="v1.19.32")))
            (root / "release-info.json").write_text(json.dumps(info))
            apk = dist / "TVAtlas-Player-v0.1.13-release.apk"
            apk.write_bytes(b"fixture package")
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            (dist / "SHA256SUMS.txt").write_text(f"{digest}  {apk.name}\n")
            (dist / "build-info.txt").write_text("fixed release signature\nCommit: " + commit +
                "\nSigner #1 certificate SHA-256 digest: " + ("c" * 64 if bad_certificate else certificate))
            uploads = []
            operations = []
            def open_request(request, timeout):
                parsed = urllib.parse.urlparse(request.full_url)
                self.assertIn(parsed.hostname, ["api.github.com", "uploads.github.com"])
                method = request.get_method()
                operations.append(method)
                if method == "GET":
                    if published:
                        result = dict(draft=False, target_commitish=commit,
                            assets=[dict(name=name) for name in [apk.name, "SHA256SUMS.txt", "build-info.txt", "release-info.json"]],
                            html_url="https://github.com/hoylturtle/TVAtlas/releases/tag/v0.1.13-beta.1")
                        return io.BytesIO(json.dumps(result).encode())
                    raise urllib.error.HTTPError(request.full_url, 404, "Not Found", {}, None)
                if parsed.hostname == "uploads.github.com":
                    uploads.append(urllib.parse.parse_qs(parsed.query)["name"][0])
                    result = {"id": len(uploads)}
                elif method == "POST":
                    payload = json.loads(request.data)
                    self.assertTrue(payload["draft"])
                    self.assertTrue(payload["prerelease"])
                    result = dict(id=1, assets=[], target_commitish=commit)
                else:
                    self.assertEqual(method, "PATCH")
                    self.assertEqual(4, len(uploads))
                    self.assertFalse(json.loads(request.data)["draft"])
                    result = {"html_url": "https://github.com/hoylturtle/TVAtlas/releases/tag/v0.1.13-beta.1"}
                return io.BytesIO(json.dumps(result).encode())
            env = dict(GITHUB_REF="refs/heads/main", GITHUB_REPOSITORY="hoylturtle/TVAtlas",
                GITHUB_SHA=commit, GITHUB_TOKEN="offline-fixture")
            old_manifest = dict(info, commit=commit, apkSha256="c" * 64, apkSize=100)
            with patch.dict(os.environ, env), patch("urllib.request.build_opener") as factory, \
                    patch("urllib.request.urlopen", return_value=io.BytesIO(json.dumps(old_manifest).encode())):
                factory.return_value.open.side_effect = open_request
                if bad_certificate:
                    with self.assertRaises(AssertionError):
                        runpy.run_path(str(script), run_name="__main__")
                    factory.assert_not_called()
                else:
                    runpy.run_path(str(script), run_name="__main__")
                    if published:
                        self.assertEqual(["GET"], operations)
                        self.assertEqual([], uploads)
                    else:
                        self.assertEqual("PATCH", operations[-1])
                        self.assertIn(apk.name, uploads)
                    manifest = json.loads((dist / "release-info.json").read_text())
                    self.assertEqual(digest, manifest["apkSha256"])
                    self.assertEqual(commit, manifest["commit"])
    def test_publish_after_all_verified_assets_uploaded(self):
        self.execute()
    def test_unverified_signer_never_starts_publication(self):
        self.execute(bad_certificate=True)
    def test_rerun_preserves_existing_public_apk_even_when_rebuild_hash_changes(self):
        self.execute(published=True)

if __name__ == "__main__":
    unittest.main()
