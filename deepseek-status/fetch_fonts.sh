#!/bin/sh
# 下载 Noto Sans CJK SC 字体到 fonts/（字体较大，未直接放进仓库）
set -e
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
mkdir -p fonts
base="https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese"
for f in NotoSansCJKsc-Regular.otf NotoSansCJKsc-Bold.otf; do
  if [ -f "fonts/$f" ]; then echo "已存在：fonts/$f"; continue; fi
  echo "下载 $f …"
  curl -L --retry 3 -o "fonts/$f" "$base/$f"
done
echo "字体就绪：$(ls -la fonts | tail -n +2 | wc -l) 个文件"
