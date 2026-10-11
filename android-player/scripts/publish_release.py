"""Publish a verified CI package atomically as a public GitHub testing release."""
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
info = json.loads((root / "release-info.json").read_text())
tag = info["releaseTag"]
assert re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+-beta\.[0-9]+", tag)
assert tag.split("-beta.")[0] == "v" + info["versionName"]
assert os.environ["GITHUB_REF"] == "refs/heads/main"
repo = os.environ["GITHUB_REPOSITORY"]
assert repo == "hoylturtle/TVAtlas"
commit = os.environ["GITHUB_SHA"]
assert re.fullmatch(r"[a-f0-9]{40}", commit)
apk = dist / f"TVAtlas-Player-v{info['versionName']}-release.apk"
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert (dist / "SHA256SUMS.txt").read_text().strip() == f"{digest}  {apk.name}"
build = (dist / "build-info.txt").read_text()
assert "fixed release signature" in build and "application-debuggable" not in build
assert f"Commit: {commit}" in build
assert f"Signer #1 certificate SHA-256 digest: {info['signingCertificateSha256']}" in build
manifest = dict(info, commit=commit, apkSha256=digest, apkSize=apk.stat().st_size)
(dist / "release-info.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the workflow token to an asset redirect.

opener = urllib.request.build_opener(NoRedirect())
token = os.environ["GITHUB_TOKEN"]
api = f"https://api.github.com/repos/{repo}"
def request(method, url, data=None, content_type="application/json", optional=False):
    body = json.dumps(data).encode() if isinstance(data, dict) else data
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
        "Content-Type": content_type, "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with opener.open(req, timeout=60) as response:
            payload = response.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        if optional and error.code == 404:
            return None
        raise RuntimeError(f"GitHub release operation failed: HTTP {error.code}") from None

release = request("GET", api + "/releases/tags/" + tag, optional=True)
files = sorted(path for path in dist.iterdir() if path.is_file())
if release and not release["draft"]:
    # A rerun never mutates an already-published version.
    assert release["target_commitish"] == commit, "Existing release points to another commit"
    assert {p.name for p in files}.issubset({a["name"] for a in release["assets"]})
    manifest_url = f"https://github.com/{repo}/releases/download/{tag}/release-info.json"
    with urllib.request.urlopen(manifest_url, timeout=30) as response:
        existing = json.loads(response.read(1024 * 1024))
    assert existing == manifest, "Published version differs; increment version before publishing"
    print("Existing public testing release verified:", release["html_url"])
else:
    if release is None:
        release = request("POST", api + "/releases", {
            "tag_name": tag, "target_commitish": commit,
            "name": f"TVAtlas {info['versionName']} 测试版", "draft": True, "prerelease": True,
            "body": info["notes"] + "\n\n直接下载 APK 安装，无需 GitHub 登录或解压。固定签名版可覆盖更新并保留配置。\n"
                "旧临时 debug 签名版本需要一次迁移安装。翡翠台官方线路仍为实验功能，受官方地区及 DRM 授权限制。\n\n"
                f"APK SHA-256: `{digest}`\n\n签名、单元测试、Android 模拟器测试及覆盖安装检查通过后发布。",
        })
    else:
        assert release["target_commitish"] == commit, "Draft release points to another commit"
    for path in files:
        for asset in release.get("assets", []):
            if asset["name"] == path.name:
                request("DELETE", api + f"/releases/assets/{asset['id']}")
        url = f"https://uploads.github.com/repos/{repo}/releases/{release['id']}/assets?name=" + urllib.parse.quote(path.name)
        kind = "application/vnd.android.package-archive" if path.suffix == ".apk" else "application/octet-stream"
        request("POST", url, path.read_bytes(), kind)
        print("Uploaded release asset:", path.name)
    published = request("PATCH", api + f"/releases/{release['id']}", {"draft": False, "prerelease": True})
    print("Published public testing release:", published["html_url"])
