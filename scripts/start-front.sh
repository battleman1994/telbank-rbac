#!/usr/bin/env bash
# 启动权限中心管理前端（开发模式）
#
# 前置条件: nvm + Node 22.22+（本仓库使用 nvm 的 Node 22）
# 用法: ./scripts/start-front.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
if [ -s "${NVM_DIR}/nvm.sh" ]; then
  # shellcheck disable=SC1091
  . "${NVM_DIR}/nvm.sh"
  nvm use 22 >/dev/null 2>&1 || true
fi

echo "node $(node -v) / npm $(npm -v)"
cd telbank-rbac-front
if [ ! -d node_modules ]; then
  npm install --no-audit --no-fund
fi
exec npm run dev
