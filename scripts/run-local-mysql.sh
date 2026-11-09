#!/usr/bin/env bash
# 本地 MySQL 模式启动后端。密码从仓库外文件读取，不进 Git。
# 用法：bash scripts/run-local-mysql.sh   （前台运行，Ctrl+C 停止）
set -euo pipefail
cd "$(dirname "$0")/.."

PW_FILE="${MYSQL_PW_FILE:-/d/mysql-local/.migrate_pw}"
[[ -f "$PW_FILE" ]] || { echo "找不到 MySQL 密码文件: $PW_FILE（设 MYSQL_PW_FILE 指定，或先跑迁移脚本）"; exit 1; }

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21}"
export SPRING_PROFILES_ACTIVE=mysql
export FEISHU_MYSQL_URL="${FEISHU_MYSQL_URL:-jdbc:mysql://localhost:3306/feishu_claude?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&preserveInstants=true&allowPublicKeyRetrieval=true&useSSL=false}"
export FEISHU_MYSQL_USER="${FEISHU_MYSQL_USER:-feishu}"
export FEISHU_MYSQL_PASSWORD="$(cat "$PW_FILE")"
export FEISHU_KB_PATH="${FEISHU_KB_PATH:-D:/feishu-claude-data/supplier-kb}"

MVN="${MVN:-/c/Program Files/JetBrains/IntelliJ IDEA 2026.1.3/plugins/maven/lib/maven3/bin/mvn.cmd}"
echo "profile=mysql url=$FEISHU_MYSQL_URL kb=$FEISHU_KB_PATH"
# 打包后把 jar 复制到本地磁盘稳定路径再运行，双重隔离两类事故：
# 1) 运行时类路径与构建输出（target/classes）隔离——并发 mvn 不会再毒化延迟类加载（HotSpot 解析失败永久缓存）。
# 2) 与仓库所在的网络盘（Z: NAS）隔离——SMB 会话掉线会使 JVM 持有的 jar 句柄作废，
#    运行数天后随机 ClassNotFoundException（2026-11-08 周末实证）。
JAR=target/feishu-claude-v2-1.0-SNAPSHOT.jar
RUN_JAR="${RUN_JAR:-D:/feishu-claude-data/run/app.jar}"
"$MVN" -q -DskipTests package || { echo "打包失败，未启动"; exit 1; }
mkdir -p "$(dirname "$RUN_JAR")"
cp -f "$JAR" "$RUN_JAR"
echo "运行 jar: $RUN_JAR（本地盘，构建与网络盘均不可达）"
"${JAVA_HOME}/bin/java" -jar "$RUN_JAR"
