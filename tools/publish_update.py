#!/usr/bin/env python3
"""发布一个新版本：在 GitHub 仓库创建 release（附 APK 与发版说明）。

用法:
    python tools/publish_update.py path/to/TVQuizLock-vX.Y.Z.apk

流程:
  1. 校验文件名 TVQuizLock-vX.Y.Z.apk
  2. 从 CHANGELOG.md 提取该版本小节作为发版说明
  3. gh release create 上传 APK 资产

发布后无需再手动传服务器：mylinux 上的同步脚本（growquiz-sync.timer，每 3 小时）会自动
把 release 的 APK 与发版说明同步到 https://www.rengouzi.top:8443/growquiz/，TV 端即可
检测到新版本并下载安装。

发版前记得:
  - app/build.gradle.kts 的 versionName/versionCode 与文件名一致且高于已装版本；
  - 本次改动已提交并推送到 commercial-dev（release 打在远端最新提交上）；
  - 本机 gh 已登录（gh auth status）。
"""

import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = "cxy12193890/GrowQuizLauncher-CN"
NAME_RE = re.compile(r"^TVQuizLock-v(\d+\.\d+(?:\.\d+)?)\.apk$")


def run(cmd: list[str]) -> None:
    print("+", " ".join(cmd))
    result = subprocess.run(cmd)
    if result.returncode != 0:
        sys.exit(f"命令失败 (exit {result.returncode}): {' '.join(cmd)}")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = Path(sys.argv[1])
    if not apk.is_file():
        sys.exit(f"找不到文件: {apk}")
    m = NAME_RE.match(apk.name)
    if not m:
        sys.exit(f"文件名需形如 GrowQuiz-v1.2.0.apk，当前: {apk.name}")
    version = m.group(1)

    changelog = (Path(__file__).resolve().parent.parent / "CHANGELOG.md").read_text(encoding="utf-8").replace("\r", "")
    parts = changelog.split(f"## {version}\n", 1)
    if len(parts) < 2:
        sys.exit(f"CHANGELOG.md 中没有 \"## {version}\" 小节")
    notes = parts[1].split("\n## ", 1)[0].strip()
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
        f.write(notes)
        notes_path = f.name

    run(["gh", "release", "create", f"v{version}", str(apk),
         "--repo", REPO, "--title", f"TVQuizLock v{version}", "--notes-file", notes_path])
    print(f"已发布 v{version}：https://github.com/{REPO}/releases/tag/v{version}")
    print("mylinux 每 3 小时自动同步到更新目录，无需手动上传。")


if __name__ == "__main__":
    main()
