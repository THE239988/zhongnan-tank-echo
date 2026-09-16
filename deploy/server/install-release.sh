#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 root 运行。"
  exit 1
fi

SOURCE="${1:?用法: install-release.sh <解压后的发布目录> <版本>}"
VERSION="${2:?用法: install-release.sh <解压后的发布目录> <版本>}"
ROOT="/opt/tank-trouble"
RELEASE="$ROOT/releases/$VERSION"

case "$VERSION" in
  *[!A-Za-z0-9._-]*|'')
    echo "版本号只能包含字母、数字、点、下划线和连字符。"
    exit 1
    ;;
esac

if [ ! -f "$SOURCE/tanktrouble-javafx-$VERSION.jar" ]; then
  echo "发布目录中缺少 tanktrouble-javafx-$VERSION.jar"
  exit 1
fi

if [ ! -f "/etc/tank-trouble.env" ]; then
  echo "缺少 /etc/tank-trouble.env，请先设置 TANKTROUBLE_PASSWORD。"
  exit 1
fi

install -d -m 0755 "$ROOT/releases"
rm -rf "$RELEASE"
install -d -m 0755 "$RELEASE"
cp -a "$SOURCE"/. "$RELEASE"/
chmod 0755 "$RELEASE/start-server.sh"

ln -sfn "$RELEASE" "$ROOT/current"
install -m 0644 "$RELEASE/tank-trouble.service" /etc/systemd/system/tank-trouble.service
systemctl daemon-reload
systemctl restart tank-trouble.service
systemctl --no-pager --full status tank-trouble.service
