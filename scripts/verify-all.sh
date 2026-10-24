#!/usr/bin/env bash
# telbank-rbac 一键验证（不需要数据库/Redis；端到端验收另见 scripts/acceptance.py）
#
# 覆盖：
#   1) 权限中心后端：JDK 21 编译 + 单元测试，断言字节码 major=65
#   2) SDK（JDK 8 版）：JDK 17 工具链构建 + 单元测试，断言字节码 major=52（Java 8）
#   3) SDK（JDK 21 版）：JDK 21 构建 + 单元测试，断言字节码 major=65
#   4) 管理前端：tsc 类型检查 + vite 生产构建
#   5) 产物/配置核对（两个 SDK 的自动配置入口、文档、脚本）
#
# 用法：
#   ./scripts/verify-all.sh
#   RBAC_M2_REPO=/tmp/rbac-m2repo ./scripts/verify-all.sh   # 自定义 Maven 本地仓库
set -euo pipefail
cd "$(dirname "$0")/.."

PASS=0
say()  { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
ok()   { PASS=$((PASS + 1)); printf '  ✅ %s\n' "$*"; }
die()  { printf '\n❌ %s\n' "$*" >&2; exit 1; }

MVN_ARGS=(-B)
if [ -n "${RBAC_M2_REPO:-}" ]; then
  MVN_ARGS+=("-Dmaven.repo.local=${RBAC_M2_REPO}")
fi

find_jdk21() {
  local cands=() c ver
  [ -n "${JAVA_HOME_21:-}" ] && cands+=("${JAVA_HOME_21}")
  cands+=(
    "${HOME}/.sdkman/candidates/java/current"
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  )
  if /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    cands+=("$(/usr/libexec/java_home -v 21)")
  fi
  for c in "${cands[@]}"; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] || continue
    ver="$("$c/bin/java" -version 2>&1 | head -n 1)"
    case "$ver" in
      *'"21.'*|*'"22.'*|*'"23.'*|*'"24.'*|*'"25.'*|*'"26.'*|*'"27.'*) echo "$c"; return 0 ;;
    esac
  done
  return 1
}

JDK21="$(find_jdk21)" || die "未找到 JDK 21+（设置 JAVA_HOME_21 或安装 openjdk@21）"
echo "使用 JDK 21：${JDK21}"
JAVA_HOME="${JDK21}" "${JDK21}/bin/java" -version 2>&1 | head -n 1

# ---------------------------------------------------------------- 1) 权限中心
say "1/5 权限中心后端（JDK 21）：编译 + 单元测试"
JAVA_HOME="${JDK21}" mvn "${MVN_ARGS[@]}" -pl telbank-rbac-api clean test
major="$(JAVA_HOME="${JDK21}" javap -verbose -cp telbank-rbac-api/target/classes \
  com.telbank.rbac.RbacApiApplication 2>/dev/null | awk '/major version/{print $3}')"
[ "$major" = "65" ] || die "权限中心字节码 major=${major}，期望 65（Java 21）"
ok "权限中心单元测试通过，字节码 major=65"

# ---------------------------------------------------------------- 2) SDK JDK 8
say "2/5 SDK（JDK 8 版：JDK 17 工具链 → Java 8 字节码）：构建 + 单元测试"
./scripts/build-sdk.sh
ok "SDK（JDK 8 版）单元测试通过，字节码 major=52"

# ---------------------------------------------------------------- 3) SDK JDK 21
say "3/5 SDK（JDK 21 版：JDK 21 + Boot 4，协议一致）：构建 + 单元测试"
JAVA_HOME="${JDK21}" mvn "${MVN_ARGS[@]}" -pl telbank-rbac2-starter clean test
major21="$(JAVA_HOME="${JDK21}" javap -verbose -cp telbank-rbac2-starter/target/classes \
  com.telbank.rbac.sdk.RbacSdkAutoConfiguration 2>/dev/null | awk '/major version/{print $3}')"
[ "$major21" = "65" ] || die "JDK 21 版 SDK 字节码 major=${major21}，期望 65（Java 21）"
ok "SDK（JDK 21 版）单元测试通过，字节码 major=65"

# ---------------------------------------------------------------- 4) 前端
say "4/5 管理前端（nvm Node 22）：tsc 类型检查 + 生产构建"
export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
if [ -s "${NVM_DIR}/nvm.sh" ]; then
  # shellcheck disable=SC1091
  . "${NVM_DIR}/nvm.sh"
  nvm use 22 >/dev/null 2>&1 || true
fi
echo "node $(node -v) / npm $(npm -v)"
( cd telbank-rbac-front && npm run build )
[ -f telbank-rbac-front/dist/index.html ] || die "前端 dist/index.html 不存在"
ok "前端类型检查 + 构建通过，dist 产物存在"

# ---------------------------------------------------------------- 5) 产物核对
say "5/5 产物核对"
grep -q 'RbacSdkAutoConfiguration' telbank-rbac-starter/src/main/resources/META-INF/spring.factories \
  || die "JDK 8 版 SDK 的 spring.factories 未注册 RbacSdkAutoConfiguration"
ok "SDK（JDK 8 版）spring.factories 自动配置存在"
grep -q 'RbacSdkAutoConfiguration' \
  telbank-rbac2-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  || die "JDK 21 版 SDK 的 AutoConfiguration.imports 未注册 RbacSdkAutoConfiguration"
ok "SDK（JDK 21 版）AutoConfiguration.imports 自动配置存在"
# 数据库初始化：手动 SQL（无 Flyway）
SQL_DIR="telbank-rbac-api/src/main/resources/db/manual"
[ -d "$SQL_DIR" ] || die "缺少手动 SQL 目录：$SQL_DIR"
SQL_COUNT="$(ls "$SQL_DIR"/*.sql 2>/dev/null | wc -l | tr -d ' ')"
[ "$SQL_COUNT" -ge 3 ] || die "手动 SQL 文件不足（$SQL_COUNT 个）"
grep -qi 'flyway' telbank-rbac-api/pom.xml && die "pom.xml 仍包含 Flyway 依赖"
if grep -rqi 'flyway' telbank-rbac-api/src/main/java; then die "源码仍包含 Flyway 引用"; fi
ok "数据库初始化采用手动 SQL（db/manual，无 Flyway）"
for f in README.md docs/README.md docs/telbank-rbac.md docs/acceptance.md docs/impl/contracts.md; do
  [ -s "$f" ] || die "缺少文档 $f"
done
ok "需求/验收/契约/README/文档总览齐备"
for f in scripts/init-db.sh scripts/start-api.sh scripts/start-front.sh scripts/gen-demo-jwt.sh \
         scripts/acceptance.py scripts/build-sdk.sh scripts/verify-all.sh scripts/start-all.sh \
         scripts/demo-temp-permission.sh; do
  [ -x "$f" ] || die "脚本不可执行或缺失：$f"
done
ok "启动/验收/构建脚本齐备且可执行"

# 分平台启动脚本（macOS / Linux / Windows）
for f in scripts/start-all-macos.sh scripts/start-all-linux.sh; do
  [ -x "$f" ] || die "分平台启动脚本缺失或不可执行：$f"
  bash -n "$f" || die "分平台启动脚本语法错误：$f"
done
ok "macOS/Linux 启动脚本存在且语法正确"
[ -f scripts/start-all-windows.ps1 ] || die "缺少 Windows 启动脚本 scripts/start-all-windows.ps1"
[ -f scripts/start-all.cmd ] || die "缺少 Windows 包装器 scripts/start-all.cmd"
grep -q 'TempDb' scripts/start-all-windows.ps1 || die "Windows 启动脚本未实现临时库参数"
ok "Windows 启动脚本（ps1 + cmd）齐备"
case "$(uname -s)" in
  Darwin|Linux)
    HELP_OUT="$(./scripts/start-all.sh --help 2>&1 || true)"
    case "$HELP_OUT" in
      *"--temp-db"*) ok "start-all.sh 平台分发正常（$(uname -s)）" ;;
      *) die "start-all.sh 平台分发异常：${HELP_OUT}" ;;
    esac
    ;;
esac

# macOS 自带 bash 3.2 + set -u 空数组展开回归检查（start-api.sh 曾因此报 unbound variable）
FAKE_BIN="$(mktemp -d)"
printf '#!/bin/sh\necho fake-mvn "$@"\n' > "$FAKE_BIN/mvn"
chmod +x "$FAKE_BIN/mvn"
API_SMOKE_OUT="$(env -u RBAC_M2_REPO PATH="$FAKE_BIN:$PATH" JAVA_HOME="$JDK21" \
  ./scripts/start-api.sh 2>&1 || true)"
rm -rf "$FAKE_BIN"
case "$API_SMOKE_OUT" in
  *unbound*) die "start-api.sh 在 bash 3.2 下存在空数组展开问题" ;;
  *fake-mvn*) ok "start-api.sh bash 3.2 兼容冒烟通过（未设置 RBAC_M2_REPO）" ;;
  *) die "start-api.sh bash 3.2 冒烟失败：${API_SMOKE_OUT}" ;;
esac

printf '\n\033[1;32m全部通过（%d 项检查）。\033[0m\n' "$PASS"
cat <<'EOF'

下一步（需要启动服务，端到端验收）：
  ./scripts/init-db.sh                          # 或 ./scripts/start-temp-mysql.sh start
  ./scripts/start-api.sh                        # 权限中心 demo profile（管理端 X-System-Code: rbac）
  python3 scripts/acceptance.py                 # 第 9 节 API 级验收（含 §9.15 权限中心自接入）
  # SDK 端到端（另起 CRM/ERP 示例业务系统）：
  #   RBAC_DEMO_CRM_URL=http://localhost:8081 RBAC_DEMO_ERP_URL=http://localhost:8082 \
  #     python3 scripts/acceptance.py --only 9.1b
EOF
