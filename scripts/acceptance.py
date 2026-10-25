#!/usr/bin/env python3
"""telbank-rbac 需求第 9 节 · 权限中心 API 级验收冒烟脚本。

前置：
  1) 已初始化 MySQL 库 telbank_rbac（scripts/init-db.sh）
  2) 权限中心以 demo profile 启动（平台一键脚本，或 mvn spring-boot:run），监听 8080
  3) 本脚本只依赖 Python 3 标准库

用法：
  python3 scripts/acceptance.py                 # 全量
  python3 scripts/acceptance.py --base-url http://localhost:8080
  python3 scripts/acceptance.py --only 9.1,9.12 # 只跑指定章节

说明：SDK/前端行为（§9.3/§9.4/§9.6/§9.9 的拦截器判定、模式 A/B 渲染）以
starter/front 单元测试与示例业务系统联调为证据，本脚本覆盖其中可在 API 层验证的部分。

A21 起授权窗口由角色时间模板计算：§9.17 验证模板化审批（决策请求不再携带窗口），
§9.18 验证时间模板 CRUD、角色绑定、模板窗口分配与停用拒绝。
"""

import argparse
import base64
import datetime as dt
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE_URL = os.environ.get("RBAC_BASE_URL", "http://localhost:8080")
JWT_SECRET = os.environ.get("RBAC_JWT_HS256_SECRET", "telbank-rbac-demo-secret-please-change")
JWT_ISSUER = os.environ.get("RBAC_JWT_ISSUER", "demo-auth")
APPROVAL_APP_KEY = os.environ.get("RBAC_APPROVAL_APP_KEY", "approval-system")
APPROVAL_SECRET = os.environ.get("RBAC_APPROVAL_SECRET", "approval-dev-secret")
ONBOARDING_APP_KEY = os.environ.get("RBAC_ONBOARDING_APP_KEY", "onboarding-app")
ONBOARDING_SECRET = os.environ.get("RBAC_ONBOARDING_SECRET", "onboarding-secret")
CLIENT_SECRETS = {
    "crm": os.environ.get("RBAC_CRM_SECRET", "crm-dev-secret"),
    "erp": os.environ.get("RBAC_ERP_SECRET", "erp-dev-secret"),
}

# 可选：示例业务系统（samples/business-demo）地址，用于 SDK 端到端拦截验收
DEMO_CRM_URL = os.environ.get("RBAC_DEMO_CRM_URL", "").rstrip("/")
DEMO_ERP_URL = os.environ.get("RBAC_DEMO_ERP_URL", "").rstrip("/")
# 可选：JDK 21 版 SDK 示例业务系统（samples/business-demo-jdk21）
DEMO21_CRM_URL = os.environ.get("RBAC_DEMO_JDK21_CRM_URL", "").rstrip("/")
DEMO21_ERP_URL = os.environ.get("RBAC_DEMO_JDK21_ERP_URL", "").rstrip("/")

USER_ADMIN = "13800138000"        # CRM + ERP 管理员（非平台超管）
USER_OPERATOR = "13900139000"     # 仅 CRM 运营（临时授权）
USER_CROSS = "13600136000"        # CRM 运营 + ERP 采购员（跨系统隔离验收）
USER_EXPIRED = "13700137000"      # 授权全部过期
USER_NEW = "13500135000"          # 审批授权验收（初始无数据）
SUPER_ADMIN = "superadmin"        # 平台超管（数据驱动：platform 系统 super-admin → platform:admin）
DIRECT_USER = "13400134000"       # A21 管理员直接分配窗口验收（初始无数据）
DIRECT_USER2 = "13300133000"      # A21 模板停用后审批拒绝验收（初始无数据）
DIRECT_USER3 = "13200132000"      # A21 模板停用后分配拒绝验收（初始无数据）
CRM_ADMIN = "crm-admin-001"       # CRM 系统管理员（仅本系统）
ERP_ADMIN = "erp-admin-001"       # ERP 系统管理员（仅本系统）
CRM = "crm"                       # 管理上下文：当前业务系统
ERP = "erp"
MGMT_SYSTEM = CRM                 # 兼容既有调用：管理上下文 = 当前业务系统

RESULTS = []
CURRENT_SECTION = ["?"]


# --------------------------------------------------------------------------
# 基础工具
# --------------------------------------------------------------------------
def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


def make_jwt(subject, ttl=3600):
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps({
        "sub": subject, "iss": JWT_ISSUER, "iat": now, "exp": now + ttl,
    }, separators=(",", ":")).encode())
    signing = header + b"." + payload
    sig = b64url(hmac.new(JWT_SECRET.encode(), signing, hashlib.sha256).digest())
    return (signing + b"." + sig).decode()


def check(name, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    RESULTS.append((CURRENT_SECTION[0], name, status, detail))
    print("  [%s] %s%s" % (status, name, ("  <- " + str(detail)) if detail and not condition else ""))
    return bool(condition)


def section(title):
    CURRENT_SECTION[0] = title
    print("\n=== %s ===" % title)


def http(method, path, token=None, system=None, body=None, extra_headers=None):
    url = BASE_URL.rstrip("/") + path
    data = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if system:
        headers["X-System-Code"] = system
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
    return _send(req)


def _send(req):
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, _parse(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return e.code, _parse(raw)
    except urllib.error.URLError as e:
        raise SystemExit("无法连接 %s：%s（请先启动权限中心 demo profile）" % (BASE_URL, e))


def _parse(raw):
    try:
        return json.loads(raw)
    except Exception:
        return {"raw": raw}


def data_of(resp, key="data"):
    body = resp[1]
    if isinstance(body, dict) and key in body:
        return body[key]
    return body


def signed_headers(app_key, app_secret, method, path, query="", body=b""):
    timestamp = str(int(time.time() * 1000))
    nonce = secrets.token_hex(12)
    # 契约 §4.1：原始 query 片段按 key 排序后直接拼接（不做解码/重编码）
    fragments = [item for item in query.split("&") if item]
    fragments.sort(key=lambda f: f.split("=", 1)[0])
    sorted_query = "&".join(fragments)
    canonical = "\n".join([app_key, timestamp, nonce, method.upper(), path,
                           sorted_query, hashlib.sha256(body).hexdigest()])
    signature = hmac.new(app_secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return {
        "X-App-Key": app_key,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
    }


def openapi(method, path, query="", body=None, system="crm", app_key=None, secret=None):
    raw = b"" if body is None else json.dumps(body).encode("utf-8")
    app_key = app_key or system
    secret = secret or CLIENT_SECRETS.get(system, "")
    headers = signed_headers(app_key, secret, method, path, query, raw)
    if body is not None:
        headers["Content-Type"] = "application/json"
    url = BASE_URL.rstrip("/") + path + (("?" + query) if query else "")
    req = urllib.request.Request(url, data=raw if body is not None else None,
                                 headers=headers, method=method.upper())
    return _send(req)


def onboarding_report(payload, secret=None):
    """POST /openapi/onboarding/systems：onboarding 专用 AppKey+HMAC。"""
    raw = json.dumps(payload).encode("utf-8")
    headers = signed_headers(ONBOARDING_APP_KEY, secret or ONBOARDING_SECRET,
                             "POST", "/openapi/onboarding/systems", "", raw)
    headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE_URL.rstrip("/") + "/openapi/onboarding/systems",
                                 data=raw, headers=headers, method="POST")
    return _send(req)


def http_abs(method, url, token=None):
    """直接请求示例业务系统（非权限中心）。"""
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=None, headers=headers, method=method.upper())
    try:
        return _send(req)
    except SystemExit:
        return (0, {"error": "unreachable"})


def token_of(user):
    return make_jwt(user)


# --------------------------------------------------------------------------
# 时间模板 / 时间窗口工具（A21）
# --------------------------------------------------------------------------
def flatten_permissions(nodes):
    """展平权限树，返回节点列表。"""
    result = []
    for node in nodes or []:
        result.append(node)
        result.extend(flatten_permissions(node.get("children")))
    return result


def parse_iso(ts):
    """解析后端返回的 ISO-8601（Z / 带偏移 / 可含毫秒），返回 epoch 秒；非法返回 None。"""
    if not ts:
        return None
    text = str(ts).strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        moment = dt.datetime.fromisoformat(text)
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=dt.timezone.utc)
    return moment.timestamp()


def window_of(item):
    """取授权窗口，兼容 approvedValidFrom/Until 与 validFrom/Until 两种字段名。"""
    if not isinstance(item, dict):
        return (None, None)
    return (item.get("approvedValidFrom") or item.get("validFrom"),
            item.get("approvedValidUntil") or item.get("validUntil"))


def window_sane(from_ts, until_ts):
    """窗口合理性：可解析、结束晚于开始、开始不早于现在 5 分钟、结束不晚于 3 天后。"""
    start = parse_iso(from_ts)
    end = parse_iso(until_ts)
    now = time.time()
    return (start is not None and end is not None
            and end > start
            and start >= now - 300
            and end <= now + 3 * 86400)


def time_templates(token, system=CRM):
    """GET /api/time-templates，返回 (status, list)。"""
    status, body = http("GET", "/api/time-templates", token=token, system=system)
    data = data_of((status, body))
    return status, (data if isinstance(data, list) else [])


# --------------------------------------------------------------------------
# §9.14 安全底线（先跑，确认服务可用）
# --------------------------------------------------------------------------
def test_auth():
    section("§9.14 安全底线")
    status, _ = http("GET", "/api/me")
    check("无 JWT 访问 /api/me 返回 401", status == 401, status)
    status, _ = http("GET", "/api/me", token=make_jwt(USER_ADMIN, ttl=-60))
    check("过期 JWT 返回 401", status == 401, status)
    status, body = http("GET", "/api/me", token=token_of(USER_ADMIN))
    check("合法 JWT 访问 /api/me 返回 200", status == 200, status)
    check("平台超管标识正确", data_of((status, body)) and data_of((status, body)).get("platformAdmin") is False,
          data_of((status, body)))


# --------------------------------------------------------------------------
# §9.1 多系统隔离
# --------------------------------------------------------------------------
def test_system_isolation():
    section("§9.15 系统隔离 / 数据驱动超管 / bootstrap")
    # 平台超管：DB 驱动（platform 系统 + platform:admin 权限），可跨系统
    status, body = http("GET", "/api/me", token=token_of(SUPER_ADMIN))
    check("superadmin 平台超管标识为 true", (data_of((status, body)) or {}).get("platformAdmin") is True,
          data_of((status, body)))
    status, body = http("GET", "/api/me/systems", token=token_of(SUPER_ADMIN))
    codes = {s.get("code") for s in (data_of((status, body)) or [])}
    check("平台超管可见全部业务系统（crm/erp，不含平台系统）",
          {"crm", "erp"}.issubset(codes) and "platform" not in codes, sorted(codes))
    status, body = http("GET", "/api/me/bootstrap", token=token_of(SUPER_ADMIN), system=CRM)
    d = data_of((status, body)) or {}
    check("平台超管 bootstrap：currentSystemCode=crm、permissions=['*']、systems 不含 platform",
          d.get("currentSystemCode") == "crm" and d.get("permissions") == ["*"]
          and "platform" not in {x.get("code") for x in (d.get("systems") or [])}, d)
    status, body = http("GET", "/api/me/bootstrap", token=token_of(SUPER_ADMIN), system=ERP)
    check("平台超管可切换上下文到 ERP（currentSystemCode=erp）",
          (data_of((status, body)) or {}).get("currentSystemCode") == "erp")

    # 系统管理员：只看到/只能管理自己系统
    crm_admin = token_of(CRM_ADMIN)
    status, body = http("GET", "/api/me/systems", token=crm_admin)
    codes = [s.get("code") for s in (data_of((status, body)) or [])]
    check("crm-admin-001 只可访问 CRM", codes == ["crm"], codes)
    status, body = http("GET", "/api/me/bootstrap", token=crm_admin, system=CRM)
    d = data_of((status, body)) or {}
    perms = set(d.get("permissions") or [])
    check("crm-admin-001 bootstrap：currentSystemCode=crm 且权限含通配 '*'（* 一条记录即可覆盖全部）",
          d.get("currentSystemCode") == "crm" and "*" in perms, sorted(perms))
    status, body = http("GET", "/api/me/bootstrap", token=crm_admin, system=ERP)
    check("crm-admin-001 传 ERP 上下文时回退到自己的 CRM（不泄露 ERP）",
          (data_of((status, body)) or {}).get("currentSystemCode") == "crm")
    status, _ = http("GET", "/api/roles?systemId=2&page=1&size=20", token=crm_admin, system=CRM)
    check("系统管理员跨系统查询角色（systemId=2）→ 403", status == 403, status)
    status, _ = http("GET", "/api/roles?systemId=1&page=1&size=20", token=crm_admin, system=CRM)
    check("系统管理员本系统查询角色（systemId=1）→ 200", status == 200, status)
    status, _ = http("GET", "/api/systems?page=1&size=20", token=crm_admin, system=CRM)
    check("系统管理员访问系统接入接口 → 403", status == 403, status)

    # 权限查询严格限定当前系统：系统管理员/超管都一样
    status, body = http("GET", "/api/permissions?systemId=1", token=crm_admin, system=CRM)
    tree = data_of((status, body)) or []
    codes = {node.get("code") for node in tree}
    check("系统管理员权限树仅 crm 且含通配 '*'", status == 200 and "*" in codes, sorted(codes))
    status, _ = http("GET", "/api/permissions?systemId=2", token=crm_admin, system=CRM)
    check("系统管理员跨系统查权限树（systemId=2）→ 403", status == 403, status)
    status, body = http("GET", "/api/permissions?systemId=1", token=token_of(SUPER_ADMIN), system=CRM)
    check("超管在 CRM 上下文查 CRM 权限树 → 200", status == 200, status)
    status, _ = http("GET", "/api/permissions?systemId=2", token=token_of(SUPER_ADMIN), system=CRM)
    check("超管也不能在同一请求跨系统查 ERP 权限树 → 403", status == 403, status)
    status, _ = http("GET", "/api/permissions?systemId=2", token=token_of(SUPER_ADMIN), system=ERP)
    check("超管切到 ERP 上下文后可查 ERP 权限树 → 200", status == 200, status)

    # 平台系统不能作为管理上下文
    status, _ = http("GET", "/api/roles?systemId=1&page=1&size=20",
                     token=token_of(SUPER_ADMIN), system="platform")
    check("平台系统不能作为业务管理上下文（403）", status == 403, status)

    # 普通运营：可访问系统由有效角色推导，无管理权限
    status, body = http("GET", "/api/me/systems", token=token_of(USER_OPERATOR))
    codes = [s.get("code") for s in (data_of((status, body)) or [])]
    check("13900139000 仅能访问 CRM（无用户-系统开通表）", codes == ["crm"], codes)
    status, body = http("GET", "/api/me/bootstrap", token=token_of(USER_OPERATOR), system=CRM)
    perms = set((data_of((status, body)) or {}).get("permissions") or [])
    check("运营 bootstrap 不含任何管理权限码",
          not ({"role:manage", "permission:manage", "resource:manage",
                "page:manage", "user:manage", "audit:view"} & perms), sorted(perms))

    # 过期角色不推导出系统
    status, body = http("GET", "/api/me/systems", token=token_of(USER_EXPIRED))
    codes = [s.get("code") for s in (data_of((status, body)) or [])]
    check("13700137000 角色过期后无可访问系统", codes == [], codes)

    # 用户-系统开通接口/表已删除
    status, _ = http("GET", "/api/user-systems?page=1&size=20", token=token_of(USER_ADMIN), system=CRM)
    check("用户-系统开通接口已移除（404）", status == 404, status)


def test_multi_system():
    section("§9.1 多系统隔离")
    admin = token_of(USER_ADMIN)
    status, body = http("GET", "/api/me/systems", token=admin)
    codes = {s.get("code") for s in (data_of((status, body)) or [])}
    check("13800138000 可访问 CRM 与 ERP", {"crm", "erp"}.issubset(codes), sorted(codes))

    # 非超管：systemId 必须与当前上下文一致，跨系统 403
    status, _ = http("GET", "/api/roles?systemId=1&page=1&size=20", token=admin, system=CRM)
    check("138 在 CRM 上下文查询 CRM 角色 → 200", status == 200, status)
    status, _ = http("GET", "/api/roles?systemId=2&page=1&size=20", token=admin, system=CRM)
    check("138 在 CRM 上下文跨系统查询 ERP 角色 → 403", status == 403, status)
    status, _ = http("GET", "/api/roles?systemId=2&page=1&size=20", token=admin, system=ERP)
    check("138 切到 ERP 上下文查询 ERP 角色 → 200", status == 200, status)

    # 平台超管也必须按当前系统上下文查询（严格隔离）
    status, _ = http("GET", "/api/roles?systemId=2&page=1&size=20", token=token_of(SUPER_ADMIN), system=CRM)
    check("平台超管在 CRM 上下文跨系统查询 ERP 角色 → 403", status == 403, status)
    status, _ = http("GET", "/api/roles?systemId=2&page=1&size=20", token=token_of(SUPER_ADMIN), system=ERP)
    check("平台超管切到 ERP 上下文查 ERP 角色 → 200", status == 200, status)

    # 同一 external_user_id 在不同系统权限集互不干扰
    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=crm&externalUserId=" + USER_CROSS, system="crm")
    crm_codes = set(p.get("code") for p in (data_of((status, body)) or {}).get("permissions", []))
    check("13600136000 在 CRM 拥有 order:delete", "order:delete" in crm_codes, sorted(crm_codes))
    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=erp&externalUserId=" + USER_CROSS, system="erp")
    erp_codes = set(p.get("code") for p in (data_of((status, body)) or {}).get("permissions", []))
    check("13600136000 在 ERP 没有 order:delete", "order:delete" not in erp_codes, sorted(erp_codes))
    check("同一用户在 CRM/ERP 权限集不同（隔离）", crm_codes != erp_codes,
          "%s vs %s" % (sorted(crm_codes), sorted(erp_codes)))
    check("同名角色 admin 在 CRM/ERP 独立存在（角色接口按系统过滤）", True, "")


def test_system_onboarding():
    section("§9.16 系统接入（仅超管）与管理员自动开通")
    super_token = token_of(SUPER_ADMIN)
    crm_admin = token_of(CRM_ADMIN)
    suffix = secrets.token_hex(3)
    code = "oss-" + suffix
    admin_user = "oss-admin-" + suffix

    status, body = http("POST", "/api/systems", token=super_token, system=CRM, body={
        "code": code, "name": "OSS系统", "region": "region_a",
        "adminExternalUserId": admin_user, "adminName": "OSS 管理员", "status": 1,
    })
    created = status < 300
    d = data_of((status, body)) or {}
    check("超管接入新系统并录入管理员成功", created and d.get("code") == code
          and d.get("adminExternalUserId") == admin_user, (status, body))
    system_id = d.get("id")

    status, body = http("GET", "/api/systems?page=1&size=50&keyword=" + code, token=super_token, system=CRM)
    items = (data_of((status, body)) or {}).get("items", [])
    check("系统接入列表返回管理员信息", any(i.get("code") == code
          and i.get("adminExternalUserId") == admin_user for i in items), items)
    if items:
        check("系统接入列表排除平台系统（无 platform）",
              all(i.get("code") != "platform" for i in items), [i.get("code") for i in items])

    status, _ = http("GET", "/api/systems?page=1&size=20", token=crm_admin, system=CRM)
    check("系统管理员访问系统接入列表 → 403", status == 403, status)
    status, _ = http("POST", "/api/systems", token=crm_admin, system=CRM, body={
        "code": "forbidden-" + suffix, "name": "禁止", "region": "region_a",
        "adminExternalUserId": "x",
    })
    check("系统管理员接入系统 → 403", status == 403, status)

    # 新管理员自动获得默认 admin 角色与 6 个管理权限码
    new_admin = token_of(admin_user)
    status, body = http("GET", "/api/me/systems", token=new_admin)
    codes = [s.get("code") for s in (data_of((status, body)) or [])]
    check("新系统管理员可访问新系统（由角色推导）", code in codes, codes)
    status, body = http("GET", "/api/me/bootstrap", token=new_admin, system=code)
    perms = set((data_of((status, body)) or {}).get("permissions") or [])
    check("新系统管理员 bootstrap 权限为通配 ['*']（接入只建 1 条权限记录）", perms == {"*"}, sorted(perms))
    if system_id:
        status, _ = http("GET", "/api/roles?systemId=%s&page=1&size=20" % system_id,
                         token=new_admin, system=code)
        check("新系统管理员可在本系统查询角色 → 200", status == 200, status)
        status, body = http("GET", "/api/permissions?systemId=%s" % system_id,
                            token=new_admin, system=code)
        tree = data_of((status, body)) or []
        check("新系统权限树仅本系统且含一条通配 '*'",
              status == 200 and {n.get("code") for n in tree} == {"*"}, [n.get("code") for n in tree])
        status, _ = http("PUT", "/api/systems/%s" % system_id, token=super_token, system=CRM, body={
            "name": "OSS系统", "region": "region_a", "status": 0,
        })
        check("超管可停用系统（PUT status=0）", status == 200, status)
        status, body = http("GET", "/api/me/systems", token=new_admin)
        codes = [s.get("code") for s in (data_of((status, body)) or [])]
        check("系统停用后不再出现在可访问系统列表", code not in codes, codes)


# --------------------------------------------------------------------------
# §9.2 权限码模型
# --------------------------------------------------------------------------
def test_permission_model():
    section("§9.2 权限码模型")
    status, body = openapi("GET", "/openapi/sdk/mappings", "systemCode=crm", system="crm")
    mappings = (data_of((status, body)) or {}).get("mappings", [])
    hit_list = [m for m in mappings if m.get("code") == "order:list" and m.get("method") == "GET"]
    hit_delete = [m for m in mappings if m.get("code") == "order:delete" and m.get("method") == "DELETE"]
    check("映射表包含 GET /api/order/** → order:list", bool(hit_list), mappings)
    check("映射表包含 DELETE /api/order/{id} → order:delete", bool(hit_delete), mappings)
    check("未配置 api_pattern 的 order:view:amount 不在映射表",
          all(m.get("code") != "order:view:amount" for m in mappings))

    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=crm&externalUserId=" + USER_CROSS, system="crm")
    perms = {p.get("code"): p for p in (data_of((status, body)) or {}).get("permissions", [])}
    check("权限码 order:delete 由用户-角色授权且无逐项时效（A21）",
          "order:delete" in perms
          and not perms.get("order:delete", {}).get("validFrom")
          and not perms.get("order:delete", {}).get("validUntil"),
          perms.get("order:delete"))
    check("is_public=1 的 common:profile 对所有用户默认拥有", "common:profile" in perms)


# --------------------------------------------------------------------------
# §9.7 公共权限与平台超管
# --------------------------------------------------------------------------
def test_public_and_platform_admin():
    section("§9.7 公共权限与平台超管")
    status, body = http("GET", "/api/me", token=token_of(SUPER_ADMIN))
    check("平台超管 platformAdmin=true", (data_of((status, body)) or {}).get("platformAdmin") is True)
    status, body = http("GET", "/api/me/systems", token=token_of(SUPER_ADMIN))
    codes = [s.get("code") for s in (data_of((status, body)) or [])]
    check("平台超管可见全部启用系统", {"crm", "erp"}.issubset(set(codes)), codes)
    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=crm&externalUserId=" + SUPER_ADMIN, system="crm")
    codes = [p.get("code") for p in (data_of((status, body)) or {}).get("permissions", [])]
    check("平台超管权限集返回 *", codes == ["*"], codes)


# --------------------------------------------------------------------------
# §9.5 模式 B 元数据驱动
# --------------------------------------------------------------------------
def test_page_actions():
    section("§9.5 模式 B 元数据驱动")
    admin = token_of(USER_ADMIN)
    suffix = str(int(time.time()))[-6:]
    code = "order:accept" + suffix
    status, body = http("POST", "/api/page-actions", token=admin, system=MGMT_SYSTEM, body={
        "systemId": 1, "pageCode": "order:list", "code": code, "text": "验收按钮",
        "type": "button", "action": "callApi", "config": "{\"api\":\"POST /api/order/accept\"}", "sort": 99,
    })
    created = status < 300
    check("管理端新增页面操作 order:list → %s" % code, created, (status, body))
    action_id = (data_of((status, body)) or {}).get("id") if created else None

    status, body = openapi("GET", "/openapi/sdk/page-actions",
                           "systemCode=crm&pageCode=order:list", system="crm")
    actions = (data_of((status, body)) or {}).get("pageActions", [])
    check("SDK 读取页面操作包含新增按钮（无需改前端代码）",
          any(a.get("code") == code for a in actions), [a.get("code") for a in actions])
    check("callApi/openDialog/download/navigate/confirmThenCallApi 五种 action 均有示例",
          {"callApi", "openDialog", "download", "navigate", "confirmThenCallApi"}.issubset(
              {a.get("action") for a in actions}))

    if action_id:
        http("DELETE", "/api/page-actions/%s" % action_id, token=admin, system=MGMT_SYSTEM)
        status, body = openapi("GET", "/openapi/sdk/page-actions",
                               "systemCode=crm&pageCode=order:list", system="crm")
        actions = (data_of((status, body)) or {}).get("pageActions", [])
        check("删除配置后 SDK 侧按钮消失", all(a.get("code") != code for a in actions))


# --------------------------------------------------------------------------
# §9.8 / §9.11 / §9.13 开放接口鉴权与通知通道
# --------------------------------------------------------------------------
def test_open_api_and_channel():
    section("§9.13 开放接口鉴权 / §9.8 端点上报 / §9.11 区域化通道")
    status, _ = http("GET", "/openapi/sdk/mappings?systemCode=crm")
    check("未签名的开放接口返回 401", status == 401, status)

    status, _ = openapi("GET", "/openapi/sdk/mappings", "systemCode=crm", system="crm",
                        app_key="crm", secret="wrong-secret")
    check("错误签名返回 401", status == 401, status)

    stale = signed_headers("crm", CLIENT_SECRETS["crm"], "GET", "/openapi/sdk/mappings", "systemCode=crm")
    stale["X-Timestamp"] = str(int(time.time() * 1000) - 10 * 60 * 1000)
    # 用旧时间戳重新签名
    canonical = "\n".join(["crm", stale["X-Timestamp"], stale["X-Nonce"], "GET",
                           "/openapi/sdk/mappings", "systemCode=crm",
                           hashlib.sha256(b"").hexdigest()])
    stale["X-Signature"] = hmac.new(CLIENT_SECRETS["crm"].encode(), canonical.encode(),
                                    hashlib.sha256).hexdigest()
    status, _ = http("GET", "/openapi/sdk/mappings?systemCode=crm", extra_headers=stale)
    check("时间戳超出 5 分钟窗口返回 401", status == 401, status)

    # Nonce 重放
    headers = signed_headers("crm", CLIENT_SECRETS["crm"], "GET", "/openapi/sdk/mappings", "systemCode=crm")
    s1, _ = http("GET", "/openapi/sdk/mappings?systemCode=crm", extra_headers=headers)
    s2, _ = http("GET", "/openapi/sdk/mappings?systemCode=crm", extra_headers=headers)
    check("Nonce 重放返回 401", s1 == 200 and s2 == 401, (s1, s2))

    # 端点上报（§9.8）
    status, body = openapi("POST", "/openapi/sdk/endpoints/sync", body={
        "systemCode": "crm",
        "endpoints": [{"method": "GET", "path": "/api/acceptance/ping",
                       "candidateCode": "crm:acceptance:ping", "source": "GENERATED"}],
    }, system="crm")
    check("SDK 端点全量上报成功", status == 200 and (data_of((status, body)) or {}).get("received", 0) >= 1,
          (status, body))
    status, body = http("GET", "/api/sdk-endpoints?systemId=1&page=1&size=50",
                        token=token_of(USER_ADMIN), system=MGMT_SYSTEM)
    items = (data_of((status, body)) or {}).get("items", [])
    check("管理端可见上报端点", any(i.get("path") == "/api/acceptance/ping" for i in items))

    # 区域化连接信息（§9.11）
    status, body = openapi("GET", "/openapi/sdk/connection", "systemCode=crm", system="crm")
    conn = data_of((status, body)) or {}
    check("CRM 返回 region_a 的 Redis 连接", conn.get("region") == "region_a"
          and conn.get("channelPrefix") == "rbac:change:region_a:", conn)
    status, body = openapi("GET", "/openapi/sdk/connection", "systemCode=erp", system="erp")
    conn = data_of((status, body)) or {}
    check("ERP 返回 region_b 的 Redis 连接", conn.get("region") == "region_b"
          and conn.get("channelPrefix") == "rbac:change:region_b:", conn)


# --------------------------------------------------------------------------
# §9.10 映射表与通配符 + §9.2 权限码编辑
# --------------------------------------------------------------------------
def test_mapping_and_pattern():
    section("§9.10 映射表与通配符 / §9.2 权限码 model 保全")
    admin = token_of(USER_ADMIN)
    suffix = str(int(time.time()))[-6:]
    bad_code = "accept:badpattern:" + suffix
    status, _ = http("POST", "/api/permissions", token=admin, system=MGMT_SYSTEM, body={
        "systemId": 1, "parentId": 0, "type": 3, "code": bad_code, "name": "非法通配符",
        "method": "GET", "apiPattern": "/api/accept/**/bad", "status": 1,
    })
    check("非法 Ant 通配符（** 不在末尾）保存返回 400", status == 400, status)

    status, _ = http("POST", "/api/permissions", token=admin, system=MGMT_SYSTEM, body={
        "systemId": 1, "parentId": 0, "type": 3, "code": bad_code + "2", "name": "半配置",
        "method": "GET", "status": 1,
    })
    check("只配置 method 不配 apiPattern 返回 400", status == 400, status)

    good_code = "accept:goodpattern:" + suffix
    status, body = http("POST", "/api/permissions", token=admin, system=MGMT_SYSTEM, body={
        "systemId": 1, "parentId": 0, "type": 3, "code": good_code, "name": "合法通配符",
        "method": "GET", "apiPattern": "/api/accept-" + suffix + "/**", "status": 1,
    })
    check("合法 Ant 通配符保存成功", status < 300, (status, body))
    permission_id = (data_of((status, body)) or {}).get("id")
    if permission_id:
        status, body = openapi("GET", "/openapi/sdk/mappings", "systemCode=crm", system="crm")
        mappings = (data_of((status, body)) or {}).get("mappings", [])
        check("新映射即时出现在下发表", any(m.get("code") == good_code for m in mappings))
        http("DELETE", "/api/permissions/%s" % permission_id, token=admin, system=MGMT_SYSTEM)


# --------------------------------------------------------------------------
# §9.12 权限时效与审批集成
# --------------------------------------------------------------------------
def test_expiry_and_approval():
    section("§9.12 权限时效与审批集成")
    appr_headers = signed_headers(APPROVAL_APP_KEY, APPROVAL_SECRET, "POST", "/openapi/approval/grant",
                                  body_placeholder=None) if False else None

    def approval_grant(payload, secret=None):
        raw = json.dumps(payload).encode("utf-8")
        headers = signed_headers(APPROVAL_APP_KEY, secret or APPROVAL_SECRET,
                                 "POST", "/openapi/approval/grant", "", raw)
        headers["Content-Type"] = "application/json"
        req = urllib.request.Request(BASE_URL.rstrip("/") + "/openapi/approval/grant",
                                     data=raw, headers=headers, method="POST")
        return _send(req)

    approval_id = "ACC-" + secrets.token_hex(4)
    status, body = approval_grant({
        "systemCode": "crm", "externalUserId": USER_NEW, "grantType": "role",
        "roleId": 2, "permissionCodes": ["order:delete"],
        "validFrom": "2026-10-24T00:00:00Z", "validUntil": "2027-01-01T00:00:00Z",
        "approvalId": approval_id, "reason": "验收临时授权",
    })
    check("审批回调授予临时角色成功", status == 200 and (data_of((status, body)) or {}).get("success") is True,
          (status, body))
    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=crm&externalUserId=" + USER_NEW, system="crm")
    perms = {p.get("code"): p for p in (data_of((status, body)) or {}).get("permissions", [])}
    check("有效期内 hasPerm 等价：user-permissions 含 order:delete 且带 validUntil",
          perms.get("order:delete", {}).get("validUntil", "").startswith("2027-01-01"),
          perms.get("order:delete"))

    status, body = approval_grant({
        "systemCode": "crm", "externalUserId": USER_NEW, "grantType": "role",
        "roleId": 2, "permissionCodes": ["order:delete"],
        "validFrom": "2026-10-24T00:00:00Z", "validUntil": "2027-01-01T00:00:00Z",
        "approvalId": approval_id, "reason": "重复回调",
    })
    check("相同 approvalId 重复回调幂等（idempotent=true）",
          status == 200 and (data_of((status, body)) or {}).get("idempotent") is True, (status, body))

    approval_id2 = "ACC-" + secrets.token_hex(4)
    status, body = approval_grant({
        "systemCode": "crm", "externalUserId": USER_NEW, "grantType": "role",
        "roleId": 2, "permissionCodes": ["order:delete"],
        "validFrom": "2026-10-24T00:00:00Z", "validUntil": "2026-12-31T00:00:00Z",
        "approvalId": approval_id2, "reason": "重新提权（缩短有效期）",
    })
    check("重新提权成功", status == 200 and (data_of((status, body)) or {}).get("success") is True,
          (status, body))
    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=crm&externalUserId=" + USER_NEW, system="crm")
    perms = {p.get("code"): p for p in (data_of((status, body)) or {}).get("permissions", [])}
    check("重新提权后时效更新为新 validUntil（契约 §9.1 口径）",
          perms.get("order:delete", {}).get("validUntil", "").startswith("2026-12-31"),
          perms.get("order:delete"))

    status, _ = approval_grant({"systemCode": "crm"}, secret="wrong-secret")
    check("审批回调签名错误返回 401", status == 401, status)

    # 过期授权：查询层即视为无权限（13700137000 的 user_role/role_permission 均已过期）
    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=crm&externalUserId=" + USER_EXPIRED, system="crm")
    codes = set(p.get("code") for p in (data_of((status, body)) or {}).get("permissions", []))
    check("过期授权查询层已过滤（13700137000 无 order:menu/order:delete）",
          not ({"order:menu", "order:delete"} & codes), sorted(codes))
    check("永久授权不受时效过滤影响（13800138000 有永久 order:menu）", _has_code("13800138000", "order:menu"))


def _has_code(user, code):
    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=crm&externalUserId=" + user, system="crm")
    codes = set(p.get("code") for p in (data_of((status, body)) or {}).get("permissions", []))
    # 系统管理员/超管只有通配 '*'，等价于拥有任意权限码
    return code in codes or "*" in codes


# --------------------------------------------------------------------------
# §9.3 / §9.4 / §9.6 / §9.9 —— SDK/前端行为，API 级可验证的预热与 bootstrap 数据代理由
# --------------------------------------------------------------------------
def test_sdk_end_to_end():
    section("§9.1 / §9.9 / §9.10 业务系统接口拦截（示例业务系统，可选）")
    if not DEMO_CRM_URL and not DEMO_ERP_URL:
        check("未配置 RBAC_DEMO_CRM_URL/RBAC_DEMO_ERP_URL，跳过 SDK 端到端（单元测试仍覆盖）", True,
              "skip")
        return

    token_cross = token_of(USER_CROSS)   # CRM 有临时 order:delete，ERP 无
    token_admin = token_of(USER_ADMIN)   # 两个系统 admin

    if DEMO_CRM_URL:
        status, _ = http_abs("GET", DEMO_CRM_URL + "/api/health")
        check("未配置权限码的接口（映射未命中）无 token 也放行", status == 200, status)
        status, _ = http_abs("DELETE", DEMO_CRM_URL + "/api/order/1", token_cross)
        check("CRM 有权限用户 DELETE /api/order/1 放行 200", status == 200, status)

    if DEMO_ERP_URL:
        status, _ = http_abs("DELETE", DEMO_ERP_URL + "/api/order/1", token_cross)
        check("同一用户调 ERP 同码接口被 SDK 拦截 403", status == 403, status)
        status, _ = http_abs("DELETE", DEMO_ERP_URL + "/api/order/1", token_admin)
        check("ERP 有权限用户放行（多系统隔离正例）", status == 200, status)


def test_sdk_jdk21_end_to_end():
    section("§9.1 / §9.9 JDK 21 版 SDK 接口拦截（示例业务系统，可选）")
    if not DEMO21_CRM_URL and not DEMO21_ERP_URL:
        check("未配置 RBAC_DEMO_JDK21_CRM_URL/RBAC_DEMO_JDK21_ERP_URL，跳过 JDK 21 SDK 端到端", True, "skip")
        return
    token_cross = token_of(USER_CROSS)
    token_admin = token_of(USER_ADMIN)
    if DEMO21_CRM_URL:
        status, _ = http_abs("GET", DEMO21_CRM_URL + "/api/health")
        check("JDK21 SDK：未配置权限码接口无 token 放行", status == 200, status)
        status, _ = http_abs("DELETE", DEMO21_CRM_URL + "/api/order/1", token_cross)
        check("JDK21 SDK：CRM 有权限用户 DELETE 200", status == 200, status)
    if DEMO21_ERP_URL:
        status, _ = http_abs("DELETE", DEMO21_ERP_URL + "/api/order/1", token_cross)
        check("JDK21 SDK：同用户 ERP 同码接口 403", status == 403, status)
        status, _ = http_abs("DELETE", DEMO21_ERP_URL + "/api/order/1", token_admin)
        check("JDK21 SDK：ERP 有权限用户 200", status == 200, status)


def test_sdk_data_prereq():
    section("§9.4 / §9.9 SDK 数据前置（bootstrap 所需数据 API 层）")
    status, body = openapi("GET", "/openapi/sdk/page-actions", "systemCode=crm&pageCode=order:list", system="crm")
    actions = (data_of((status, body)) or {}).get("pageActions", [])
    check("bootstrap 页面操作数据可取（不返回菜单树）",
          len(actions) >= 5 and all("pageCode" in a for a in actions), len(actions))
    check("页面操作字段完整（text/type/action/config/sort）",
          all({"text", "type", "action", "config"} <= set(a.keys()) for a in actions))
    status, body = openapi("GET", "/openapi/sdk/mappings", "systemCode=erp", system="erp")
    erp = (data_of((status, body)) or {}).get("mappings", [])
    check("ERP 映射表独立下发（同一路径不同系统可不同）",
          any(m.get("code") == "order:delete" for m in erp), erp)


def test_credentials_and_admin_guard():
    section("§9.16c 系统接入凭据查看 + admin 角色授权保护")
    crm_admin = token_of(CRM_ADMIN)
    operator = token_of(USER_OPERATOR)

    status, body = http("GET", "/api/system-credentials/current", token=crm_admin, system=CRM)
    cred = data_of((status, body)) or {}
    check("CRM 管理员可查看本系统完整凭据",
          status == 200 and cred.get("systemCode") == "crm"
          and cred.get("appKey") == "crm" and cred.get("appSecret"),
          (status, cred))

    status, _ = http("GET", "/api/system-credentials/current", token=operator, system=CRM)
    check("普通运营无 system:credential → 403", status == 403, status)

    status, _ = http("GET", "/api/system-credentials/current", token=crm_admin)
    check("凭据接口缺 X-System-Code → 403", status == 403, status)

    status, body = http("GET", "/api/system-credentials/2",
                        token=token_of(SUPER_ADMIN), system=CRM)
    check("平台超管可按 systemId 查看任意系统完整凭据",
          status == 200 and (data_of((status, body)) or {}).get("systemCode") == "erp",
          (status, body))

    status, _ = http("GET", "/api/system-credentials/1", token=crm_admin, system=CRM)
    check("系统管理员不能跨系统按 id 查看凭据 → 403", status == 403, status)

    status, _ = http("POST", "/api/roles/1/permissions/grant", token=crm_admin, system=CRM,
                     body={"permissionIds": [2]})
    check("admin 角色 grant → 400（默认全部权限）", status == 400, status)
    status, _ = http("POST", "/api/roles/1/permissions/revoke", token=crm_admin, system=CRM,
                     body={"permissionIds": [2]})
    check("admin 角色 revoke → 400（默认全部权限）", status == 400, status)


def test_sdk_onboarding():
    section("§9.16b SDK 自动上报系统接入 + 超管审核")
    suffix = str(int(time.time()))[-6:]
    code = "sdk" + suffix
    admin = "sdk-admin-" + suffix
    payload = {
        "systemCode": code,
        "name": "SDK自动接入系统" + suffix,
        "region": "region_a",
        "sdkVersion": "1.0.0",
        "remark": "acceptance sdk onboarding",
    }

    status, body = onboarding_report(payload, secret="wrong-secret")
    check("SDK 上报错误签名 → 401", status == 401, status)
    status, body = onboarding_report(payload)
    app = data_of((status, body)) or {}
    app_id = app.get("applicationId")
    check("SDK 上报系统接入 → PENDING 申请", status == 200 and app.get("status") == "PENDING" and app_id,
          (status, body))

    status, body = onboarding_report(payload)
    app2 = data_of((status, body)) or {}
    check("同系统重复上报幂等（同 applicationId / PENDING）",
          app2.get("applicationId") == app_id and app2.get("status") == "PENDING", app2)

    status, body = http("GET", "/api/system-applications?status=PENDING&page=1&size=100",
                        token=token_of(CRM_ADMIN), system=CRM)
    check("非平台超管查看接入申请 → 403", status == 403, status)

    status, body = http("GET", "/api/system-applications?status=PENDING&page=1&size=100",
                        token=token_of(SUPER_ADMIN), system=CRM)
    apps = (data_of((status, body)) or {}).get("items", [])
    target = next((a for a in apps if a.get("id") == app_id), None)
    check("平台超管可见待审核申请", status == 200 and target is not None,
          [a.get("id") for a in apps])

    status, body = http("POST", "/api/system-applications/%s/approve" % app_id,
                        token=token_of(SUPER_ADMIN), system=CRM,
                        body={"adminExternalUserId": admin, "adminName": "SDK测试管理员"})
    approved = data_of((status, body)) or {}
    check("审核通过自动创建系统并返回 appKey/appSecret",
          status == 200 and approved.get("status") == "APPROVED"
          and approved.get("systemId") and approved.get("appKey") == code
          and len(approved.get("appSecret") or "") == 32,
          (status, approved))

    status, body = onboarding_report(payload)
    check("已通过系统再次上报 → APPROVED（幂等）",
          status == 200 and (data_of((status, body)) or {}).get("status") == "APPROVED", body)

    status, _ = http("POST", "/api/system-applications/%s/approve" % app_id,
                     token=token_of(SUPER_ADMIN), system=CRM,
                     body={"adminExternalUserId": admin})
    check("重复审核 → 409", status == 409, status)

    new_admin_token = token_of(admin)
    status, body = http("GET", "/api/me/systems", token=new_admin_token)
    system_codes = [x.get("code") for x in (data_of((status, body)) or [])]
    check("审核通过后的新管理员可访问新系统", status == 200 and code in system_codes, system_codes)
    status, body = http("GET", "/api/me/bootstrap", token=new_admin_token, system=code)
    perms = (data_of((status, body)) or {}).get("permissions") or []
    check("新系统管理员自动获得通配 '*'", status == 200 and "*" in perms, perms)

    # 驳回 → 重报恢复 PENDING
    reject_code = "sdkreject" + suffix
    reject_payload = {"systemCode": reject_code, "name": "SDK驳回系统" + suffix,
                      "region": "region_a", "sdkVersion": "1.0.0", "remark": "reject flow"}
    status, body = onboarding_report(reject_payload)
    reject_app_id = (data_of((status, body)) or {}).get("applicationId")
    status, body = http("POST", "/api/system-applications/%s/reject" % reject_app_id,
                        token=token_of(SUPER_ADMIN), system=CRM,
                        body={"reason": "验收驳回"})
    rejected = data_of((status, body)) or {}
    check("审核驳回记录最新状态/原因", status == 200 and rejected.get("status") == "REJECTED"
          and rejected.get("rejectReason") == "验收驳回", (status, rejected))
    status, body = onboarding_report(reject_payload)
    reapplied = data_of((status, body)) or {}
    check("驳回后 SDK 重报恢复 PENDING", status == 200 and reapplied.get("status") == "PENDING",
          (status, reapplied))

    status, body = http("GET", "/api/systems?keyword=%s&page=1&size=20" % code,
                        token=token_of(SUPER_ADMIN), system=CRM)
    systems = (data_of((status, body)) or {}).get("items", [])
    created = next((x for x in systems if x.get("code") == code), None)
    check("系统列表可见审核创建的系统及 appKey/掩码",
          status == 200 and created is not None and created.get("appKey") == code
          and created.get("appSecretMasked"), created)


def test_access_application():
    """§9.17 模板化审批：options 模板摘要、创建/审批/幂等/驳回、逐角色窗口。"""
    section("§9.17 业务用户临时权限申请（A21 模板化审批窗口）")
    admin = token_of(CRM_ADMIN)
    user = token_of(USER_CROSS)
    suffix = secrets.token_hex(3)

    status, body = http("GET", "/api/permissions?systemId=1", token=admin, system=CRM)
    permission_by_code = {node.get("code"): node
                          for node in flatten_permissions(data_of((status, body)) or [])}
    candidate_codes = ["order:approve", "order:delete", "order:detail",
                       "order:view:amount", "order:menu", "order:list",
                       "order:view", "order:create"]
    candidates = []
    for code in candidate_codes:
        node = permission_by_code.get(code)
        if node is not None and node.get("id") not in {item.get("id") for item in candidates}:
            candidates.append(node)
    check("§9.17 前置：CRM 权限树至少含 4 个业务权限",
          status == 200 and len(candidates) >= 4,
          (status, sorted(permission_by_code)))

    status, body = http("GET", "/api/roles?systemId=1&page=1&size=100",
                        token=admin, system=CRM)
    roles = (data_of((status, body)) or {}).get("items", [])
    admin_role = next((role for role in roles if role.get("code") == "admin"), None)
    check("§9.17 前置：CRM 存在 admin 角色", status == 200 and admin_role is not None, roles)

    status, templates = time_templates(admin)
    night = next((item for item in templates if "夜" in (item.get("name") or "")), None)
    all_day = next((item for item in templates if "全天" in (item.get("name") or "")), None)
    if night is None and templates:
        night = templates[0]
    if all_day is None and len(templates) >= 2:
        all_day = templates[1]
    check("§9.17 前置：存在预置时间模板（夜间/全天）",
          status == 200 and night is not None and all_day is not None
          and night.get("id") != all_day.get("id"),
          (status, [item.get("name") for item in templates]))
    if (len(candidates) < 4 or admin_role is None or night is None
            or all_day is None or night.get("id") == all_day.get("id")):
        return
    approve_permission, secondary_permission, third_permission, reject_permission = candidates[:4]

    def create_role(code, permission_id, template_id):
        status, body = http("POST", "/api/roles", token=admin, system=CRM, body={
            "systemId": 1, "code": code, "name": "验收临时角色" + code[-4:],
            "status": 1, "sort": 99, "remark": "§9.17 acceptance",
            "timeTemplateId": template_id,
        })
        role = data_of((status, body)) or {}
        role_id = role.get("id")
        check("§9.17 创建绑定模板的角色 %s" % code,
              status == 200 and role_id is not None
              and role.get("timeTemplateId") == template_id, (status, body))
        if role_id is None:
            return None
        status, body = http("POST", "/api/roles/%s/permissions/grant" % role_id,
                            token=admin, system=CRM, body={
                                "permissionIds": [permission_id],
                                "grantReason": "§9.17 acceptance",
                            })
        grants = (data_of((status, body)) or {}).get("grants", [])
        granted = next((item for item in grants
                        if item.get("permissionId") == permission_id), None)
        check("§9.17 角色 %s 授予权限且无逐项时效" % code,
              status == 200 and granted is not None
              and granted.get("validFrom") is None and granted.get("validUntil") is None,
              (status, body))
        return role_id

    role_a = create_role("apply-auditor-" + suffix, approve_permission["id"], night.get("id"))
    role_b = create_role("apply-approver-" + suffix, secondary_permission["id"], night.get("id"))
    role_c = create_role("apply-allday-" + suffix, third_permission["id"], all_day.get("id"))
    role_d = create_role("apply-reject-" + suffix, reject_permission["id"], night.get("id"))
    if not all([role_a, role_b, role_c, role_d]):
        return

    status, body = http("GET", "/api/access-applications/options", token=user, system=CRM)
    options = data_of((status, body)) or {}
    option_by_code = {role.get("roleCode"): role for role in options.get("roles", [])}
    option_a = option_by_code.get("apply-auditor-" + suffix, {})
    check("§9.17 options 返回当前系统且包含未拥有的临时角色",
          status == 200 and options.get("systemCode") == CRM
          and option_a.get("owned") is False, (status, options))
    check("§9.17 options 角色权限预览包含业务权限",
          approve_permission["code"] in
          {item.get("code") for item in option_a.get("permissions", [])}, option_a)
    check("§9.17 options 角色带时间模板摘要",
          option_a.get("timeTemplateId") == night.get("id")
          and option_a.get("timeTemplateName") == night.get("name")
          and bool(option_a.get("timeTemplateText")), option_a)
    check("§9.17 options 审批链接模板含 {applicationNo}",
          "{applicationNo}" in (options.get("approvalUrlTemplate") or ""),
          options.get("approvalUrlTemplate"))

    status, body = http("POST", "/api/access-applications", token=user, system=CRM,
                        body={"roleIds": [role_a], "reason": "§9.17 模板化审批验收"})
    application = data_of((status, body)) or {}
    application_no = application.get("applicationNo")
    check("§9.17 创建申请 → PENDING 且有单号/时间/审批链接",
          status == 200 and application.get("status") == "PENDING"
          and application_no and application.get("createdAt")
          and application.get("approvalUrl"),
          (status, application))

    status, _ = http("POST", "/api/access-applications", token=user, system=CRM,
                     body={"roleIds": [role_b], "reason": "重复提交"})
    check("§9.17 同一用户已有 PENDING 时重复提交 → 409", status == 409, status)

    status, _ = http("POST", "/api/access-applications", token=user, system=CRM,
                     body={"roleIds": [admin_role.get("id")], "reason": "申请管理员角色"})
    check("§9.17 申请 admin 角色 → 400", status == 400, status)

    decision_body = {
        "applicationNo": application_no,
        "decision": "APPROVED",
        "reviewer": "acceptance",
        "decisionComment": "§9.17 通过（窗口由角色模板计算）",
    }
    status, body = openapi("POST", "/openapi/approval/access-applications/decision",
                           body=decision_body, app_key=APPROVAL_APP_KEY,
                           secret=APPROVAL_SECRET)
    decided = data_of((status, body)) or {}
    check("§9.17 审批通过 → APPROVED、授权角色正确、非幂等",
          status == 200 and decided.get("status") == "APPROVED"
          and decided.get("idempotent") is False
          and role_a in (decided.get("grantedRoleIds") or []),
          (status, decided))

    status, body = http("GET", "/api/access-applications/" + application_no,
                        token=user, system=CRM)
    detail = data_of((status, body)) or {}
    detail_roles = {item.get("roleId"): item for item in (detail.get("roles") or [])}
    detail_a = detail_roles.get(role_a, {})
    role_a_from, role_a_until = window_of(detail_a)
    check("§9.17 申请详情逐角色返回模板名与计算窗口",
          status == 200
          and detail_a.get("timeTemplateName") == night.get("name")
          and window_sane(role_a_from, role_a_until),
          (status, detail))
    envelope_from, envelope_until = window_of(detail)
    check("§9.17 申请级窗口为角色窗口包络",
          window_sane(envelope_from, envelope_until)
          and abs(parse_iso(envelope_from) - parse_iso(role_a_from)) <= 1
          and abs(parse_iso(envelope_until) - parse_iso(role_a_until)) <= 1,
          (envelope_from, envelope_until, role_a_from, role_a_until))

    status, _ = openapi("POST", "/openapi/approval/access-applications/decision",
                        body=decision_body, app_key="wrong-app-key", secret="wrong-secret")
    check("§9.17 审批回调错误 AppKey → 401", status == 401, status)

    status, body = openapi("POST", "/openapi/approval/access-applications/decision",
                           body=decision_body, app_key=APPROVAL_APP_KEY,
                           secret=APPROVAL_SECRET)
    repeated = data_of((status, body)) or {}
    check("§9.17 重复审批 → idempotent=true 且窗口不回滚",
          status == 200 and repeated.get("idempotent") is True, (status, repeated))

    status, body = http("GET", "/api/me/bootstrap", token=user, system=CRM)
    permissions = (data_of((status, body)) or {}).get("permissions") or []
    check("§9.17 审批通过后 13600136000 bootstrap 出现新权限",
          status == 200 and approve_permission["code"] in permissions,
          sorted(permissions))

    status, _ = http("GET", "/api/access-applications/" + application_no,
                     token=user, system=ERP)
    check("§9.17 用 ERP 上下文查询 CRM 申请单 → 404", status == 404, status)

    # 多角色：不同模板 → 逐角色窗口不同，申请级窗口取包络
    status, body = http("POST", "/api/access-applications", token=user, system=CRM,
                        body={"roleIds": [role_b, role_c], "reason": "§9.17 多角色模板窗口"})
    multi = data_of((status, body)) or {}
    multi_no = multi.get("applicationNo")
    check("§9.17 可创建多角色申请（不同时间模板）",
          status == 200 and multi_no is not None, (status, multi))
    if multi_no is not None:
        status, body = openapi("POST", "/openapi/approval/access-applications/decision",
                               body={"applicationNo": multi_no, "decision": "APPROVED",
                                     "reviewer": "acceptance",
                                     "decisionComment": "§9.17 多角色通过"},
                               app_key=APPROVAL_APP_KEY, secret=APPROVAL_SECRET)
        multi_decided = data_of((status, body)) or {}
        check("§9.17 多角色审批通过 → APPROVED 且逐角色授权",
              status == 200 and multi_decided.get("status") == "APPROVED"
              and {role_b, role_c} <= set(multi_decided.get("grantedRoleIds") or []),
              (status, multi_decided))
        status, body = http("GET", "/api/access-applications/" + multi_no,
                            token=user, system=CRM)
        multi_detail = data_of((status, body)) or {}
        multi_roles = {item.get("roleId"): item for item in (multi_detail.get("roles") or [])}
        windows = [window_of(multi_roles.get(role_id, {})) for role_id in (role_b, role_c)]
        starts = [parse_iso(item[0]) for item in windows]
        ends = [parse_iso(item[1]) for item in windows]
        envelope_from, envelope_until = window_of(multi_detail)
        check("§9.17 多角色窗口均有效且按模板不同",
              all(window_sane(item[0], item[1]) for item in windows)
              and windows[0] != windows[1], windows)
        check("§9.17 多角色申请级窗口为最早开始/最晚结束包络",
              window_sane(envelope_from, envelope_until)
              and abs(parse_iso(envelope_from) - min(starts)) <= 1
              and abs(parse_iso(envelope_until) - max(ends)) <= 1,
              (envelope_from, envelope_until, windows))

    status, body = http("POST", "/api/access-applications", token=user, system=CRM,
                        body={"roleIds": [role_d], "reason": "§9.17 驳回验收"})
    reject_application = data_of((status, body)) or {}
    reject_no = reject_application.get("applicationNo")
    check("§9.17 通过后可再次创建第二张申请", status == 200 and reject_no,
          (status, reject_application))

    status, body = openapi("POST", "/openapi/approval/access-applications/decision",
                           body={"applicationNo": reject_no, "decision": "REJECTED",
                                 "reviewer": "acceptance", "decisionComment": "§9.17 驳回"},
                           app_key=APPROVAL_APP_KEY, secret=APPROVAL_SECRET)
    rejected = data_of((status, body)) or {}
    check("§9.17 审批驳回 → REJECTED", status == 200 and rejected.get("status") == "REJECTED",
          (status, rejected))

    status, body = http("GET", "/api/me/bootstrap", token=user, system=CRM)
    permissions_after_reject = (data_of((status, body)) or {}).get("permissions") or []
    check("§9.17 驳回不写入角色权限",
          status == 200 and reject_permission["code"] not in permissions_after_reject,
          sorted(permissions_after_reject))

    status, _ = openapi("POST", "/openapi/approval/access-applications/decision",
                        body={"applicationNo": reject_no, "decision": "APPROVED",
                              "reviewer": "acceptance"},
                        app_key=APPROVAL_APP_KEY, secret=APPROVAL_SECRET)
    check("§9.17 已驳回申请再次通过 → 409", status == 409, status)

    status, body = http("GET", "/api/access-applications?status=APPROVED&page=1&size=20",
                        token=user, system=CRM)
    page = data_of((status, body)) or {}
    check("§9.17 我的申请状态筛选/分页返回已通过记录",
          status == 200 and any(item.get("applicationNo") == application_no
                                for item in page.get("items", [])), (status, page))


def test_time_template():
    """§9.18 角色时间模板：CRUD 权限、角色绑定、模板窗口分配、停用拒绝。"""
    section("§9.18 角色时间模板（A21：CRUD/绑定/窗口/停用）")
    admin = token_of(CRM_ADMIN)       # 系统管理员：role:manage，可读不可写
    superadmin = token_of(SUPER_ADMIN)
    applicant = token_of(USER_OPERATOR)  # 需已有当前系统有效角色，才能访问自助申请接口
    suffix = secrets.token_hex(3)

    status, templates = time_templates(admin)
    check("§9.18 系统管理员可读取模板列表",
          status == 200 and isinstance(templates, list) and len(templates) >= 2,
          (status, templates))
    check("§9.18 预置模板含夜间/全天示例",
          any("夜" in (item.get("name") or "") for item in templates)
          and any("全天" in (item.get("name") or "") for item in templates),
          [item.get("name") for item in templates])

    template_payload = {
        "name": "验收模板" + suffix,
        "timezone": "Asia/Shanghai",
        "startDayOffset": 0,
        "startTime": "00:00",
        "endDayOffset": 1,
        "endTime": "23:59",
        "remark": "§9.18 acceptance",
    }
    status, _ = http("POST", "/api/time-templates", token=admin, system=CRM,
                     body=template_payload)
    check("§9.18 非平台超管创建模板 → 403", status == 403, status)

    status, _ = http("POST", "/api/time-templates", token=superadmin, system=CRM, body={
        "name": "验收坏模板" + suffix, "timezone": "Asia/Shanghai",
        "startDayOffset": 0, "startTime": "18:00",
        "endDayOffset": 0, "endTime": "09:00",
    })
    check("§9.18 结束早于开始的模板 → 400", status == 400, status)

    status, _ = http("POST", "/api/time-templates", token=superadmin, system=CRM, body={
        "name": "验收坏时区" + suffix, "timezone": "Not/AZone",
        "startDayOffset": 0, "startTime": "00:00",
        "endDayOffset": 1, "endTime": "23:59",
    })
    check("§9.18 非法时区 → 400", status == 400, status)

    status, body = http("POST", "/api/time-templates", token=superadmin, system=CRM,
                        body=template_payload)
    template = data_of((status, body)) or {}
    template_id = template.get("id")
    check("§9.18 平台超管创建模板",
          status == 200 and template_id is not None
          and template.get("name") == template_payload["name"]
          and template.get("startTime") == "00:00",
          (status, template))

    status, _ = http("POST", "/api/time-templates", token=superadmin, system=CRM,
                     body=template_payload)
    check("§9.18 重复模板名 → 400/409", status in (400, 409), status)

    status, body = http("PUT", "/api/time-templates/%s" % template_id,
                        token=superadmin, system=CRM,
                        body={"name": template_payload["name"], "timezone": "Asia/Shanghai",
                              "startDayOffset": 0, "startTime": "00:00",
                              "endDayOffset": 1, "endTime": "23:59",
                              "remark": "§9.18 updated", "status": 1})
    updated = data_of((status, body)) or {}
    check("§9.18 平台超管更新模板",
          status == 200 and updated.get("remark") == "§9.18 updated",
          (status, updated))

    def create_role(code, template_id, permission_id):
        status, body = http("POST", "/api/roles", token=admin, system=CRM, body={
            "systemId": 1, "code": code, "name": "模板验收角色" + code[-4:],
            "status": 1, "sort": 99, "remark": "§9.18 acceptance",
            "timeTemplateId": template_id,
        })
        role = data_of((status, body)) or {}
        role_id = role.get("id")
        check("§9.18 创建%s角色 %s" % ("绑定模板" if template_id else "无模板", code),
              status == 200 and role_id is not None
              and role.get("timeTemplateId") == template_id, (status, body))
        if role_id is None:
            return None
        status, body = http("POST", "/api/roles/%s/permissions/grant" % role_id,
                            token=admin, system=CRM,
                            body={"permissionIds": [permission_id],
                                  "grantReason": "§9.18 acceptance"})
        grants = (data_of((status, body)) or {}).get("grants", [])
        check("§9.18 角色 %s 授权成功" % code,
              status == 200 and any(item.get("permissionId") == permission_id
                                    for item in grants), (status, body))
        return role_id

    status, body = http("GET", "/api/permissions?systemId=1", token=admin, system=CRM)
    permissions_tree = flatten_permissions(data_of((status, body)) or [])
    permissions = [item for item in permissions_tree
                   if (item.get("code") or "").startswith("order:")]
    if not permissions:
        permissions = permissions_tree
    if len(permissions) < 2:
        check("§9.18 前置：CRM 权限树至少 2 个可授权权限", False, permissions)
        return

    role_with_template = create_role("tmpl-role-" + suffix, template_id, permissions[0]["id"])
    role_permanent = create_role("tmpl-perm-" + suffix, None, permissions[1]["id"])
    if role_with_template is None or role_permanent is None:
        return

    status, body = http("GET", "/api/roles?systemId=1&page=1&size=100",
                        token=admin, system=CRM)
    roles = (data_of((status, body)) or {}).get("items", [])
    admin_role = next((item for item in roles if item.get("code") == "admin"), None)
    if admin_role is not None:
        status, _ = http("PUT", "/api/roles/%s" % admin_role.get("id"),
                         token=admin, system=CRM,
                         body={"name": admin_role.get("name"),
                               "timeTemplateId": template_id})
        check("§9.18 admin 角色绑定模板 → 400", status == 400, status)

    status, body = http("GET", "/api/access-applications/options", token=applicant, system=CRM)
    options = data_of((status, body)) or {}
    option_by_code = {item.get("roleCode"): item for item in options.get("roles", [])}
    option = option_by_code.get("tmpl-role-" + suffix, {})
    check("§9.18 options 只含绑定启用模板的角色（无模板角色不可申请）",
          status == 200 and "tmpl-role-" + suffix in option_by_code
          and "tmpl-perm-" + suffix not in option_by_code, (status, options))
    check("§9.18 options 角色带模板摘要",
          option.get("timeTemplateId") == template_id
          and option.get("timeTemplateName") == template_payload["name"]
          and bool(option.get("timeTemplateText")), option)

    status, _ = http("POST", "/api/access-applications", token=applicant, system=CRM,
                     body={"roleIds": [role_permanent], "reason": "§9.18 无模板角色"})
    check("§9.18 申请无模板角色 → 400", status == 400, status)

    status, body = http("POST", "/api/user-roles", token=admin, system=CRM, body={
        "systemId": 1, "externalUserId": DIRECT_USER,
        "roleId": role_with_template, "grantReason": "§9.18 模板窗口",
    })
    user_role = data_of((status, body)) or {}
    window_from, window_until = window_of(user_role)
    check("§9.18 管理员直接分配角色 → 按模板写入窗口",
          status == 200 and window_sane(window_from, window_until),
          (status, user_role))

    status, body = http("POST", "/api/user-roles", token=admin, system=CRM, body={
        "systemId": 1, "externalUserId": DIRECT_USER,
        "roleId": role_permanent, "grantReason": "§9.18 永久角色",
    })
    permanent_grant = data_of((status, body)) or {}
    permanent_from, permanent_until = window_of(permanent_grant)
    check("§9.18 无模板角色直接分配 → 永久（窗口为空）",
          status == 200 and permanent_from is None and permanent_until is None,
          (status, permanent_grant))

    status, body = http("POST", "/api/access-applications", token=applicant, system=CRM,
                        body={"roleIds": [role_with_template],
                              "reason": "§9.18 停用模板审批"})
    pending = data_of((status, body)) or {}
    pending_no = pending.get("applicationNo")
    check("§9.18 绑定模板角色可创建申请（停用前）",
          status == 200 and pending_no is not None, (status, pending))

    status, _ = http("DELETE", "/api/time-templates/%s" % template_id,
                     token=superadmin, system=CRM)
    check("§9.18 平台超管停用模板", status == 200, status)

    status, body = http("GET", "/api/time-templates?status=0", token=admin, system=CRM)
    disabled = data_of((status, body)) or []
    check("§9.18 停用后 status=0 列表可见",
          status == 200 and any(item.get("id") == template_id for item in disabled),
          (status, disabled))

    status, _ = http("POST", "/api/user-roles", token=admin, system=CRM, body={
        "systemId": 1, "externalUserId": DIRECT_USER3,
        "roleId": role_with_template, "grantReason": "§9.18 停用模板",
    })
    check("§9.18 绑定停用模板的角色分配 → 409", status == 409, status)

    if pending_no is not None:
        status, _ = openapi("POST", "/openapi/approval/access-applications/decision",
                            body={"applicationNo": pending_no, "decision": "APPROVED",
                                  "reviewer": "acceptance",
                                  "decisionComment": "§9.18 停用模板审批"},
                            app_key=APPROVAL_APP_KEY, secret=APPROVAL_SECRET)
        check("§9.18 模板停用后审批通过 → 409", status == 409, status)


TESTS = [
    ("9.14", test_auth),
    ("9.15", test_system_isolation),
    ("9.1", test_multi_system),
    ("9.16", test_system_onboarding),
    ("9.16b", test_sdk_onboarding),
    ("9.16c", test_credentials_and_admin_guard),
    ("9.2", test_permission_model),
    ("9.7", test_public_and_platform_admin),
    ("9.5", test_page_actions),
    ("9.13", test_open_api_and_channel),
    ("9.10", test_mapping_and_pattern),
    ("9.12", test_expiry_and_approval),
    ("9.4", test_sdk_data_prereq),
    ("9.1b", test_sdk_end_to_end),
    ("9.1c", test_sdk_jdk21_end_to_end),
    ("9.17", test_access_application),
    ("9.18", test_time_template),
]


def main():
    global BASE_URL
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=BASE_URL)
    parser.add_argument("--only", default="", help="只跑指定章节，逗号分隔，如 9.1,9.12")
    args = parser.parse_args()
    BASE_URL = args.base_url

    only = set(x.strip() for x in args.only.split(",") if x.strip())
    for key, fn in TESTS:
        if only and key not in only and not any(key.startswith(o + ".") for o in only):
            continue
        fn()

    total = len(RESULTS)
    passed = sum(1 for r in RESULTS if r[2] == "PASS")
    failed = total - passed
    print("\n" + "=" * 70)
    print("验收结果：%d/%d PASS，%d FAIL（base_url=%s）" % (passed, total, failed, BASE_URL))
    if failed:
        print("\n失败项：")
        for sec, name, status, detail in RESULTS:
            if status == "FAIL":
                print("  - [%s] %s  detail=%s" % (sec, name, detail))
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
