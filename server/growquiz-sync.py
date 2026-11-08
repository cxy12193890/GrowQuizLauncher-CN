#!/usr/bin/env python3
"""GrowQuiz 更新同步：把 GitHub 仓库最新 release 的 APK 与发版说明拉到 nginx 更新目录。

源:   https://github.com/1zzzzzlll/GrowQuizLauncher 的 latest release（公开仓库，无需令牌）
目标: /root/nginx/web/growquiz/（Caddy :8443 -> nginx :8088 静态站点）
产出: GrowQuiz-vX.Y.Z.apk、latest.json（version/apk/notes）、release_notes.md

由 growquiz-sync.timer 每 3 小时触发一次；也可手动: systemctl start growquiz-sync
"""

import json
import sys
import urllib.request
from pathlib import Path

REPO = "1zzzzzlll/GrowQuizLauncher"
DEST = Path("/root/nginx/web/growquiz")
API = f"https://api.github.com/repos/{REPO}/releases/latest"


def fetch(url: str, accept: str, dest=None) -> bytes:
    req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "growquiz-sync"})
    with urllib.request.urlopen(req, timeout=300) as r:
        if dest is None:
            return r.read()
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                return b""
            dest.write(chunk)


def main() -> None:
    rel = json.loads(fetch(API, "application/vnd.github+json"))
    version = rel["tag_name"].lstrip("v")
    asset = next((a for a in rel["assets"] if a["name"].endswith(".apk")), None)
    if asset is None:
        sys.exit(f"release {version} 没有附 APK 资产")
    DEST.mkdir(parents=True, exist_ok=True)
    latest_path = DEST / "latest.json"
    current = json.loads(latest_path.read_text(encoding="utf-8")) if latest_path.is_file() else {}
    target = DEST / asset["name"]
    # The APK is large: skip only its download. Notes and latest.json are rewritten every run, so a
    # release whose text changed without a new version still propagates.
    if not (current.get("version") == version and target.is_file() and target.stat().st_size == asset["size"]):
        tmp = DEST / (asset["name"] + ".part")
        with open(tmp, "wb") as f:
            fetch(asset["url"], "application/octet-stream", dest=f)
        if tmp.stat().st_size != asset["size"]:
            tmp.unlink()
            sys.exit(f"下载不完整: {asset['name']}")
        tmp.rename(target)
        print(f"已下载 {asset['name']}")
    notes = rel.get("body") or ""
    (DEST / "release_notes.md").write_text(notes, encoding="utf-8")
    latest_path.write_text(
        json.dumps({"version": version, "apk": asset["name"], "notes": notes}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    print(f"已同步 {version}: {asset['name']}")


if __name__ == "__main__":
    main()
