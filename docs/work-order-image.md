# 作业单图片处理（F5）设计与运维说明

理事会诉求：作业单据图片从"邮件来回发"收进系统统一管理，时效与留档都要有保障。
本文档记录口径、存储布局、幂等规则与上线步骤。

## 处理链路

```
上传 multipart ──► 校验（作业单存在/扩展名/机手工号/文件名）
                ──► SHA-256 内容指纹
                ──► 去重判定（order_id + file_name）
                ──► 生成 300px 缩略图 + 底部水印（拍摄时间 + 机手工号）
                ──► 原图落 original/、缩略图落 thumbs/（CREATE_NEW，内容寻址）
                ──► 请求号台账 + 图片元数据 同一事务落库
```

- **纯 JDK 实现**（ImageIO + Graphics2D），不依赖外部进程。注意：本机 ImageMagick
  `convert` 会崩溃（工作区 `core.*` 转储即其所留），严禁用 shell 调它兜底。
- **水印只打在缩略图上**，原图字节自落盘起不被触碰（"原图保留"）。
- 缩略图最长边 300px、等比缩放、小图不放大；格式沿用原图（jpg/png/bmp/gif）。
- 水印文本：`yyyy-MM-dd HH:mm:ss 机手工号`，底部半透明黑带白字，超宽自动缩字号（最小 8px）。
  逻辑字体渲染数字字母无环境依赖；机手工号建议用字母数字（≤24 字符）。

## 存储布局

```
data/work-order-images/            ← workorder.image.root-dir（可配）
├── original/<orderId>/<sha256>.<ext>   原图，内容寻址，永不覆盖
└── thumbs/<orderId>/<sha256>.<ext>     300px 水印缩略图，独立目录
```

- 落盘文件名 = 内容 SHA-256 + 归一扩展名：**同内容同路径、异内容异路径**，从文件系统层面
  保证"原图永不覆盖"；客户端文件名仅作元数据落 `t_work_order_image.file_name`，不参与路径。
- 元数据（尺寸、拍摄时间、机手工号、双方路径、请求号）落 `t_work_order_image`，
  图片列表/详情从库里取，下载接口按 ID 读文件。

## 幂等与永不覆盖口径

| 场景 | 行为 |
| --- | --- |
| 同名同内容重复上传 | 只处理一次：幂等返回首次记录 `duplicated=true`，不重复写盘、不重复落库 |
| 同名不同内容 | 拒绝（`success=false`，提示"原图保留不覆盖，请改名后上传"），原图不动 |
| 并发同名上传 | `uk_work_order_image_name` 唯一约束只放行一笔，后到者按重复上传口径返回 |
| 请求号重放 | 台账唯一约束拒绝："请勿重复提交" |
| 同名图发到不同作业单 | 各自独立成记录（去重范围是单内）；内容相同则磁盘上天然共享同一份文件 |

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/work-orders/{id}/images` | 上传（multipart：file、operatorCode 必填、capturedAt 可选、requestNo 必填） |
| GET | `/api/work-orders/{id}/images` | 图片列表（带 thumbUrl，供列表与报表） |
| GET | `/api/work-orders/images/{imageId}/thumb` | 缩略图下载 |
| GET | `/api/work-orders/images/{imageId}/original` | 原图下载（备查） |

按单号检索 `GET /api/work-orders?orderNo=xxx` 的详情里也带 `images`（含 thumbUrl）。

## 配置

```yaml
workorder:
  image:
    root-dir: ./data/work-order-images   # 存储根目录（original/ 与 thumbs/ 的父目录）
    thumb-size: 300                      # 缩略图最长边
spring:
  servlet:
    multipart:
      max-file-size: 20MB                # 田间手机照片 3~12MB
      max-request-size: 25MB
```

## 上线步骤（先备份 → 再变更 → 后验证，一步不省）

1. **备份**：`backups/evops-pre-f5-image-20260927-041448.tar.gz`（含 src/docs/data/pom，
   gzip 校验 + 解包冒烟 150/150 文件一致，见 `backups/BACKUP-NOTE.md`）。
2. **变更**：本功能代码 + `t_work_order_image` 表（schema.sql 幂等，启动自动执行，
   版本登记 `f5-workorder-image`）+ 配置项。存量库无需手工干预。
3. **验证**：`mvn test` 全绿（含新增 8 个图片用例）；`docs/acceptance/run-image-verify.sh`
   起真实服务做端到端验证（上传→300px 缩略图→水印→重传幂等→同名异内容拒绝→原图字节未变），
   留档 `docs/acceptance/output/image-verify.txt`。

回滚：停服 → 解包备份 tar 到工作区 → 重启。`t_work_order_image` 为新增表，回滚不影响既有表；
已落盘图片文件保留在 `root-dir` 下，不碍回滚。

## 运维注意

- 存储目录要纳入定期备份（原图是凭证）；缩略图丢失可由原图重生成，原图不可再生。
- 单文件上限 20MB，超限统一返回 `success=false`（不抛 500）。
- 换库（目前 H2）时需复核：并发同名靠唯一约束兜底后捕获 `DuplicateKeyException` 继续查询，
  该写法假设约束冲突不中止事务（H2 成立，PostgreSQL 不成立）。
