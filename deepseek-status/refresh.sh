#!/bin/sh
# DeepSeek 状态墨水屏 —— Linux Shell 控制端
# 读取同目录 .dstoken（DeepSeek API Key），默认每 60 秒刷新一次。
# 用法： ./refresh.sh [--once] [--interval 60] [--mock] [--preview] [--transport ble|none]
set -e
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$DIR"

PY=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
if [ -z "$PY" ]; then
  echo "错误：未找到 Python 3，请先安装（并 pip install pillow requests bleak）" >&2
  exit 127
fi

exec "$PY" status_display.py "$@"
