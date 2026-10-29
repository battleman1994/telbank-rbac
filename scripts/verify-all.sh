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
if grep -rq 'expiring-grants' telbank-rbac-front/src; then
  die "前端仍引用已移除的到期提醒路由/菜单"
fi
ok "前端类型检查 + 构建通过，dist 产物存在"

# ---------------------------------------------------------------- 5) 产物核对
say "5/5 产物核对"
# A23：SDK 开放接口为声明式 Feign 接口（一个 interface 类声明一组 API，签名由 Feign Client 装饰器注入）
grep -q 'feign-core' telbank-rbac-starter/pom.xml \
  || die "JDK 8 版 SDK 未使用 Feign 声明式客户端（缺少 feign-core）"
grep -q 'feign-core' telbank-rbac2-starter/pom.xml \
  || die "JDK 21 版 SDK 未使用 Feign 声明式客户端（缺少 feign-core）"
for module in telbank-rbac-starter telbank-rbac2-starter; do
  for f in RbacOpenApiFeignApi RbacOnboardingFeignApi RbacSigningClient; do
    [ -f "$module/src/main/java/com/telbank/rbac/sdk/client/$f.java" ] \
      || die "$module 缺少 Feign 基础设施类 $f"
  done
  grep -q 'RbacOpenApiFeignApi' \
    "$module/src/main/java/com/telbank/rbac/sdk/client/HttpRbacOpenApiClient.java" \
    || die "$module 客户端未通过 Feign 接口调用"
done
if grep -rq 'RestTemplate\|RestClient\|RbacFeignDecoder\|RbacResponseDecoder' \
    telbank-rbac-starter/src telbank-rbac2-starter/src; then
  die "SDK 仍存在 RestTemplate/RestClient 或旧解码器（A23 已改为声明式 Feign 接口）"
fi
grep -q 'RbacSdkAutoConfiguration' telbank-rbac-starter/src/main/resources/META-INF/spring.factories \
  || die "JDK 8 版 SDK 的 spring.factories 未注册 RbacSdkAutoConfiguration"
ok "SDK（JDK 8 版）spring.factories 自动配置 + 声明式 Feign 接口存在（A23）"
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
[ -f telbank-rbac-api/src/main/resources/db/manual/06_cleanup_admin_redundant_grants.sql ] \
  || die "缺少 06_cleanup_admin_redundant_grants.sql"
[ -f telbank-rbac-api/src/main/resources/db/manual/07_page_action_semantic_type.sql ] \
  || die "缺少 07_page_action_semantic_type.sql"
[ -f telbank-rbac-api/src/main/resources/db/manual/08_system_application.sql ] \
  || die "缺少 08_system_application.sql"
[ -f telbank-rbac-api/src/main/resources/db/manual/09_system_appkey.sql ] \
  || die "缺少 09_system_appkey.sql"
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
# A18 凭据查看 + admin 授权保护产物
[ -f telbank-rbac-api/src/main/java/com/telbank/rbac/web/admin/SystemCredentialController.java ] \
  || die "缺少 SystemCredentialController"
[ -f telbank-rbac-front/src/pages/SystemCredentialPage.tsx ] \
  || die "缺少接入凭据页面"
grep -q 'system:credential' telbank-rbac-front/src/config/menu.ts \
  || die "前端菜单缺少 system:credential"
ok "系统接入凭据查看 + admin 授权保护产物存在"
# A20 业务用户临时权限申请产物
[ -f "$SQL_DIR/10_access_application.sql" ] \
  || die "缺少 10_access_application.sql"
[ -f telbank-rbac-api/src/main/java/com/telbank/rbac/web/admin/AccessApplicationController.java ] \
  || die "缺少 AccessApplicationController"
[ -f telbank-rbac-api/src/main/java/com/telbank/rbac/web/openapi/OpenApiAccessApplicationController.java ] \
  || die "缺少 OpenApiAccessApplicationController"
[ -f telbank-rbac-api/src/main/java/com/telbank/rbac/service/AccessDecisionService.java ] \
  || die "缺少 AccessDecisionService"
[ -f telbank-rbac-front/src/pages/AccessApplicationsPage.tsx ] \
  || die "缺少我的申请页面"
[ -f telbank-rbac-front/src/api/accessApplications.ts ] \
  || die "缺少 accessApplications API 模块"
[ -x scripts/mock-access-approval.py ] \
  || die "缺少可执行的 mock-access-approval.py"
ok "业务用户临时权限申请产物存在"
# A21 角色时间模板产物
[ -f "$SQL_DIR/11_time_template.sql" ] || die "缺少 11_time_template.sql"
grep -q 'sys_time_template' "$SQL_DIR/11_time_template.sql" \
  || die "11_time_template.sql 未创建 sys_time_template"
grep -q 'time_template_id' "$SQL_DIR/11_time_template.sql" \
  || die "11_time_template.sql 未给角色/申请快照增加 time_template_id"
grep -rq '"/api/time-templates"' telbank-rbac-api/src/main/java \
  || die "缺少 /api/time-templates 时间模板接口"
grep -rq 'class RoleTimeWindowService' telbank-rbac-api/src/main/java \
  || die "缺少 RoleTimeWindowService（角色时间窗口计算）"
grep -rq 'time-templates' telbank-rbac-front/src/config/menu.ts \
  || die "前端菜单缺少 time-templates（时间模板）"
[ -f telbank-rbac-front/src/pages/TimeTemplatesPage.tsx ] \
  || die "缺少时间模板管理页面"
grep -q '角色权限' telbank-rbac-front/src/config/menu.ts \
  || die "前端菜单未更名为「角色权限」"
grep -q '用户角色' telbank-rbac-front/src/config/menu.ts \
  || die "前端菜单未更名为「用户角色」"
grep -q '版本：v3.0' docs/impl/contracts.md \
  || die "contracts.md 未升级到 v3.0（A21 时间模板）"
ok "A21 角色时间模板产物存在（11 迁移/模板接口与页面/菜单更名/契约 v3.0）"

for f in README.md docs/README.md docs/telbank-rbac.md docs/acceptance.md docs/impl/contracts.md; do
  [ -s "$f" ] || die "缺少文档 $f"
done
ok "需求/验收/契约/README/文档总览齐备"
# 架构文档：单文件、五个部分、Mermaid 图可渲染（图数量下限 + 代码围栏成对）
[ -s docs/architecture.md ] || die "缺少架构文档 docs/architecture.md"
for part in 第一部分 第二部分 第三部分 第四部分 第五部分; do
  grep -q "## ${part}" docs/architecture.md || die "架构文档缺少「${part}」"
done
MERMAID_COUNT="$(grep -c '^```mermaid' docs/architecture.md || true)"
[ "$MERMAID_COUNT" -ge 30 ] || die "架构文档 Mermaid 图不足（${MERMAID_COUNT} 张）"
FENCE_COUNT="$(grep -c '^```' docs/architecture.md || true)"
[ $((FENCE_COUNT % 2)) -eq 0 ] || die "架构文档代码围栏未闭合"
[ ! -d docs/architecture/_drafts ] || die "架构文档草稿目录未清理"
grep -q 'docs/architecture.md' docs/README.md || die "文档总览未索引 docs/architecture.md"
ok "架构文档齐备（5 部分 / ${MERMAID_COUNT} 张 Mermaid 图 / 无草稿残留）"
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
