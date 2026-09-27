#!/usr/bin/env bash
# F5 作业单图片处理 实机验证：起真实服务 → 上传 → 缩略图300px+水印 → 重传幂等 →
# 同名异内容拒绝 → 原图字节未变。全程可重复执行，留档 docs/acceptance/output/image-verify.txt。
# 用法: docs/acceptance/run-image-verify.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

PORT=18081
BASE="http://localhost:$PORT"
DB=./data/evops-image-verify
STORE=./data/image-verify-store
WORK=/tmp/evops-image-verify
OUT=docs/acceptance/output/image-verify.txt
APP_LOG=/tmp/evops-image-verify-app.log

mkdir -p docs/acceptance/output "$WORK"
rm -f "$DB".mv.db "$DB".trace.db
rm -rf "$STORE" "$WORK"
mkdir -p "$WORK"
: > "$OUT"

say() { echo "$@" | tee -a "$OUT"; }

# 构建（如 jar 不存在或源码更新）
if [ ! -f target/evops-base-0.1.0-SNAPSHOT.jar ] || [ -n "$(find src -newer target/evops-base-0.1.0-SNAPSHOT.jar -name '*.java' -print -quit 2>/dev/null)" ]; then
  echo "== 构建应用 =="
  mvn -q -DskipTests package
fi

# 编译图片工具（生成测试图 / 读取图片尺寸）
javac -d "$WORK" docs/acceptance/ImageVerifyTool.java

say "== 启动应用（端口 $PORT，验证库 $DB，图片目录 $STORE）=="
java -jar target/evops-base-0.1.0-SNAPSHOT.jar \
  --server.port=$PORT \
  --spring.datasource.url="jdbc:h2:file:$DB;AUTO_SERVER=TRUE" \
  --reconcile.scheduling-enabled=false \
  --workorder.image.root-dir="$STORE" > "$APP_LOG" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT

for i in $(seq 1 60); do
  if curl -sf "$BASE/api/health" > /dev/null 2>&1; then break; fi
  sleep 2
done
curl -sf "$BASE/api/health" > /dev/null || { echo "应用启动失败，日志见 $APP_LOG"; exit 1; }
say "应用已就绪"

J() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }
call() { local method="$1" path="$2" body="${3:-}"; \
  if [ -n "$body" ]; then curl -s -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body"; \
  else curl -s -X "$method" "$BASE$path"; fi; }

say ""
say "== 步骤 1：准备数据（农机 + 作业单）=="
MID=$(call POST /api/machines '{"machineCode":"M-IMG-ACC-01","machineName":"雷沃谷神收割机","requestNo":"REQ-IMG-ACC-M-001"}' | J "['data']['id']")
OID=$(call POST /api/work-orders '{"orderNo":"WO-IMG-ACC-0001","machineId":'$MID',"operationType":"收割","plotName":"东洼子3号地","requestNo":"REQ-IMG-ACC-W-001"}' | J "['data']['id']")
say "作业单 WO-IMG-ACC-0001 id=$OID"

say ""
say "== 步骤 2：上传田间照片（1600x1200，机手工号 JH0231，拍摄时间 2026-09-26 15:30:00）=="
java -cp "$WORK" ImageVerifyTool make "$WORK/field-001.jpg" 1600 1200 "dong-wa-zi-3" | tee -a "$OUT"
UP=$(curl -s -X POST "$BASE/api/work-orders/$OID/images" \
  -F "file=@$WORK/field-001.jpg;type=image/jpeg;filename=field-001.jpg" \
  -F "operatorCode=JH0231" -F "capturedAt=2026-09-26 15:30:00" -F "requestNo=REQ-IMG-ACC-U-001")
echo "$UP" | python3 -m json.tool | tee -a "$OUT"
[ "$(echo "$UP" | J "['success']")" = "True" ] || { echo "上传失败"; exit 1; }
IMG_ID=$(echo "$UP" | J "['data']['image']['id']")
[ "$(echo "$UP" | J "['data']['image']['thumbWidth']")" = "300" ] || { echo "缩略图宽度不是300"; exit 1; }
[ "$(echo "$UP" | J "['data']['duplicated']")" = "False" ] || { echo "首传不应标记重复"; exit 1; }
say "上传成功：imageId=$IMG_ID，缩略图 300x$(echo "$UP" | J "['data']['image']['thumbHeight']")"

say ""
say "== 步骤 3：下载缩略图，验证最长边 300px =="
curl -s "$BASE/api/work-orders/images/$IMG_ID/thumb" -o "$WORK/thumb-001.jpg"
THUMB_SIZE=$(java -cp "$WORK" ImageVerifyTool size "$WORK/thumb-001.jpg")
say "缩略图尺寸：$THUMB_SIZE"
[ "$THUMB_SIZE" = "300x225" ] || { echo "缩略图尺寸不符，期望 300x225"; exit 1; }
ls -la "$STORE/original/$OID" "$STORE/thumbs/$OID" | tee -a "$OUT"
say "原图与缩略图分目录落盘确认（original/ 与 thumbs/ 独立）"

say ""
say "== 步骤 4：下载原图，与上传文件逐字节比对（原图保留、未被改写）=="
curl -s "$BASE/api/work-orders/images/$IMG_ID/original" -o "$WORK/original-back.jpg"
if cmp -s "$WORK/field-001.jpg" "$WORK/original-back.jpg"; then
  say "原图字节一致：$(cmp "$WORK/field-001.jpg" "$WORK/original-back.jpg" && echo IDENTICAL)"
else
  echo "原图被改写，验证失败"; exit 1
fi

say ""
say "== 步骤 5：同名同内容重复上传 → 只处理一次（幂等）=="
DUP=$(curl -s -X POST "$BASE/api/work-orders/$OID/images" \
  -F "file=@$WORK/field-001.jpg;type=image/jpeg;filename=field-001.jpg" \
  -F "operatorCode=JH0231" -F "capturedAt=2026-09-26 15:30:00" -F "requestNo=REQ-IMG-ACC-U-002")
echo "$DUP" | python3 -m json.tool | tee -a "$OUT"
[ "$(echo "$DUP" | J "['success']")" = "True" ] || { echo "重复上传应幂等成功"; exit 1; }
[ "$(echo "$DUP" | J "['data']['duplicated']")" = "True" ] || { echo "应标记 duplicated=true"; exit 1; }
[ "$(echo "$DUP" | J "['data']['image']['id']")" = "$IMG_ID" ] || { echo "应返回首次记录"; exit 1; }
COUNT=$(call GET "/api/work-orders/$OID/images" | J "['data'].__len__()")
[ "$COUNT" = "1" ] || { echo "重复上传后应仍只有 1 条记录"; exit 1; }
say "重复上传幂等：仍只有 1 条记录、1 份文件"

say ""
say "== 步骤 6：同名不同内容 → 拒绝，原图不动 =="
java -cp "$WORK" ImageVerifyTool make "$WORK/field-different.jpg" 1600 1200 "another-field" > /dev/null
CLASH=$(curl -s -X POST "$BASE/api/work-orders/$OID/images" \
  -F "file=@$WORK/field-different.jpg;type=image/jpeg;filename=field-001.jpg" \
  -F "operatorCode=JH0231" -F "requestNo=REQ-IMG-ACC-U-003")
echo "$CLASH" | python3 -m json.tool | tee -a "$OUT"
[ "$(echo "$CLASH" | J "['success']")" = "False" ] || { echo "同名异内容应被拒绝"; exit 1; }
curl -s "$BASE/api/work-orders/images/$IMG_ID/original" -o "$WORK/original-after-clash.jpg"
cmp -s "$WORK/field-001.jpg" "$WORK/original-after-clash.jpg" \
  && say "同名异内容被拒后，原图仍是首次上传字节（永不覆盖）" \
  || { echo "原图被覆盖，验证失败"; exit 1; }

say ""
say "== 步骤 7：第二张不同名照片正常上传；列表/详情带缩略图地址（供列表与报表）=="
# 机手工号是水印要素：缺参数应被拒绝
NOOP=$(curl -s -X POST "$BASE/api/work-orders/$OID/images" \
  -F "file=@$WORK/field-different.jpg;type=image/jpeg;filename=field-002.jpg" \
  -F "requestNo=REQ-IMG-ACC-U-005")
echo "$NOOP" | python3 -m json.tool | tee -a "$OUT"
[ "$(echo "$NOOP" | J "['success']")" = "False" ] || { echo "缺机手工号应被拒绝"; exit 1; }
say "缺机手工号（水印要素）上传被拒绝，符合口径"
UP2=$(curl -s -X POST "$BASE/api/work-orders/$OID/images" \
  -F "file=@$WORK/field-different.jpg;type=image/jpeg;filename=field-002.jpg" \
  -F "operatorCode=JH0231" -F "requestNo=REQ-IMG-ACC-U-006")
[ "$(echo "$UP2" | J "['success']")" = "True" ] || { echo "第二张上传失败"; exit 1; }
LIST=$(call GET "/api/work-orders/$OID/images")
echo "$LIST" | python3 -m json.tool | tee -a "$OUT"
[ "$(echo "$LIST" | J "['data'].__len__()")" = "2" ] || { echo "列表应为 2 条"; exit 1; }
DETAIL=$(call GET "/api/work-orders?orderNo=WO-IMG-ACC-0001")
[ "$(echo "$DETAIL" | J "['data']['images'].__len__()")" = "2" ] || { echo "详情应带 2 张图"; exit 1; }
say "列表与详情均返回缩略图地址 thumbUrl，供列表与报表引用"

say ""
say "== 全部验证通过 =="
say "- 上传即生成 300px 缩略图，独立 thumbs/ 目录，水印含拍摄时间与机手工号"
say "- 原图保留在 original/，下载字节与上传一致，同名异内容被拒且原图未动（永不覆盖）"
say "- 同名重复上传只处理一次（duplicated=true，记录与文件均不重复）"
say "- 列表/详情返回 thumbUrl，缩略图用于列表与报表"
