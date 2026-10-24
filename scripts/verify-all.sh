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
for f in README.md docs/README.md docs/telbank-rbac.md docs/acceptance.md docs/impl/contracts.md; do
  [ -s "$f" ] || die "缺少文档 $f"
done
ok "需求/验收/契约/README/文档总览齐备"
for f in scripts/init-db.sh scripts/start-api.sh scripts/start-front.sh scripts/gen-demo-jwt.sh \
         scripts/acceptance.py scripts/build-sdk.sh scripts/verify-all.sh; do
  [ -x "$f" ] || die "脚本不可执行或缺失：$f"
done
ok "启动/验收/构建脚本齐备且可执行"

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
