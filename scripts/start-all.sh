#!/usr/bin/env bash
# 同时启动权限中心后端与管理前端 —— Unix 分发器
#
# 按操作系统分发到对应平台脚本（Windows 请使用 scripts\start-all.cmd）：
#   macOS → scripts/start-all-macos.sh
#   Linux → scripts/start-all-linux.sh
#
# 用法：
#   ./scripts/start-all.sh --temp-db
#   API_PORT=18080 FRONT_PORT=5174 ./scripts/start-all.sh --temp-db
#
# 参数会原样传给对应平台脚本（--temp-db / --init-db / -h）。
set -euo pipefail
cd "$(dirname "$0")"

OS="$(uname -s)"
case "$OS" in
  Darwin) exec ./start-all-macos.sh "$@" ;;
  Linux)  exec ./start-all-linux.sh "$@" ;;
  *)
    echo "❌ 当前系统 ${OS} 暂不支持 Unix 启动脚本。" >&2
    echo "   Windows 请使用：scripts\\start-all.cmd --temp-db（或 start-all-windows.ps1）" >&2
    exit 1
    ;;
esac
