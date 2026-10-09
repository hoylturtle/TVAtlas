"""Bundle pinned upstream Android executables; optionally produce corresponding source."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / "mihomo-lock.json").read_text())

def download(url, path):
    request = urllib.request.Request(url, headers={"User-Agent": "TVAtlas-build"})
    with urllib.request.urlopen(request, timeout=60) as response, path.open("wb") as output:
        shutil.copyfileobj(response, output)

with tempfile.TemporaryDirectory() as temporary:
    workspace = Path(temporary)
    if "--source" in sys.argv:
        source = workspace / "source.tar.gz"
        download(lock["source_url"], source)
        with tarfile.open(source) as archive:
            archive.extractall(workspace / "source", filter="data")
        project = next((workspace / "source").iterdir())
        subprocess.run(["go", "mod", "vendor"], cwd=project, check=True)
        (project / "TVATLAS_BUILD.txt").write_text(
            f"Unmodified Mihomo {lock['version']}; https://github.com/MetaCubeX/mihomo\n"
            "Upstream Android build scripts: .github/workflows and Makefile.\n"
            "TVAtlas uses the pinned official release executables without changes.\n"
            "Dependencies and their notices are included in vendor/.\n"
        )
        dist = root / "dist"; dist.mkdir(exist_ok=True)
        path = dist / f"Mihomo-{lock['version']}-corresponding-source.tar.gz"
        with tarfile.open(path, "w:gz") as archive:
            archive.add(project, arcname=project.name)
        shutil.copyfile(root / "app/src/main/assets/Mihomo-LICENSE.txt", dist / "Mihomo-LICENSE.txt")
        print(f"Corresponding source prepared: {path.name}")
    else:
        for asset in lock["assets"]:
            compressed = workspace / asset["name"]
            download(asset["url"], compressed)
            actual = "sha256:" + hashlib.sha256(compressed.read_bytes()).hexdigest()
            if actual != asset["digest"]:
                raise RuntimeError(f"Checksum mismatch for {asset['name']}")
            target = root / "app/src/main/jniLibs" / asset["abi"] / "libmihomo.so"
            target.parent.mkdir(parents=True, exist_ok=True)
            with gzip.open(compressed, "rb") as input, target.open("wb") as output:
                shutil.copyfileobj(input, output)
            if target.read_bytes()[:4] != b"\x7fELF":
                raise RuntimeError("Invalid core executable")
            target.chmod(0o755)
            print(f"Verified {asset['abi']} core {lock['version']}")
