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
# v2.0 系统隔离重构产物核对
[ -f telbank-rbac-api/src/main/resources/db/manual/04_system_isolation.sql ] \
  || die "缺少 04_system_isolation.sql"
[ -f telbank-rbac-api/src/main/resources/db/manual/05_wildcard_permissions.sql ] \
  || die "缺少 05_wildcard_permissions.sql"
[ -f telbank-rbac-api/src/main/java/com/telbank/rbac/common/PermissionMatcher.java ] \
  || die "缺少 PermissionMatcher（通配匹配器）"
grep -rq 'class UserSystemController' telbank-rbac-api/src/main/java && die "仍存在 UserSystemController"
[ -d telbank-rbac-api/src/main/java/com/telbank/rbac/security ] \
  && [ -f telbank-rbac-api/src/main/java/com/telbank/rbac/security/ManagementContextService.java ] \
  || die "缺少 ManagementContextService（系统隔离上下文）"
[ -f telbank-rbac-api/src/main/java/com/telbank/rbac/service/PlatformAdminService.java ] \
  || die "缺少 PlatformAdminService（数据驱动超管）"
grep -q 'platform-admin-ids' telbank-rbac-api/src/main/resources/application.yml && die "仍存在配置驱动超管 platform-admin-ids"
ok "系统隔离重构产物核对（04 迁移 / 上下文 / 数据驱动超管 / 无 user-system）"
# 持久层：MyBatis-Plus（A13），无 JPA/Hibernate
grep -q 'mybatis-plus-spring-boot4-starter' telbank-rbac-api/pom.xml \
  || die "pom 缺少 mybatis-plus-spring-boot4-starter"
grep -q 'spring-boot-starter-data-jpa' telbank-rbac-api/pom.xml && die "pom 仍包含 data-jpa"
if grep -rqi 'jakarta.persistence\|org.hibernate\|JpaRepository\|EntityManager' telbank-rbac-api/src/main/java; then
  die "源码仍有 JPA/Hibernate 引用"
fi
[ -f telbank-rbac-api/src/main/java/com/telbank/rbac/config/MybatisPlusConfig.java ] \
  || die "缺少 MybatisPlusConfig"
[ -d telbank-rbac-api/src/main/java/com/telbank/rbac/domain/mapper ] \
  || die "缺少 MyBatis-Plus Mapper 目录"
ok "持久层使用 MyBatis-Plus（无 JPA/Hibernate）"
for f in README.md docs/README.md docs/telbank-rbac.md docs/acceptance.md docs/impl/contracts.md; do
  [ -s "$f" ] || die "缺少文档 $f"
done
ok "需求/验收/契约/README/文档总览齐备"
for f in scripts/init-db.sh scripts/gen-demo-jwt.sh scripts/acceptance.py \
         scripts/build-sdk.sh scripts/verify-all.sh scripts/demo-temp-permission.sh; do
  [ -x "$f" ] || die "脚本不可执行或缺失：$f"
done
ok "工具脚本齐备且可执行"

# 每平台一个自包含启动脚本（共 3 个，无分发器/helper）
for f in scripts/start-all-macos.sh scripts/start-all-linux.sh; do
  [ -x "$f" ] || die "平台启动脚本缺失或不可执行：$f"
  bash -n "$f" || die "平台启动脚本语法错误：$f"
  grep -q 'start_backend' "$f" || die "平台启动脚本未内聚后端启动：$f"
  grep -q 'start_frontend' "$f" || die "平台启动脚本未内聚前端启动：$f"
done
[ -f scripts/start-all-windows.ps1 ] || die "缺少 Windows 启动脚本 scripts/start-all-windows.ps1"
grep -q 'Invoke-InitSchema' scripts/start-all-windows.ps1 || die "Windows 启动脚本未实现数据库初始化"
ok "3 个平台启动脚本齐备且自包含（macOS/Linux/Windows）"
[ ! -e scripts/start-all.sh ] || die "不应再有 start-all.sh 分发器"
[ ! -e scripts/start-all.cmd ] || die "不应再有 start-all.cmd 包装器"
[ ! -e scripts/start-api.sh ] || die "不应再有 start-api.sh helper"
[ ! -e scripts/start-front.sh ] || die "不应再有 start-front.sh helper"
[ ! -e scripts/start-temp-mysql.sh ] || die "不应再有 start-temp-mysql.sh helper"
ok "已删除分发器 / Windows 包装器 / 3 个 helper 启动脚本"
case "$(uname -s)" in
  Darwin)
    HELP_OUT="$(./scripts/start-all-macos.sh --help 2>&1 || true)"
    case "$HELP_OUT" in
      *"--temp-db"*) ok "macOS 启动脚本 --help 正常" ;;
      *) die "macOS 启动脚本 --help 异常：${HELP_OUT}" ;;
    esac
    ;;
  Linux)
    HELP_OUT="$(./scripts/start-all-linux.sh --help 2>&1 || true)"
    case "$HELP_OUT" in
      *"--temp-db"*) ok "Linux 启动脚本 --help 正常" ;;
      *) die "Linux 启动脚本 --help 异常：${HELP_OUT}" ;;
    esac
    ;;
esac

printf '\n\033[1;32m全部通过（%d 项检查）。\033[0m\n' "$PASS"
cat <<'EOF'

下一步（需要启动服务，端到端验收）：
  ./scripts/init-db.sh                          # 可选：已有库/临时库
  ./scripts/start-all-macos.sh --temp-db        # macOS；Linux 用 start-all-linux.sh；Windows 用 ps1
  python3 scripts/acceptance.py                 # 第 9 节 API 级验收（含系统隔离/系统接入）
  # SDK 端到端（另起 CRM/ERP 示例业务系统）：
  #   RBAC_DEMO_CRM_URL=http://localhost:8081 RBAC_DEMO_ERP_URL=http://localhost:8082 \
  #     python3 scripts/acceptance.py --only 9.1b
EOF
