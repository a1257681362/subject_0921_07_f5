#!/usr/bin/env bash
# EVOPS 上线前备份：H2 数据文件 + schema + 配置 + 图片目录，整体打入 backups/<时间戳>/。
# 用法：scripts/backup-evops-data.sh [备注]
# 输出：备份目录路径（最后一行），供值班记录与回滚使用。
set -euo pipefail

cd "$(dirname "$0")/.."
NOTE="${1:-manual}"
TS="$(date +%Y%m%d-%H%M%S)"
DEST="backups/${TS}-${NOTE}"

mkdir -p "${DEST}"

# 1) H2 数据文件（库可能正在运行，.mv.db 单文件拷贝即可；如有 .lock.db 一并带走）
shopt -s nullglob
DB_FILES=(data/*.mv.db data/*.lock.db)
if [ ${#DB_FILES[@]} -eq 0 ]; then
    echo "警告：data/ 下没有找到 H2 数据文件" >&2
else
    cp -a "${DB_FILES[@]}" "${DEST}/"
fi

# 2) schema 与配置（回滚时据此比对结构差异）
cp -a src/main/resources/schema.sql "${DEST}/schema.sql.bak"
cp -a src/main/resources/application.yml "${DEST}/application.yml.bak"

# 3) 图片目录（已上线图片功能后，原图/缩略图一并备份）
if [ -d data/photos ]; then
    cp -a data/photos "${DEST}/photos"
fi

# 4) 备份清单（校验用）
( cd "${DEST}" && find . -type f -exec sha256sum {} \; ) > "${DEST}/MANIFEST.sha256"

echo "备份完成：${DEST}"
echo "${DEST}"
