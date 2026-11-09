#!/usr/bin/env python3
"""给 TVQuizLock 创建版本标签，触发 GitHub Actions 自动构建并发布正式 APK。

用法:
    python tools/publish_update.py

前置条件:
  - 当前分支必须是 commercial-dev，且工作区干净；
  - app/build.gradle.kts 的 versionName/versionCode 与 CHANGELOG.md 一致；
  - docs/ASSET-LICENSE-REVIEW.md 中 RELEASE_APPROVED 必须为 YES；
  - GitHub 仓库已配置固定签名密钥的 Actions Secrets；
  - 本机 git 已配置可推送到 origin 的权限。

脚本只推送版本标签，不在本机上传 APK。标签推送后，release-apk.yml 会执行测试、
使用固定签名密钥构建 APK，并创建 GitHub Release。正式发布前会再次检查授权状态。
"""

import re
import subprocess
import sys
from pathlib import Path

REPO = "cxy12193890/GrowQuizLauncher-CN"
ROOT = Path(__file__).resolve().parent.parent


def output(cmd: list[str]) -> str:
    result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"命令失败 (exit {result.returncode}): {' '.join(cmd)}\n{result.stderr}")
    return result.stdout.strip()


def run(cmd: list[str]) -> None:
    print("+", " ".join(cmd))
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        sys.exit(f"命令失败 (exit {result.returncode}): {' '.join(cmd)}")


def main() -> None:
    if len(sys.argv) != 1:
        sys.exit(__doc__)

    review = ROOT / "docs" / "ASSET-LICENSE-REVIEW.md"
    if not review.is_file() or not re.search(r"^RELEASE_APPROVED: YES$", review.read_text(encoding="utf-8"), re.M):
        sys.exit("发行已阻止：题库或素材授权尚未全部核实。先完成 docs/ASSET-LICENSE-REVIEW.md。")

    branch = output(["git", "branch", "--show-current"])
    if branch != "commercial-dev":
        sys.exit(f"请切换到 commercial-dev 分支后再发布，当前分支：{branch}")

    if output(["git", "status", "--porcelain"]):
        sys.exit("工作区存在未提交改动。请先提交并推送所有改动。")

    gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    if not version:
        sys.exit("无法从 app/build.gradle.kts 读取 versionName")
    version_name = version.group(1)

    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8").replace("\r", "")
    if f"## {version_name}\n" not in changelog:
        sys.exit(f"CHANGELOG.md 中没有 \"## {version_name}\" 小节")

    tag = f"v{version_name}"
    if output(["git", "tag", "--list", tag]):
        sys.exit(f"标签 {tag} 已存在；请先提升 versionName/versionCode。")

    run(["git", "push", "origin", "HEAD:commercial-dev"])
    run(["git", "tag", "-a", tag, "-m", f"TVQuizLock {version_name}"])
    run(["git", "push", "origin", tag])
    print(f"已推送 {tag}。GitHub Actions 将自动构建并发布：https://github.com/{REPO}/actions")


if __name__ == "__main__":
    main()
