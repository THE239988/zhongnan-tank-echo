#!/usr/bin/env bash
# 启动坦克大战服务器
#
#   ./start-server.sh <房间口令> [端口]
#
# 口令必填。这个进程监听在公网地址上，没有口令任何人都能连进来建房、刷屏、
# 看到所有玩家的位置。
set -euo pipefail
cd "$(dirname "$0")"

PASSWORD="${TANKTROUBLE_PASSWORD:-${1:-}}"
PORT="${TANKTROUBLE_PORT:-${2:-7777}}"

if [ -z "$PASSWORD" ]; then
  echo "用法: ./start-server.sh <房间口令> [端口]"
  echo "口令不能为空。"
  exit 2
fi

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 java。请先执行: apt install -y openjdk-21-jre-headless"
  exit 1
fi

# -Xmx 限制堆上限，避免单个房间出问题拖垮整机。实测 5 房间存活对象仅几 MB，768m 余量充足。
export TANKTROUBLE_PASSWORD="$PASSWORD"
export TANKTROUBLE_PORT="$PORT"

exec java -Xmx768m -XX:+UseSerialGC \
  -cp "tanktrouble-javafx-3.0.2.jar:lib/*" \
  tanktrouble.net.GameServer "$PORT"
