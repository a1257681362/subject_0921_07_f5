# EVOPS 基础工作区

合作社作业管理系统骨架（F1：农机底账 + 作业单主链路；F2：卫星轨迹账每日勾稽/核销）。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf、Quartz。

启动：

1. `mvn -q -DskipTests compile` 构建。
2. `mvn spring-boot:run` 启动；Spring Boot 自动执行 `src/main/resources/schema.sql`（对旧库幂等，`CREATE TABLE IF NOT EXISTS` + 版本登记 `MERGE`）。
3. H2 数据文件默认写入工作区 `data/evops`。测试使用独立内存库（`test` profile）。
4. `mvn test` 运行全部测试。

> 交付副本 `../../docs/schema/evops.sql` 应与 `src/main/resources/schema.sql` 保持一致；当前容器对根目录 `/docs` 无写权限，部署时由有权限的环节同步。

## 核心约定

- **统一返回结构**：`{ success, message, data }`；业务/参数异常同样 HTTP 200 + `success=false`。
- **请求号**：所有新增/变更请求必须携带 `requestNo`，先登记 `t_request_record`（唯一约束），与业务写入同一事务；重放/并发重复提交只接受第一笔。
- **版本**：农机、作业单带 `version` 乐观锁，变更须携带最新版本，落库自动递增。
- **不物理删除**：农机停用（DISABLED）、作业单红冲（REVERSED）均只改状态、保留历史。
- **面积口径**：`trackArea` 轨迹核算面积是权威依据，完工必须先有轨迹面积；`manualArea` 人工上报仅留痕参考。
- **红冲**：原单置 `REVERSED`，另开反向单 `单号-R`（面积取反、`reversalFlag=1`、`originOrderId` 指向原单）。
- **事务边界**：建档/流转/面积/红冲均为单事务，台账、主表、状态日志一起提交或整体回退。
- 同一作业单号并发两笔建档，由 `uk_work_order_no` 唯一约束兜底，只成功一条。

## 接口

农机 `/api/machines`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/machines` | 农机建档（machineCode、machineName、requestNo） |
| POST | `/api/machines/{id}` | 变更信息（version 乐观锁、requestNo） |
| POST | `/api/machines/{id}/disable` | 停用（requestNo） |
| GET | `/api/machines/{id}` | 查询 |
| GET | `/api/machines` | 列表 |

作业单 `/api/work-orders`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/work-orders` | 建档（orderNo、machineId、operationType、requestNo） |
| GET | `/api/work-orders?orderNo=xxx` | 按作业单号检索（作业单 + 状态流转日志） |
| POST | `/api/work-orders/{id}/transit` | 状态流转（CREATED→IN_PROGRESS→COMPLETED；CREATED/IN_PROGRESS→CANCELLED） |
| POST | `/api/work-orders/{id}/track-area` | 登记轨迹核算面积（权威） |
| POST | `/api/work-orders/{id}/manual-area` | 人工上报面积（仅参考） |
| POST | `/api/work-orders/{id}/reverse` | 红冲，另开反向单 |
| POST | `/api/work-orders/{id}/photos` | 上传现场照片（multipart：file、shotTime、operatorCode、requestNo），自动生成 300px 水印缩略图 |
| GET | `/api/work-orders/{id}/photos` | 照片列表（含缩略图地址，供列表与报表引用） |
| GET | `/api/work-orders/photos/{photoId}/thumb` | 缩略图（300px，水印：拍摄时间 + 机手工号） |
| GET | `/api/work-orders/photos/{photoId}/original` | 原图下载（字节级保留，永不覆盖） |

作业单文件交换（CSV）：`GET /api/work-orders/export` 导出（可按 status 过滤）、`GET /api/work-orders/import-template` 下载导入模板、`POST /api/work-orders/import` 批量导入（multipart，逐行独立事务，失败行带行号；单号已存在自动跳过，重复导入幂等）。格式与转义规则见 [docs/work-order-csv-exchange.md](docs/work-order-csv-exchange.md)，值班处置见 [docs/work-order-csv-runbook.md](docs/work-order-csv-runbook.md)。

结算单 `/api/settlements`（F4，详见下文）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/settlements/generate` | 生成结算单：已完工未结算作业单按期间一次汇总成单（period、unitPrice、operator、requestNo） |
| POST | `/api/settlements/{id}/transit` | 状态流转统一入口（targetStatus、version、operator、requestNo；非法迁移即拒绝） |
| GET | `/api/settlements/{id}` | 详情：单头 + 明细快照 + 完整流转历史 |
| GET | `/api/settlements?status=&period=` | 列表 |
| GET | `/api/settlements/transitions` | 状态机迁移规则配置（可查） |
| POST | `/api/settlements/transitions/{id}/toggle` | 迁移规则启停（enabled、version、operator、requestNo） |

表结构（4 张业务表）：`t_machine`、`t_work_order`、`t_work_order_status_log`、`t_request_record`。

## F2 卫星轨迹账每日勾稽

与卫星轨迹核算平台**天天对账、一笔对一笔**：Quartz 每日 02:10 自动勾稽前一日（错过则启动补跑），也可手工触发；每日只允许一个批次（唯一约束 + 请求号台账双重防重入）。

### 勾稽口径（新旧并跑）

- **旧引擎（LEGACY，切换前基线）**：红冲原单/反向单整笔剔除，金额分毫必较，无容差。
- **新引擎（NEW）**：红冲原单 + 反向单都进勾稽（净额口径，双方逐笔配对，红冲单单号为 `原单号-R`）；`|双方面积差| <= 容差` 即判一致。
- **并跑模式（PARALLEL，默认）**：同快照双跑，批次头逐笔比对两种口径，给出 `MATCH/MISMATCH` 与分歧明细。至少连续两天 `MATCH` 且无未核销差异，`/api/recon/cutover-check` 才放行切换。
- 差异三类：`LOCAL_ONLY`（本地有对方无）、`REMOTE_ONLY`（对方有本地无）、`AMOUNT_DIFF`（超容差）；差异明细**带双方原始字段**（单号/面积/时间/状态/红冲标记）。
- **容差可配置**：`reconcile.tolerance`（默认 0.05 亩），也可通过接口在线调整（`t_recon_config` 落库，重启不丢），每批记录当时容差。

### 核销（手工调整）与清零

- 差异走核销流程：申请（带调整类型、原因、双方快照）→ 审批/驳回；审批通过与差异**同一事务**即时清零（明细置 `WRITTEN_OFF`、批次未平计数归零），驳回只留痕、差异保持未平。
- 核销是勾稽挂账层面的处理，**不回写作业单权威轨迹面积**。
- 已核销差异若在后续批次快照中仍存在（对方尚未补/冲账），**自动结转**到新批次并清零、挂原核销单，不会当新差异重复报。
- 所有核销动作走请求号台账，核销单带乐观锁。

### 勾稽接口 `/api/recon`、卫星账单 `/api/satellite-bills`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/satellite-bills` | 卫星账单录入（对方账，billNo、orderNo、trackArea、requestNo） |
| POST | `/api/satellite-bills/{id}/reverse` | 卫星账单红冲，另开反向账单（号-R、面积取反） |
| POST | `/api/recon/run` | 手工触发勾稽（不传日期默认前一日） |
| GET | `/api/recon/batches?limit=` | 批次列表（含双引擎汇总与比对结论） |
| GET | `/api/recon/batches/{id}` | 批次详情：新/旧引擎两份逐笔差异清单 |
| GET | `/api/recon/open-diffs?reconcileDate=` | 未核销差异清单（带双方明细） |
| GET | `/api/recon/cutover-check` | 切换闸门：连续 N 批双引擎一致且差异清零才 ready |
| GET/POST | `/api/recon/config`、`/api/recon/config/tolerance`、`/api/recon/config/engine-mode` | 查看/调整容差与引擎模式（PARALLEL/NEW/LEGACY） |
| POST | `/api/recon/writeoffs` | 核销申请 |
| POST | `/api/recon/writeoffs/{id}/approve` | 审批通过（差异即时清零） |
| POST | `/api/recon/writeoffs/{id}/reject` | 驳回（差异保持未平） |

切换操作流：`PARALLEL` 并跑 ≥ 2 天 → 每日看批次 `engineCompareResult=MATCH`、差异清零 → `cutover-check` 返回 `ready=true` → `engine-mode=NEW` 切换；异常时可改回 `LEGACY` 回退。

新增表：`t_satellite_bill`、`t_recon_batch`、`t_recon_detail`、`t_recon_writeoff`、`t_recon_config`。

## F4 作业费结算单

把"导出 Excel 再手工算"的结算流程收进系统：已完工、未红冲、未进入有效结算单的作业单，按期间一次生成结算单，明细快照面积/单价/金额直接落库，**不再需要二次加工**。

### 状态机（迁移规则配置化）

- 状态：`DRAFT` 草稿 → `CONFIRMED` 已确认 → `APPROVED` 已审批 → `PAID` 已付款（终态）；`DRAFT`/`CONFIRMED` 可作废到 `CANCELLED`（终态）；另有退回边 `CONFIRMED→DRAFT`、`APPROVED→CONFIRMED`。
- **迁移规则不落代码**：全部存 `t_settlement_transition`（7 条种子规则随 schema 初始化），`/api/settlements/transitions` 可查，`/toggle` 可在线启停，即时生效、重启不丢。
- **入口统一校验**：所有状态变更只走 `POST /api/settlements/{id}/transit` 一个入口，统一校验目标状态、版本、操作人；**非法迁移即拒绝**，报错给出当前可去方向；终态不可再流转。
- **每次变更带版本与操作人**：流转请求必须携带当前 `version`（乐观锁，不一致即拒绝）与 `operator`；每次变更落 `t_settlement_status_log`（前后状态、前后版本、操作人、请求号、时间），**流转历史完整**、只追加不修改。
- 作废必须填原因；作废即释放：期间占位与明细作业单占用解除（`active_tag` 翻转），历史保留不删，可重新生成结算单，单号序号不复用。
- 同一期间最多一张有效结算单、一张作业单最多进一张有效结算单，均由数据库唯一约束兜底。

### 防重与事务

生成/流转/规则启停都走请求号台账（`t_request_record`），与业务写入同一事务；结算单头、明细、流转日志一起提交或整体回退。

新增表：`t_settlement`、`t_settlement_item`、`t_settlement_status_log`、`t_settlement_transition`。

验收报告（含状态机图、逐步截图，可重复执行）：[docs/settlement-acceptance-report.md](docs/settlement-acceptance-report.md)。

## F5 作业单现场照片

现场照片不再走邮件，随单入系统：上传即处理，列表与报表直接用缩略图。

- **上传即处理**：`POST /api/work-orders/{id}/photos`（multipart：file、shotTime 拍摄时间、operatorCode 机手工号、requestNo）。原图按内容 SHA-256 寻址落盘 `photo.root-dir/original/<作业单ID>/`，同时生成 300px（最长边、等比、小图不放大）缩略图到**独立目录** `thumb/<作业单ID>/`。
- **水印**：只打在缩略图上（右下角半透明底条白字：`拍摄时间:yyyy-MM-dd HH:mm:ss 机手:工号`），中文字体打包在 `resources/fonts/` 内，服务器无需装字体。**原图不加任何水印**，字节级保留。
- **原图永不覆盖**：落盘一律 `CREATE_NEW`，内容寻址命名（同名即同内容），已存在即复用，任何路径都不会重写已有文件；台账登记内容 SHA-256 供核对。
- **同名只处理一次**：同一作业单下同名图片重复上传，直接返回已登记记录（`duplicate=true`），不重复处理、不重复落盘；`uk_order_photo_name(order_id, file_name)` 唯一约束兜底，JVM 分段锁串行同名单并发。不同作业单传同名图各自正常处理。幂等跳过不消耗请求号（与 CSV 导入"已存在跳过"同口径）；已消费的请求号重放仍按台账拒绝。
- **列表与报表**：`GET /api/work-orders/{id}/photos` 返回照片清单（含 `thumbUrl`、`thumbWidth/Height`），列表与报表直接引用缩略图；`GET /api/work-orders/photos/{photoId}/thumb|original` 取图。
- **限制**：JPG/PNG（按内容识别，不只信扩展名），单张 ≤ 20MB；上传走请求号台账（`WORK_ORDER_PHOTO_UPLOAD`），与照片登记同一事务。
- 配置：`photo.root-dir`（默认 `./data/photos`）、`photo.thumb-max-side`（默认 300）、`photo.allowed-types`、`photo.max-file-size`。

新增表：`t_work_order_photo`。上线操作流（备份→变更→验证）：[docs/work-order-photo-runbook.md](docs/work-order-photo-runbook.md)。
