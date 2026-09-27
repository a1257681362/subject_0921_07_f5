#!/usr/bin/env bash
# 把步骤文本渲染成终端风格 PNG 截图（JDK AWT 绘制，零外部依赖）。
# 用法: render-shot.sh <标题> <输入文本文件> <输出png>
# 中文字体不存在时自动从 Google Fonts 下载到 docs/acceptance/fonts/（只需一次）。
set -euo pipefail

TITLE="$1"
INPUT="$2"
OUTPUT="$3"

HERE="$(cd "$(dirname "$0")" && pwd)"
FONT_DIR="$HERE/fonts"
FONT_REG="$FONT_DIR/NotoSansSC-Regular.ttf"
FONT_BOLD="$FONT_DIR/NotoSansSC-Bold.ttf"

if [ ! -f "$FONT_REG" ] || [ ! -f "$FONT_BOLD" ]; then
  mkdir -p "$FONT_DIR"
  echo "下载中文字体到 $FONT_DIR ..."
  curl -sfL -o "$FONT_REG" "https://fonts.gstatic.com/s/notosanssc/v40/k3kCo84MPvpLmixcA63oeAL7Iqp5IZJF9bmaG9_FnYw.ttf"
  curl -sfL -o "$FONT_BOLD" "https://fonts.gstatic.com/s/notosanssc/v40/k3kCo84MPvpLmixcA63oeAL7Iqp5IZJF9bmaGzjCnYw.ttf"
fi

# 渲染器只需编译一次
if [ ! -f "$HERE/ShotRenderer.class" ] || [ "$HERE/ShotRenderer.java" -nt "$HERE/ShotRenderer.class" ]; then
  (cd "$HERE" && javac -encoding UTF-8 ShotRenderer.java)
fi

java -Djava.awt.headless=true -cp "$HERE" ShotRenderer "$TITLE" "$INPUT" "$OUTPUT" "$FONT_REG" "$FONT_BOLD"
