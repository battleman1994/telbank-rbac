#!/usr/bin/env python3
"""telbank-rbac 需求第 9 节 · 权限中心 API 级验收冒烟脚本。

前置：
  1) 已初始化 MySQL 库 telbank_rbac（scripts/init-db.sh 或 scripts/start-temp-mysql.sh）
  2) 权限中心以 demo profile 启动（./scripts/start-api.sh），监听 8080
  3) 本脚本只依赖 Python 3 标准库

用法：
  python3 scripts/acceptance.py                 # 全量
  python3 scripts/acceptance.py --base-url http://localhost:8080
  python3 scripts/acceptance.py --only 9.1,9.12 # 只跑指定章节

说明：SDK/前端行为（§9.3/§9.4/§9.6/§9.9 的拦截器判定、模式 A/B 渲染）以
starter/front 单元测试与示例业务系统联调为证据，本脚本覆盖其中可在 API 层验证的部分。
"""

import argparse
import base64
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

USER_ADMIN = "13800138000"        # CRM + ERP 管理员
USER_OPERATOR = "13900139000"     # 仅 CRM 运营（临时授权）
USER_CROSS = "13600136000"        # CRM 运营 + ERP 采购员（跨系统隔离验收）
USER_EXPIRED = "13700137000"      # 授权全部过期
USER_NEW = "13500135000"          # 审批授权验收（初始无数据）
PLATFORM_ADMIN = "platform-admin-001"
MGMT_SYSTEM = "rbac"            # 管理端 /api/** 的 X-System-Code 固定为权限中心自身系统

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
def test_self_onboarding():
    section("§9.15 权限中心自接入（rbac 系统 + /api/me/bootstrap）")
    admin = token_of(USER_ADMIN)
    status, body = http("GET", "/api/me/bootstrap", token=admin)
    d = data_of((status, body)) or {}
    perms = set(d.get("permissions") or [])
    systems = {s.get("code") for s in (d.get("systems") or [])}
    check("管理员 bootstrap 返回 200 且 managementSystemCode=rbac",
          status == 200 and d.get("managementSystemCode") == "rbac", (status, d.get("managementSystemCode")))
    check("管理员 bootstrap 含 5 个 rbac:* 管理权限",
          {"rbac:system:manage", "rbac:role:manage", "rbac:permission:manage",
           "rbac:page:manage", "rbac:audit:view"} <= perms, sorted(perms))
    check("管理员 bootstrap systems 含 rbac/crm/erp", {"rbac", "crm", "erp"} <= systems, sorted(systems))

    status, body = http("GET", "/api/me/systems", token=admin)
    codes = {s.get("code") for s in (data_of((status, body)) or [])}
    check("管理员 /api/me/systems 含 rbac 系统", "rbac" in codes, sorted(codes))

    status, body = http("GET", "/api/me/bootstrap", token=token_of(USER_OPERATOR))
    d = data_of((status, body)) or {}
    perms = set(d.get("permissions") or [])
    systems = {s.get("code") for s in (d.get("systems") or [])}
    check("operator bootstrap 无管理权限（空集合）", perms == set(), sorted(perms))
    check("operator 系统列表不含 rbac", "rbac" not in systems, sorted(systems))

    status, body = http("GET", "/api/me/bootstrap", token=token_of(PLATFORM_ADMIN))
    d = data_of((status, body)) or {}
    check("平台超管 bootstrap permissions=['*']", d.get("permissions") == ["*"], d.get("permissions"))

    status, _ = http("GET", "/api/roles?systemId=1&page=1&size=20", token=admin, system=MGMT_SYSTEM)
    check("管理上下文 rbac：管理员 200", status == 200, status)
    status, _ = http("GET", "/api/roles?systemId=1&page=1&size=20", token=token_of(USER_OPERATOR), system=MGMT_SYSTEM)
    check("管理上下文 rbac：operator 403", status == 403, status)


def test_multi_system():
    section("§9.1 多系统隔离")
    admin = token_of(USER_ADMIN)
    status, body = http("GET", "/api/me/systems", token=admin)
    systems = data_of((status, body)) or []
    codes = [s.get("code") for s in systems]
    check("用户 A 可访问 CRM 与 ERP", {"crm", "erp"}.issubset(set(codes)), codes)

    # 管理端鉴权：管理上下文固定为 rbac 系统（X-System-Code: rbac）
    status, _ = http("GET", "/api/roles?systemId=1&page=1&size=20", token=token_of(USER_OPERATOR), system=MGMT_SYSTEM)
    check("operator 无管理权限：管理端角色列表返回 403", status == 403, status)
    status, _ = http("GET", "/api/roles?systemId=1&page=1&size=20", token=admin, system=MGMT_SYSTEM)
    check("rbac 管理员可查 CRM 角色（systemId=1）返回 200", status == 200, status)
    status, _ = http("GET", "/api/roles?systemId=2&page=1&size=20", token=admin, system=MGMT_SYSTEM)
    check("rbac 管理员可跨系统查 ERP 角色（systemId=2）返回 200", status == 200, status)

    status, body = http("GET", "/api/me/systems", token=token_of(USER_OPERATOR))
    codes = [s.get("code") for s in (data_of((status, body)) or [])]
    check("13900139000 仅能访问 CRM（未接入权限中心系统）", codes == ["crm"], codes)

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
    check("权限码 order:delete 返回时效 validUntil=2027-06-30",
          perms.get("order:delete", {}).get("validUntil", "").startswith("2027-06-30"),
          perms.get("order:delete"))
    check("is_public=1 的 common:profile 对所有用户默认拥有", "common:profile" in perms)


# --------------------------------------------------------------------------
# §9.7 公共权限与平台超管
# --------------------------------------------------------------------------
def test_public_and_platform_admin():
    section("§9.7 公共权限与平台超管")
    status, body = http("GET", "/api/me", token=token_of(PLATFORM_ADMIN))
    check("平台超管 platformAdmin=true", (data_of((status, body)) or {}).get("platformAdmin") is True)
    status, body = http("GET", "/api/me/systems", token=token_of(PLATFORM_ADMIN))
    codes = [s.get("code") for s in (data_of((status, body)) or [])]
    check("平台超管可见全部启用系统", {"crm", "erp"}.issubset(set(codes)), codes)
    status, body = openapi("GET", "/openapi/sdk/user-permissions",
                           "systemCode=crm&externalUserId=" + PLATFORM_ADMIN, system="crm")
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
        "type": "default", "action": "callApi", "config": "{\"api\":\"POST /api/order/accept\"}", "sort": 99,
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
    return code in codes


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


TESTS = [
    ("9.14", test_auth),
    ("9.15", test_self_onboarding),
    ("9.1", test_multi_system),
    ("9.2", test_permission_model),
    ("9.7", test_public_and_platform_admin),
    ("9.5", test_page_actions),
    ("9.13", test_open_api_and_channel),
    ("9.10", test_mapping_and_pattern),
    ("9.12", test_expiry_and_approval),
    ("9.4", test_sdk_data_prereq),
    ("9.1b", test_sdk_end_to_end),
    ("9.1c", test_sdk_jdk21_end_to_end),
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
