#!/usr/bin/env python3
"""模拟外部审批平台：查看业务用户临时权限申请，并做出通过/驳回决策。

权限中心只维护申请单与审批结果，不实现审批流（设计 §7/§10.1）。本脚本模拟外部审批平台：

  list     列出申请单（默认只查「待审批」，即模拟审批平台的待办箱）
  show     查看单张申请单详情
  approve  通过申请（POST /openapi/approval/access-applications/decision，decision=APPROVED）
  reject   驳回申请（同上，decision=REJECTED）

决策回调使用审批专用 AppKey + HMAC-SHA256 验签，签名规范与契约 §4.1 一致：

  canonical = appKey \n timestamp \n nonce \n METHOD \n path \n sortedQuery \n sha256Hex(body)
  X-Signature = HMAC-SHA256(appSecret, canonical) 的小写 hex

用法：

  ./scripts/mock-access-approval.py list
  ./scripts/mock-access-approval.py list --status ALL --system-code crm --limit 50
  ./scripts/mock-access-approval.py show --application-no RA-20261025-A1B2C3
  ./scripts/mock-access-approval.py approve --application-no RA-20261025-A1B2C3 --comment "同意，仅限本次"
  ./scripts/mock-access-approval.py reject --application-no RA-... --comment "不符合规定"

A21 起审批决策不再携带授权窗口：权限中心在决策时按每个角色绑定的时间模板计算绝对窗口
（多角色可各自不同），脚本只提交申请单号、决策、审批人与意见。list/show 展示的逐角色窗口
来自权限中心落库结果（快照），仅用于本地演示核对。

  # 兼容计划 Task 7 的参数风格（无子命令时按旧参数执行决策）：
  ./scripts/mock-access-approval.py --application-no RA-... --decision REJECTED --comment 不符合规定

环境变量：

  RBAC_BASE_URL              权限中心地址，默认 http://localhost:8080
  RBAC_APPROVAL_APP_KEY      审批专用 AppKey，默认 approval-system
  RBAC_APPROVAL_SECRET       审批专用 AppSecret，默认 approval-dev-secret
  MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD/RBAC_DB_NAME
                             list/show 读库连接（默认与 scripts/init-db.sh 一致：
                             127.0.0.1:3306 root 空密码 telbank_rbac）
  RBAC_MYSQL_BIN             mysql 客户端路径，默认 mysql

说明（A21）：审批决策请求不再接收 validFrom/validUntil，窗口由权限中心按角色时间模板计算；
请求体只含 applicationNo/decision/reviewer/decisionComment。

说明：权限中心当前只开放「决策回调」，没有供审批平台拉取申请单的开放接口（设计 §7.1）。
list/show 因此直接读取本地 demo 数据库，作为「模拟审批平台的待办箱」，仅用于本地演示与验收；
approve/reject 只依赖 HTTP，可在没有数据库的环境中直接使用（申请单号从「我的申请」页面复制）。

退出码：0 成功；1 调用失败或参数/环境错误（便于脚本化验收）。
"""

import argparse
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.request

PATH_DECISION = "/openapi/approval/access-applications/decision"
NEWLINE = "\n"

BASE_URL = os.environ.get("RBAC_BASE_URL", "http://localhost:8080").rstrip("/")
APP_KEY = os.environ.get("RBAC_APPROVAL_APP_KEY", "approval-system")
APP_SECRET = os.environ.get("RBAC_APPROVAL_SECRET", "approval-dev-secret")

MYSQL_BIN = os.environ.get("RBAC_MYSQL_BIN", "mysql")
MYSQL_HOST = os.environ.get("MYSQL_HOST", "127.0.0.1")
MYSQL_PORT = os.environ.get("MYSQL_PORT", "3306")
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
MYSQL_PASSWORD = os.environ.get("MYSQL_PASSWORD", "")
DB_NAME = os.environ.get("RBAC_DB_NAME", "telbank_rbac")

STATUS_VALUES = {"PENDING": 0, "APPROVED": 1, "REJECTED": 2}
STATUS_LABELS = {"PENDING": "待审批", "APPROVED": "已通过", "REJECTED": "已驳回"}
APPLICATION_NO_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
SYSTEM_CODE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
MAX_COMMENT_LENGTH = 255


class ScriptError(Exception):
    """脚本可预期错误：打印中文提示后以非 0 退出。"""


# --------------------------------------------------------------------------
# 签名与 HTTP（契约 §4.1）
# --------------------------------------------------------------------------
def canonical_string(app_key, timestamp, nonce, method, path, query, body):
    """contract §4.1：原始 query 片段按 key 排序后直接拼接（不做解码/重编码）。"""
    fragments = [item for item in query.split("&") if item]
    fragments.sort(key=lambda item: item.split("=", 1)[0])
    sorted_query = "&".join(fragments)
    body_hash = hashlib.sha256(body).hexdigest()
    return NEWLINE.join([app_key, timestamp, nonce, method.upper(), path, sorted_query, body_hash])


def signed_headers(app_key, app_secret, method, path, raw, query=""):
    timestamp = str(int(time.time() * 1000))
    nonce = secrets.token_hex(12)
    canonical = canonical_string(app_key, timestamp, nonce, method, path, query, raw)
    signature = hmac.new(app_secret.encode("utf-8"), canonical.encode("utf-8"),
                         hashlib.sha256).hexdigest()
    return {
        "X-App-Key": app_key,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
        "Content-Type": "application/json",
        "Accept": "application/json",
    }


def print_signed_curl(base_url, app_key, app_secret, method, path, raw):
    """打印等价的 curl 命令，便于联调排查（不含明文 secret）。"""
    headers = signed_headers(app_key, app_secret, method, path, raw)
    parts = ["curl", "-sS", "-X", method.upper(), base_url.rstrip("/") + path]
    for name in ("X-App-Key", "X-Timestamp", "X-Nonce", "X-Signature"):
        parts += ["-H", "%s: %s" % (name, headers[name])]
    parts += ["-H", "Content-Type: application/json", "--data-binary", "@-"]
    print("[curl] " + " ".join(shell_quote(part) for part in parts))


def shell_quote(text):
    if text and all(ch.isalnum() or ch in "-_./:=@%+" for ch in text):
        return text
    return "'" + text.replace("'", "'\\''") + "'"


def http_post_decision(base_url, app_key, app_secret, payload, timeout):
    raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    url = base_url.rstrip("/") + PATH_DECISION
    headers = signed_headers(app_key, app_secret, "POST", PATH_DECISION, raw)

    print("[请求] POST " + url)
    print("[请求体] " + json.dumps(payload, ensure_ascii=False))
    request = urllib.request.Request(url, data=raw, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", "replace")
            code = response.status
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        print("[HTTP %d] %s" % (error.code, body), file=sys.stderr)
        return 1
    except urllib.error.URLError as error:
        print("[调用失败] 无法连接权限中心 %s：%s" % (url, error.reason), file=sys.stderr)
        return 1
    except Exception as error:  # noqa: BLE001 - 演示脚本，兜底提示
        print("[调用失败] %s" % error, file=sys.stderr)
        return 1

    print("[HTTP %d] %s" % (code, body))
    try:
        envelope = json.loads(body)
    except ValueError:
        return 0
    data = envelope.get("data") if isinstance(envelope, dict) else None
    if isinstance(data, dict) and data.get("status"):
        suffix = "（幂等重复决策）" if data.get("idempotent") else ""
        print("[最终状态] %s %s" % (data["status"], suffix))
    return 0


# --------------------------------------------------------------------------
# 本地库查询（模拟审批平台待办箱）
# --------------------------------------------------------------------------
def mysql_connection_error():
    return ("无法读取申请单：未找到 mysql 客户端或连接失败。\n"
            "  list/show 依赖本地 demo 数据库（环境变量 MYSQL_HOST/MYSQL_PORT/MYSQL_USER/"
            "MYSQL_PASSWORD/RBAC_DB_NAME，默认与 scripts/init-db.sh 一致）。\n"
            "  若只需对已知申请单做决策，可跳过 list/show：申请单号从「我的申请」页面复制，"
            "直接执行 approve/reject。")


def query_applications(status=None, system_code=None, limit=20, application_no=None):
    if shutil.which(MYSQL_BIN) is None:
        raise ScriptError(mysql_connection_error())

    where = ["1 = 1"]
    if application_no is not None:
        where.append("a.application_no = '%s'" % application_no.replace("'", "''"))
    if status is not None:
        where.append("a.status = %d" % status)
    if system_code is not None:
        where.append("s.code = '%s'" % system_code.replace("'", "''"))

    sql = (
        "SELECT JSON_OBJECT("
        "'applicationNo', a.application_no,"
        "'systemCode', s.code,"
        "'systemName', s.name,"
        "'externalUserId', a.external_user_id,"
        "'status', CASE a.status WHEN 0 THEN 'PENDING' WHEN 1 THEN 'APPROVED' "
        "WHEN 2 THEN 'REJECTED' ELSE CAST(a.status AS CHAR) END,"
        "'reason', a.reason,"
        "'roles', COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT("
        "'roleId', r.role_id, 'roleCode', r.role_code, 'roleName', r.role_name,"
        "'timeTemplateName', r.time_template_name,"
        "'approvedValidFrom', DATE_FORMAT(r.approved_valid_from, '%Y-%m-%dT%H:%i:%s'),"
        "'approvedValidUntil', DATE_FORMAT(r.approved_valid_until, '%Y-%m-%dT%H:%i:%s'))) "
        "FROM sys_access_application_role r WHERE r.application_id = a.id), JSON_ARRAY()),"
        "'createdAt', DATE_FORMAT(a.created_at, '%Y-%m-%dT%H:%i:%s'),"
        "'reviewedBy', a.reviewed_by,"
        "'reviewedAt', DATE_FORMAT(a.reviewed_at, '%Y-%m-%dT%H:%i:%s'),"
        "'decisionComment', a.decision_comment,"
        "'approvedValidFrom', DATE_FORMAT(a.approved_valid_from, '%Y-%m-%dT%H:%i:%s'),"
        "'approvedValidUntil', DATE_FORMAT(a.approved_valid_until, '%Y-%m-%dT%H:%i:%s')"
        ") FROM sys_access_application a JOIN sys_system s ON s.id = a.system_id"
        " WHERE " + " AND ".join(where) +
        " ORDER BY a.created_at DESC, a.id DESC"
    )
    if application_no is None:
        sql += " LIMIT %d" % limit
    else:
        sql += " LIMIT 1"
    sql += ";"

    command = [MYSQL_BIN, "-h", MYSQL_HOST, "-P", MYSQL_PORT, "-u", MYSQL_USER,
               "-N", "-B", "-r"]
    if MYSQL_PASSWORD:
        command.append("-p" + MYSQL_PASSWORD)
    command += [DB_NAME, "-e", sql]

    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        raise ScriptError(mysql_connection_error() + "\n  详细：%s" % error)
    if completed.returncode != 0:
        detail = (completed.stderr or "").strip()
        raise ScriptError(mysql_connection_error() + "\n  详细：%s" % detail)

    rows = []
    for line in completed.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except ValueError:
            continue
    return rows


def format_roles(roles):
    if not roles:
        return "-"
    parts = []
    for role in roles:
        parts.append("%s:%s(%s)" % (role.get("roleId"), role.get("roleName"),
                                    role.get("roleCode")))
    return ", ".join(parts)


def display_width(text):
    width = 0
    for char in str(text):
        width += 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
    return width


def pad(text, width):
    text = str(text)
    return text + " " * max(0, width - display_width(text))


def print_table(headers, rows):
    widths = [display_width(header) for header in headers]
    for row in rows:
        for index, cell in enumerate(row):
            widths[index] = max(widths[index], display_width(cell))
    print("  ".join(pad(header, width) for header, width in zip(headers, widths)))
    print("  ".join("-" * width for width in widths))
    for row in rows:
        print("  ".join(pad(cell, width) for cell, width in zip(row, widths)))


# --------------------------------------------------------------------------
# 命令实现
# --------------------------------------------------------------------------
def status_label(status):
    if status in STATUS_LABELS:
        return "%s（%s）" % (status, STATUS_LABELS[status])
    return str(status)


def run_list(args):
    if args.system_code is not None and not SYSTEM_CODE_PATTERN.fullmatch(args.system_code):
        raise ScriptError("--system-code 格式不合法：%s" % args.system_code)
    if args.limit < 1 or args.limit > 500:
        raise ScriptError("--limit 取值范围 1~500")
    status = None if args.status == "ALL" else STATUS_VALUES[args.status]
    rows = query_applications(status=status, system_code=args.system_code, limit=args.limit)
    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0
    if not rows:
        print("没有符合条件的申请单（status=%s）。" % args.status)
        return 0
    table = []
    for item in rows:
        reason = item.get("reason") or ""
        if len(reason) > 40:
            reason = reason[:40] + "…"
        table.append([
            item.get("applicationNo") or "-",
            item.get("systemCode") or "-",
            item.get("externalUserId") or "-",
            status_label(item.get("status")),
            format_roles(item.get("roles")),
            reason or "-",
            item.get("createdAt") or "-",
        ])
    print_table(["申请单号", "系统", "申请人", "状态", "申请角色", "申请原因", "创建时间"], table)
    print("\n共 %d 条（status=%s）。审批："
          "./scripts/mock-access-approval.py approve --application-no <单号>" % (len(rows), args.status))
    return 0


def run_show(args):
    if not APPLICATION_NO_PATTERN.fullmatch(args.application_no):
        raise ScriptError("申请单号格式不合法：%s" % args.application_no)
    rows = query_applications(application_no=args.application_no)
    if not rows:
        raise ScriptError("申请单不存在：%s" % args.application_no)
    item = rows[0]
    if args.json:
        print(json.dumps(item, ensure_ascii=False, indent=2))
        return 0
    print("申请单号 : %s" % item.get("applicationNo"))
    print("系统     : %s (%s)" % (item.get("systemCode"), item.get("systemName")))
    print("申请人   : %s" % (item.get("externalUserId") or "-"))
    print("状态     : %s" % status_label(item.get("status")))
    roles = item.get("roles") or []
    print("申请角色 : %s" % format_roles(roles))
    for role in roles:
        template = role.get("timeTemplateName") or "-"
        window = "%s ~ %s" % (role.get("approvedValidFrom") or "-",
                              role.get("approvedValidUntil") or "-")
        print("           - %s 模板=%s 窗口=%s" % (role.get("roleCode"), template, window))
    print("申请原因 : %s" % (item.get("reason") or "-"))
    print("创建时间 : %s" % (item.get("createdAt") or "-"))
    if item.get("reviewedBy") or item.get("reviewedAt"):
        print("审批人   : %s" % (item.get("reviewedBy") or "-"))
        print("审批时间 : %s" % (item.get("reviewedAt") or "-"))
    if item.get("decisionComment"):
        print("审批意见 : %s" % item.get("decisionComment"))
    if item.get("approvedValidFrom") or item.get("approvedValidUntil"):
        print("授权窗口（包络）: %s ~ %s" % (item.get("approvedValidFrom") or "-",
                                            item.get("approvedValidUntil") or "-"))
    return 0


def build_decision_payload(application_no, decision, comment, reviewer):
    """A21：只提交决策；授权窗口由权限中心按角色时间模板计算，请求不再携带时间字段。"""
    payload = {
        "applicationNo": application_no,
        "decision": decision,
        "reviewer": reviewer,
    }
    if comment:
        if len(comment) > MAX_COMMENT_LENGTH:
            raise ScriptError("审批意见不能超过 %d 字（当前 %d 字）"
                              % (MAX_COMMENT_LENGTH, len(comment)))
        payload["decisionComment"] = comment
    return payload


def run_decision(args):
    application_no = args.application_no.strip()
    if not APPLICATION_NO_PATTERN.fullmatch(application_no):
        raise ScriptError("申请单号格式不合法：%s" % application_no)
    payload = build_decision_payload(application_no, args.decision, args.comment,
                                     args.reviewer)
    raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    print_signed_curl(args.base_url, args.app_key, args.app_secret, "POST", PATH_DECISION, raw)
    return http_post_decision(args.base_url, args.app_key, args.app_secret, payload,
                              args.timeout)


# --------------------------------------------------------------------------
# 命令行
# --------------------------------------------------------------------------
def add_common_http_args(parser):
    parser.add_argument("--base-url", default=BASE_URL,
                        help="权限中心地址，默认 %s（环境变量 RBAC_BASE_URL）" % BASE_URL)
    parser.add_argument("--app-key", default=APP_KEY,
                        help="审批专用 AppKey，默认 %s（环境变量 RBAC_APPROVAL_APP_KEY）" % APP_KEY)
    parser.add_argument("--app-secret", "--secret", dest="app_secret", default=APP_SECRET,
                        help="审批专用 AppSecret（环境变量 RBAC_APPROVAL_SECRET）")
    parser.add_argument("--timeout", type=float, default=20.0, help="HTTP 超时秒数，默认 20")


def build_parser():
    parser = argparse.ArgumentParser(
        description="模拟外部审批平台：查看申请单并通过/驳回（审批回调使用 AppKey+HMAC-SHA256 验签）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
               "  %(prog)s list --status PENDING\n"
               "  %(prog)s show --application-no RA-20261025-A1B2C3\n"
               "  %(prog)s approve --application-no RA-20261025-A1B2C3 --comment 同意，仅限本次\n"
               "  %(prog)s reject --application-no RA-20261025-A1B2C3 --comment 不符合规定")
    subparsers = parser.add_subparsers(dest="command", metavar="{list,show,approve,reject}")

    list_parser = subparsers.add_parser("list", help="列出申请单（默认待审批）",
                                        description="列出申请单（读取本地 demo 数据库）")
    list_parser.add_argument("--status", choices=["PENDING", "APPROVED", "REJECTED", "ALL"],
                             default="PENDING", help="状态筛选，默认 PENDING")
    list_parser.add_argument("--system-code", default=None, help="按业务系统编码筛选")
    list_parser.add_argument("--limit", type=int, default=20, help="最多返回条数，默认 20")
    list_parser.add_argument("--json", action="store_true", help="以 JSON 输出")
    list_parser.set_defaults(handler=run_list)

    show_parser = subparsers.add_parser("show", help="查看申请单详情",
                                        description="查看单张申请单详情（读取本地 demo 数据库）")
    show_parser.add_argument("--application-no", required=True, help="申请单号，如 RA-20261025-A1B2C3")
    show_parser.add_argument("--json", action="store_true", help="以 JSON 输出")
    show_parser.set_defaults(handler=run_show)

    for command, decision, title in (("approve", "APPROVED", "通过申请"),
                                     ("reject", "REJECTED", "驳回申请")):
        decide_parser = subparsers.add_parser(command, help=title, description=title)
        decide_parser.add_argument("--application-no", required=True,
                                   help="申请单号，如 RA-20261025-A1B2C3")
        decide_parser.add_argument("--comment", default="", help="审批意见（<=255 字）")
        decide_parser.add_argument("--reviewer", default="mock-approval-platform",
                                   help="审批方标识，默认 mock-approval-platform")
        add_common_http_args(decide_parser)
        decide_parser.set_defaults(handler=run_decision, decision=decision)
    return parser


def build_legacy_parser():
    """兼容计划 Task 7 的旧参数：--application-no/--decision/--comment ..."""
    parser = argparse.ArgumentParser(
        description="模拟外部审批平台（旧参数风格：--application-no/--decision）",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--application-no", required=True, help="申请单号")
    parser.add_argument("--decision", choices=["APPROVED", "REJECTED"], default="APPROVED",
                        help="决策，默认 APPROVED")
    parser.add_argument("--comment", default="", help="审批意见（<=255 字）")
    parser.add_argument("--reviewer", default="mock-approval-platform", help="审批方标识")
    add_common_http_args(parser)
    parser.set_defaults(handler=run_decision)
    return parser


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in ("-h", "--help"):
        parser = build_parser()
        parser.print_help()
        print("\n兼容旧参数：--application-no <单号> [--decision APPROVED|REJECTED] [--comment ...]")
        return 0
    if argv[0] in ("list", "show", "approve", "reject"):
        parser = build_parser()
    else:
        parser = build_legacy_parser()
    args = parser.parse_args(argv)
    if not hasattr(args, "handler"):
        parser.print_help()
        return 0
    try:
        return args.handler(args)
    except ScriptError as error:
        print("[错误] %s" % error, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n已取消。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
