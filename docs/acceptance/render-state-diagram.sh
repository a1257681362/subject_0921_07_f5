#!/usr/bin/env bash
# 生成结算单状态机示意图 assets/state-machine.png（中文标注，给非技术同事看）。
# 状态与迁移规则以 t_settlement_transition 配置为准，本图与种子规则保持一致。
set -euo pipefail
cd "$(dirname "$0")"

FONT_DIR="$(cd "$(dirname "$0")" && pwd)/fonts"
FONT_REG="$FONT_DIR/NotoSansSC-Regular.ttf"
FONT_BOLD="$FONT_DIR/NotoSansSC-Bold.ttf"
if [ ! -f "$FONT_REG" ] || [ ! -f "$FONT_BOLD" ]; then
  mkdir -p "$FONT_DIR"
  curl -sfL -o "$FONT_REG" "https://fonts.gstatic.com/s/notosanssc/v40/k3kCo84MPvpLmixcA63oeAL7Iqp5IZJF9bmaG9_FnYw.ttf"
  curl -sfL -o "$FONT_BOLD" "https://fonts.gstatic.com/s/notosanssc/v40/k3kCo84MPvpLmixcA63oeAL7Iqp5IZJF9bmaGzjCnYw.ttf"
fi

OUT=assets/state-machine.png
mkdir -p assets

# 画布 1240x400。主流程横向：DRAFT→CONFIRMED→APPROVED→PAID；CANCELLED 在下方。
# 先画状态框与文字，再统一画连接线（fill none），最后画箭头（实心），避免路径被填充。
convert -size 1240x400 xc:white \
  -stroke '#E0A030' -strokewidth 3 -fill '#FFF4E5' -draw "roundrectangle 60,80 240,150 12,12" \
  -stroke '#3B76E1' -fill '#E8F0FE' -draw "roundrectangle 330,80 510,150 12,12" \
  -stroke '#2E9E5B' -fill '#E6F4EA' -draw "roundrectangle 600,80 780,150 12,12" \
  -stroke '#1B7A3D' -fill '#D9EAD3' -draw "roundrectangle 870,80 1050,150 12,12" \
  -stroke '#D93025' -fill '#FCE8E6' -draw "roundrectangle 330,280 510,350 12,12" \
  \
  -stroke none -fill '#222222' -font "$FONT_BOLD" -pointsize 19 \
  -annotate +100+118 "DRAFT" \
  -annotate +352+118 "CONFIRMED" \
  -annotate +622+118 "APPROVED" \
  -annotate +912+118 "PAID" \
  -annotate +352+318 "CANCELLED" \
  -font "$FONT_REG" -pointsize 14 \
  -annotate +112+140 "草稿" \
  -annotate +382+140 "已确认" \
  -annotate +652+140 "已审批" \
  -annotate +922+140 "已付款·终态" \
  -annotate +372+340 "已作废·终态" \
  \
  -stroke '#555555' -strokewidth 2 -fill none \
  -draw "line 240,115 322,115" \
  -draw "line 510,115 592,115" \
  -draw "line 780,115 862,115" \
  -draw "path 'M 420,150 L 420,192 L 150,192 L 150,158'" \
  -draw "path 'M 690,150 L 690,214 L 420,214 L 420,158'" \
  -draw "path 'M 150,150 L 150,315 L 316,315'" \
  -draw "line 420,150 420,272" \
  \
  -stroke none -fill '#555555' \
  -draw "polygon 322,110 322,120 334,115" \
  -draw "polygon 592,110 592,120 604,115" \
  -draw "polygon 862,110 862,120 874,115" \
  -draw "polygon 144,158 156,158 150,146" \
  -draw "polygon 414,158 426,158 420,146" \
  -draw "polygon 316,309 316,321 328,315" \
  -draw "polygon 414,272 426,272 420,284" \
  \
  -fill '#333333' -font "$FONT_BOLD" -pointsize 15 \
  -annotate +262+108 "确认" \
  -annotate +528+108 "审批通过" \
  -annotate +802+108 "付款" \
  -annotate +252+186 "退回草稿" \
  -annotate +522+208 "审批退回" \
  -annotate +164+240 "作废" \
  -annotate +432+240 "作废" \
  \
  "$OUT"

echo "已生成 $OUT"
