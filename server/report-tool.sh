#!/bin/bash
# GrowQuiz 报告处理辅助：区分未处理与已处理。
# 用法：
#   report-tool pending              列出所有未处理的报告（设备目录下、非 processed/、不含 latest/）
#   report-tool done <file...>       归档指定报告到同目录 processed/ 子目录
# 处理侧约定：分析完一份报告（无论有无新问题），就 report-tool done <file>，
# 下次只看 pending，不会重复读已处理的内容。latest/ 只做速查，不参与处理流程。
set -euo pipefail
BASE=/root/growquiz-reports

case "${1:-}" in
  pending)
    find "$BASE" -mindepth 2 -maxdepth 2 -name '*.json' \
      -not -path "$BASE/latest/*" | sort
    ;;
  done)
    shift
    [ $# -ge 1 ] || { echo "usage: report-tool done <file...>" >&2; exit 1; }
    for f in "$@"; do
      case "$f" in
        "$BASE"/*/processed/*) continue ;; # 已归档的跳过
        "$BASE"/*/*.json) ;;
        *) echo "skip (not a report): $f" >&2; continue ;;
      esac
      d=$(dirname "$f")
      mkdir -p "$d/processed"
      mv "$f" "$d/processed/"
      echo "archived: $d/processed/$(basename "$f")"
    done
    ;;
  *)
    echo "usage: report-tool pending | report-tool done <file...>" >&2
    exit 1
    ;;
esac
