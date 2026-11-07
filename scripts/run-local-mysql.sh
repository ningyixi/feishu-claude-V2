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
# 先打包成 fat jar 再以 java -jar 运行：运行时类路径（jar）与构建输出（target/classes）物理隔离。
# 教训：spring-boot:run 直接跑 target/classes，并发 mvn test 重编译会让 JVM 延迟加载撞进类文件
# 增删窗口，解析失败被 HotSpot 永久缓存（NoClassDefFoundError 即使文件完好也持续报，只能重启）。
JAR=target/feishu-claude-v2-1.0-SNAPSHOT.jar
"$MVN" -q -DskipTests package || { echo "打包失败，未启动"; exit 1; }
"${JAVA_HOME}/bin/java" -jar "$JAR"
