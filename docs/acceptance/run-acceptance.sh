#!/usr/bin/env bash
# 结算流程验收走查：启动服务（独立验收库）→ 逐步调用真实接口 → 留档文本 → 渲染截图。
# 全程可重复执行：每次先清空验收库，结果写入 docs/acceptance/output 与 docs/acceptance/assets。
# 用法: docs/acceptance/run-acceptance.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

PORT=18080
BASE="http://localhost:$PORT"
DB=./data/evops-acceptance
OUT=docs/acceptance/output
ASSETS=docs/acceptance/assets
APP_LOG=/tmp/evops-acceptance-app.log
SLIM="python3 docs/acceptance/slim.py"

mkdir -p "$OUT" "$ASSETS"
rm -f "$DB".mv.db "$DB".trace.db

# 构建（如 jar 不存在或源码更新）
if [ ! -f target/evops-base-0.1.0-SNAPSHOT.jar ] || [ -n "$(find src -newer target/evops-base-0.1.0-SNAPSHOT.jar -name '*.java' -print -quit 2>/dev/null)" ]; then
  echo "== 构建应用 =="
  mvn -q -DskipTests package
fi

echo "== 启动应用（端口 $PORT，验收库 $DB）=="
java -jar target/evops-base-0.1.0-SNAPSHOT.jar \
  --server.port=$PORT \
  --spring.datasource.url="jdbc:h2:file:$DB;AUTO_SERVER=TRUE" \
  --reconcile.scheduling-enabled=false > "$APP_LOG" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT

for i in $(seq 1 60); do
  if curl -sf "$BASE/api/health" > /dev/null 2>&1; then break; fi
  sleep 2
done
curl -sf "$BASE/api/health" > /dev/null || { echo "应用启动失败，日志见 $APP_LOG"; exit 1; }
echo "应用已就绪"

# call <method> <path> [json]：调接口，输出原始 JSON（供取值与留档）
call() {
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -s -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -X "$method" "$BASE$path"
  fi
}

# doc <步骤文件> <说明> <展示的命令> [实际执行的命令]：记录"命令 + 瘦身后的真实返回"
doc() {
  local file="$1" comment="$2" display="$3" actual="${4:-$3}"
  {
    echo "# $comment"
    echo "\$ $display"
    eval "$actual" | $SLIM
    echo
  } >> "$file"
}

J() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== 步骤 01：准备数据（农机 + 两张完工作业单）=="
F=$OUT/step-01.txt; : > "$F"
M=$(call POST /api/machines '{"machineCode":"M-ACC-01","machineName":"雷沃谷神收割机","requestNo":"REQ-ACC-M-001"}')
MID=$(echo "$M" | J "['data']['id']")
doc "$F" "农机建档" \
  "POST /api/machines  {machineCode:M-ACC-01, machineName:雷沃谷神收割机}" "echo '$M'"
O1=$(call POST /api/work-orders '{"orderNo":"WO-ACC-0001","machineId":'$MID',"operationType":"收割","plotName":"东洼子3号地","requestNo":"REQ-ACC-W-001"}')
OID1=$(echo "$O1" | J "['data']['id']")
O2=$(call POST /api/work-orders '{"orderNo":"WO-ACC-0002","machineId":'$MID',"operationType":"收割","plotName":"西坡7号地","requestNo":"REQ-ACC-W-002"}')
OID2=$(echo "$O2" | J "['data']['id']")
doc "$F" "作业单建档（两张）" \
  "POST /api/work-orders  {orderNo:WO-ACC-0001, operationType:收割, plotName:东洼子3号地}" "echo '$O1'"
call POST /api/work-orders/$OID1/track-area '{"trackArea":12.50,"requestNo":"REQ-ACC-TA-001"}' > /dev/null
call POST /api/work-orders/$OID1/transit '{"targetStatus":"IN_PROGRESS","requestNo":"REQ-ACC-TR-001"}' > /dev/null
call POST /api/work-orders/$OID1/transit '{"targetStatus":"COMPLETED","requestNo":"REQ-ACC-TR-002"}' > /dev/null
call POST /api/work-orders/$OID2/track-area '{"trackArea":8.30,"requestNo":"REQ-ACC-TA-002"}' > /dev/null
call POST /api/work-orders/$OID2/transit '{"targetStatus":"IN_PROGRESS","requestNo":"REQ-ACC-TR-003"}' > /dev/null
R=$(call POST /api/work-orders/$OID2/transit '{"targetStatus":"COMPLETED","requestNo":"REQ-ACC-TR-004"}')
doc "$F" "两张作业单登记轨迹面积并完工（WO-ACC-0001 计 12.50 亩，WO-ACC-0002 计 8.30 亩）" \
  "POST /api/work-orders/2/transit  {targetStatus:COMPLETED}  ← 第二张完工" "echo '$R'"

echo "== 步骤 02：生成结算单 =="
F=$OUT/step-02.txt; : > "$F"
G=$(call POST /api/settlements/generate '{"period":"2026-09","unitPrice":80.00,"operator":"财务-小王","requestNo":"REQ-ACC-G-001","remark":"2026年9月收割作业费"}')
SID=$(echo "$G" | J "['data']['id']")
doc "$F" "生成 2026-09 结算单：已完工未结算作业单自动汇总成单" \
  "POST /api/settlements/generate  {period:2026-09, unitPrice:80.00, operator:财务-小王}" "echo '$G'"
D=$(call GET /api/settlements/$SID)
doc "$F" "结算单详情：明细快照（单号/面积/单价/金额）已落库，无需导出二次加工" \
  "GET /api/settlements/$SID" "echo '$D'"

echo "== 步骤 03：非法迁移即拒绝（跳级 DRAFT→PAID）=="
F=$OUT/step-03.txt; : > "$F"
doc "$F" "草稿直接付款：状态机拒绝，并提示当前可去方向" \
  "call POST /api/settlements/$SID/transit '{\"targetStatus\":\"PAID\",\"version\":0,\"operator\":\"财务-小王\",\"requestNo\":\"REQ-ACC-T-901\"}'"

echo "== 步骤 04：版本不一致即拒绝 =="
F=$OUT/step-04.txt; : > "$F"
doc "$F" "携带过期版本(version=9)流转：乐观锁拒绝，提示当前版本" \
  "call POST /api/settlements/$SID/transit '{\"targetStatus\":\"CONFIRMED\",\"version\":9,\"operator\":\"财务-小王\",\"requestNo\":\"REQ-ACC-T-902\"}'"

echo "== 步骤 05：正常流转 DRAFT→CONFIRMED（带版本+操作人）=="
F=$OUT/step-05.txt; : > "$F"
doc "$F" "确认结算单：携带当前版本 0 与操作人，流转成功，版本自动 +1" \
  "call POST /api/settlements/$SID/transit '{\"targetStatus\":\"CONFIRMED\",\"version\":0,\"operator\":\"财务-小王\",\"requestNo\":\"REQ-ACC-T-001\"}'"

echo "== 步骤 06：迁移规则配置化（可查）=="
F=$OUT/step-06.txt; : > "$F"
doc "$F" "状态机迁移规则全部来自配置表，可在线查看" "call GET /api/settlements/transitions"

echo "== 步骤 07：停用规则后流转被拒（配置即时生效）=="
F=$OUT/step-07.txt; : > "$F"
RULE_ID=$(call GET /api/settlements/transitions | python3 -c "
import sys,json
for r in json.load(sys.stdin)['data']:
    if r['fromStatus']=='CONFIRMED' and r['toStatus']=='APPROVED': print(r['id'])")
doc "$F" "管理员停用 已确认→已审批 规则（带规则版本与操作人）" \
  "call POST /api/settlements/transitions/$RULE_ID/toggle '{\"enabled\":false,\"version\":0,\"operator\":\"管理员-小周\",\"requestNo\":\"REQ-ACC-R-001\"}'"
doc "$F" "规则停用后，审批流转立即被拒" \
  "call POST /api/settlements/$SID/transit '{\"targetStatus\":\"APPROVED\",\"version\":1,\"operator\":\"合作社-老李\",\"requestNo\":\"REQ-ACC-T-903\"}'"
doc "$F" "重新启用规则" \
  "call POST /api/settlements/transitions/$RULE_ID/toggle '{\"enabled\":true,\"version\":1,\"operator\":\"管理员-小周\",\"requestNo\":\"REQ-ACC-R-002\"}'"

echo "== 步骤 08：审批、付款走完主流程 =="
F=$OUT/step-08.txt; : > "$F"
doc "$F" "审批通过（CONFIRMED→APPROVED）" \
  "call POST /api/settlements/$SID/transit '{\"targetStatus\":\"APPROVED\",\"version\":1,\"operator\":\"合作社-老李\",\"requestNo\":\"REQ-ACC-T-002\"}'"
doc "$F" "付款（APPROVED→PAID）" \
  "call POST /api/settlements/$SID/transit '{\"targetStatus\":\"PAID\",\"version\":2,\"operator\":\"出纳-小赵\",\"requestNo\":\"REQ-ACC-T-003\",\"remark\":\"9月作业费已转账\"}'"

echo "== 步骤 09：终态不可再流转 =="
F=$OUT/step-09.txt; : > "$F"
doc "$F" "已付款是终态：任何继续流转都被拒绝" \
  "call POST /api/settlements/$SID/transit '{\"targetStatus\":\"CONFIRMED\",\"version\":3,\"operator\":\"财务-小王\",\"requestNo\":\"REQ-ACC-T-904\"}'"

echo "== 步骤 10：完整流转历史（每步带版本与操作人）=="
F=$OUT/step-10.txt; : > "$F"
doc "$F" "结算单详情：从生成到付款的每一步都有 状态变化、前后版本、操作人、请求号、时间" \
  "call GET /api/settlements/$SID"

echo "== 步骤 11：作废释放，可重新结算 =="
F=$OUT/step-11.txt; : > "$F"
call POST /api/work-orders '{"orderNo":"WO-ACC-0003","machineId":'$MID',"operationType":"秸秆还田","plotName":"东洼子5号地","requestNo":"REQ-ACC-W-003"}' > /dev/null
OID3=$(call GET "/api/work-orders?orderNo=WO-ACC-0003" | J "['data']['order']['id']")
call POST /api/work-orders/$OID3/track-area '{"trackArea":6.00,"requestNo":"REQ-ACC-TA-003"}' > /dev/null
call POST /api/work-orders/$OID3/transit '{"targetStatus":"IN_PROGRESS","requestNo":"REQ-ACC-TR-005"}' > /dev/null
call POST /api/work-orders/$OID3/transit '{"targetStatus":"COMPLETED","requestNo":"REQ-ACC-TR-006"}' > /dev/null
G2=$(call POST /api/settlements/generate '{"period":"2026-10","unitPrice":60.00,"operator":"财务-小王","requestNo":"REQ-ACC-G-002"}')
SID2=$(echo "$G2" | J "['data']['id']")
doc "$F" "新期间生成结算单（WO-ACC-0003 秸秆还田 6.00 亩 × 60 元）" \
  "POST /api/settlements/generate  {period:2026-10, unitPrice:60.00, operator:财务-小王}" "echo '$G2'"
doc "$F" "作废必须填原因：空原因被拒绝" \
  "call POST /api/settlements/$SID2/transit '{\"targetStatus\":\"CANCELLED\",\"version\":0,\"operator\":\"财务-小王\",\"requestNo\":\"REQ-ACC-T-905\"}'"
doc "$F" "带原因作废成功" \
  "call POST /api/settlements/$SID2/transit '{\"targetStatus\":\"CANCELLED\",\"version\":0,\"operator\":\"财务-小王\",\"requestNo\":\"REQ-ACC-T-004\",\"remark\":\"单价录错，作废重开\"}'"
doc "$F" "作废后作业单与期间释放，可重新生成（单号序号不复用，新单 -0002）" \
  "call POST /api/settlements/generate '{\"period\":\"2026-10\",\"unitPrice\":62.00,\"operator\":\"财务-小王\",\"requestNo\":\"REQ-ACC-G-003\"}'"

echo "== 渲染截图 =="
declare -A TITLES=(
  [step-01]="步骤 1 ｜ 准备数据：农机建档 + 两张作业单完工"
  [step-02]="步骤 2 ｜ 生成结算单：完工单自动汇总，金额自动算好"
  [step-03]="步骤 3 ｜ 非法迁移即拒绝：草稿不能直接付款"
  [step-04]="步骤 4 ｜ 版本不一致即拒绝：防止多人同时改乱"
  [step-05]="步骤 5 ｜ 正常流转：带版本与操作人，确认成功"
  [step-06]="步骤 6 ｜ 迁移规则配置化：7 条规则在线可查"
  [step-07]="步骤 7 ｜ 停用规则立即生效：审批被拦截，启用后恢复"
  [step-08]="步骤 8 ｜ 审批、付款走完主流程"
  [step-09]="步骤 9 ｜ 终态保护：已付款不可再变更"
  [step-10]="步骤 10 ｜ 流转历史完整：每步带版本与操作人"
  [step-11]="步骤 11 ｜ 作废释放：历史保留，可重新结算"
)
for name in step-01 step-02 step-03 step-04 step-05 step-06 step-07 step-08 step-09 step-10 step-11; do
  docs/acceptance/render-shot.sh "${TITLES[$name]}" "$OUT/$name.txt" "$ASSETS/$name.png"
done

echo "== 验收走查完成，截图在 $ASSETS =="
