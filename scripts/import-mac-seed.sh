#!/usr/bin/env bash
# Mac/Linux 侧：把 Windows 导出的种子包导入成一个"完整本地库"，并跑起来。
# 用法：bash scripts/import-mac-seed.sh /path/to/mac-seed-<ts>.zip
#   可选环境变量：MAC_DB(默认 feishu_claude) MAC_DB_USER(默认 feishu)
#     MAC_KB_DIR(默认 ~/feishu-claude-data/supplier-kb) MYSQL_PWD(目标库密码)
# 说明：Mac 只需这一个自包含本地库，导入后与 Windows 各用各的，不再互相依赖。
set -euo pipefail
ZIP="${1:?用法: import-mac-seed.sh <seed.zip>}"
MAC_DB="${MAC_DB:-feishu_claude}"
MAC_DB_USER="${MAC_DB_USER:-feishu}"
MAC_KB_DIR="${MAC_KB_DIR:-$HOME/feishu-claude-data/supplier-kb}"
WIN_KB_PREFIX='D:\feishu-claude-data\supplier-kb'

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
unzip -q "$ZIP" -d "$TMP"
[[ -f "$TMP/feishu_claude.sql" ]] || { echo "包内缺 feishu_claude.sql"; exit 1; }

echo "① 建库 $MAC_DB 并导入完整结构+数据…"
mysql -u "$MAC_DB_USER" ${MYSQL_PWD:+-p"$MYSQL_PWD"} -e "DROP DATABASE IF EXISTS $MAC_DB; CREATE DATABASE $MAC_DB CHARACTER SET utf8mb4;"
mysql -u "$MAC_DB_USER" ${MYSQL_PWD:+-p"$MYSQL_PWD"} "$MAC_DB" < "$TMP/feishu_claude.sql"

echo "② 展开知识库文件到 $MAC_KB_DIR …"
mkdir -p "$MAC_KB_DIR" && tar -xzf "$TMP/supplier-kb.tar.gz" -C "$MAC_KB_DIR" --strip-components=1

# 把库里的 Windows 绝对路径前缀改写到 Mac 本地目录，正文/子页面才打得开
echo "③ 改写 md_path: $WIN_KB_PREFIX → $MAC_KB_DIR"
mysql -u "$MAC_DB_USER" ${MYSQL_PWD:+-p"$MYSQL_PWD"} "$MAC_DB" <<SQL
UPDATE supplier_kb_entry SET md_path = REPLACE(md_path, '$WIN_KB_PREFIX', '$MAC_KB_DIR') WHERE md_path LIKE '$WIN_KB_PREFIX%';
UPDATE supplier_kb_page  SET md_path = REPLACE(md_path, '$WIN_KB_PREFIX', '$MAC_KB_DIR') WHERE md_path LIKE '$WIN_KB_PREFIX%';
SQL
# 站点 index.md 里的页面绝对路径同样改写
find "$MAC_KB_DIR" -name index.md -print0 2>/dev/null | xargs -0 -r sed -i.bak "s#$WIN_KB_PREFIX#$MAC_KB_DIR#g" || true

echo "④ 完成。启动后端(Mac 原生示例)："
echo "   export SPRING_PROFILES_ACTIVE=mysql FEISHU_MYSQL_URL='jdbc:mysql://localhost:3306/$MAC_DB?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false' FEISHU_MYSQL_USER='$MAC_DB_USER' FEISHU_MYSQL_PASSWORD='<密码>' FEISHU_KB_PATH='$MAC_KB_DIR'"
echo "   java -jar target/feishu-claude-v2-1.0-SNAPSHOT.jar"
echo "   浏览器 http://localhost:8080   （实例工作目录/供应商类库等仍指向 Windows 路径，若要在 Mac 真跑 agent 请在 UI 改成 Mac 路径；仅浏览/演示无需改）"
