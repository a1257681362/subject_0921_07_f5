# 作业单现场照片 上线 Runbook（F5）

理事会定下的上线顺序：**先备份 → 再变更 → 后验证，一步都不能省**。本手册按此三步执行，
每步带检查点，任一步检查点不通过即停止，按文末回滚步骤恢复。

适用范围：F5 作业单现场照片（上传生成 300px 水印缩略图、原图保留、同名去重）及其后续变更。

---

## 第一步：备份（不备份不变更）

```bash
scripts/backup-evops-data.sh pre-f5-photo
```

备份内容（输出目录形如 `backups/20260927-041335-pre-f5-photo/`）：

- `data/*.mv.db`：H2 数据文件（含验收库）
- `schema.sql.bak`、`application.yml.bak`：变更前结构与配置
- `data/photos/`（已存在时）：全部原图与缩略图
- `MANIFEST.sha256`：备份文件校验清单

检查点：

- [ ] 脚本最后一行输出备份目录路径，值班记录该路径
- [ ] `ls <备份目录>` 内 `.mv.db`、`schema.sql.bak`、`application.yml.bak`、`MANIFEST.sha256` 齐全
- [ ] `(cd <备份目录> && sha256sum -c MANIFEST.sha256)` 全部 OK（忽略 MANIFEST 自身一行）

> 注意：备份前确认应用已停止（`curl -m 2 localhost:8080/api/health` 无响应），
> 运行中的 H2 文件库拷贝可能不一致。

## 第二步：变更

1. 部署新代码（含 `t_work_order_photo` 建表语句的 `schema.sql`、图片处理模块、
   `resources/fonts/NotoSansSC-Regular.ttf` 水印字体）。
2. 启动应用：`mvn spring-boot:run`（或等效部署方式）。Spring Boot 自动执行 `schema.sql`，
   对旧库幂等：`t_work_order_photo` 不存在才建，`t_evops_schema_version` 登记 `f5-workorder-photo`。
3. 确认存储目录：`photo.root-dir`（默认 `./data/photos`）下自动建 `original/`、`thumb/` 两个独立子目录。

检查点：

- [ ] 启动日志无 `SQLException`、无建表报错
- [ ] `curl localhost:8080/api/health` 返回 `{"success":true,...}`
- [ ] 日志出现 `水印字体加载成功: fonts/NotoSansSC-Regular.ttf`
- [ ] 既有功能回归：`mvn test` 全绿（含既有 F1–F4 用例）

## 第三步：验证

按序执行，全部通过才算上线完成（本次上线已按此执行，结果见每步末尾）：

1. **上传处理**：创建作业单后 `POST /api/work-orders/{id}/photos` 上传现场照片
   （`shotTime=2026-09-27 09:15:30`、`operatorCode=JG-A017`）→
   `success=true`、`duplicate=false`、`thumbWidth=300`。✔ 已验证
2. **缩略图**：`GET .../photos/{photoId}/thumb` 返回 300px（最长边）图片，
   右下角水印清晰可读：`拍摄时间:2026-09-27 09:15:30 机手:JG-A017`。✔ 已验证（300×225）
3. **原图保留**：`GET .../photos/{photoId}/original` 下载字节与上传文件
   `sha256sum` 完全一致。✔ 已验证
4. **同名只处理一次**：同作业单同名图再次上传 → `duplicate=true`、同一 `photoId`，
   磁盘 `original/`、`thumb/` 仍各一份，原图 SHA-256 不变。✔ 已验证
5. **请求号台账**：已消费的 `requestNo` 重放 → `已处理，请勿重复提交`。✔ 已验证
6. **重启幂等**：应用重启后 `schema.sql` 重跑不报错，照片台账与文件完好。✔ 已验证
7. **列表取数**：`GET /api/work-orders/{id}/photos` 返回清单含 `thumbUrl`，
   列表与报表直接引用。✔ 已验证

## 回滚

验证任一项不通过且现场无法处置时：

1. 停止应用。
2. 用备份目录中的 `.mv.db` 覆盖 `data/` 对应文件（先确认应用已停）。
3. 回退代码到变更前版本，重启。
4. `data/photos/` 为新增目录，回滚后可保留（不影旧版运行）或按备份恢复。
5. `t_work_order_photo` 表为新增表，旧版本代码不读取，可留库待再次上线；
   如需彻底清除，停库后执行 `DROP TABLE t_work_order_photo` 并删除
   `t_evops_schema_version` 中 `f5-workorder-photo` 行。

## 值班注意

- 缩略图水印只打在缩略图上；原图永远保持上传原样，供留档核对。
- 同一作业单同名图只处理一次是**特性**（防机手重复提交），要换图请换文件名上传。
- 不同作业单上传同名图互不影响，各自正常处理。
- `photo.root-dir` 需纳入定期备份（备份脚本已覆盖）。
- 环境提醒：本机 ImageMagick `convert` 带 `-annotate` 会崩溃（工作区 `core.*` 即其残留），
  图片处理一律走应用内 JDK 实现，不要在服务器上用 ImageMagick 处理业务图片。
