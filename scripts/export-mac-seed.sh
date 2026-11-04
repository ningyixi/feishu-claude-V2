#!/usr/bin/env bash
# Windows 侧：导出当前生产库为一个"Mac 种子包"(完整结构+数据 + 知识库文件 + 导入脚本)。
# 用法：bash scripts/export-mac-seed.sh
#   依赖：本机 MySQL 客户端(mysqldump)、tar、gzip；读环境变量或默认本地迁移留下的配置。
#   可选：MYSQL_PWD(目标库用户密码，默认读 D:/mysql-local/.migrate_pw)
#         FEISHU_MYSQL_USER(默认 feishu) FEISHU_MYSQL_DB(默认 feishu_claude)
#         MYSQL_BIN(默认 D:/mysql-local/mysql-8.0.28-winx64/bin)
#         FEISHU_KB_PATH(默认 D:/feishu-claude-data/supplier-kb)
set -euo pipefail
cd "$(dirname "$0")/.."
MYSQL_BIN="${MYSQL_BIN:-/d/mysql-local/mysql-8.0.28-winx64/bin}"
DB="${FEISHU_MYSQL_DB:-feishu_claude}"
DBUSER="${FEISHU_MYSQL_USER:-feishu}"
KB="${FEISHU_KB_PATH:-/d/feishu-claude-data/supplier-kb}"
if [[ -z "${MYSQL_PWD:-}" && -f /d/mysql-local/.migrate_pw ]]; then MYSQL_PWD="$(cat /d/mysql-local/.migrate_pw)"; fi
: "${MYSQL_PWD:?需要 MYSQL_PWD（目标库用户密码）}"
[[ -x "$MYSQL_BIN/mysqldump.exe" || -f "$MYSQL_BIN/mysqldump.exe" ]] || { echo "找不到 mysqldump，在 MYSQL_BIN: $MYSQL_BIN"; exit 1; }

B="backups/mac-seed-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$B"
echo "① 导出库 $DB(结构+数据) → $B/feishu_claude.sql"
"$MYSQL_BIN/mysqldump.exe" -u "$DBUSER" -p"$MYSQL_PWD" -h 127.0.0.1 \
  --single-transaction --no-tablespaces --routines --triggers --set-gtid-purged=OFF \
  --default-character-set=utf8mb4 --add-drop-table "$DB" > "$B/feishu_claude.sql"
echo "   表数: $(grep -c 'CREATE TABLE' "$B/feishu_claude.sql")"

echo "② 打包知识库目录 $KB"
tar -czf "$B/supplier-kb.tar.gz" -C "$(dirname "$KB")" "$(basename "$KB")"

cp scripts/import-mac-seed.sh "$B/"
cp "docs/Mac部署与数据导入.md" "$B/" 2>/dev/null || true

OUT="$B.tar.gz"
tar -czf "$OUT" -C backups "$(basename "$B")"
rm -rf "$B"
echo
echo "★ 种子包已生成: $OUT   （把它拷到 Mac，按包内 README/文档操作即可）"
echo "  注意：内含业务数据，属敏感文件，放 gitignore 的 backups/ 下，不要提交 Git。"
