#!/usr/bin/env python3
"""AppKey + HMAC-SHA256 签名请求工具（验收/联调用，非生产依赖）。

用法示例（打印并可直接执行 curl）:

  python3 scripts/sign-request.py \
    --app-key crm --app-secret crm-dev-secret \
    --method GET --path /openapi/sdk/mappings --query 'systemCode=crm'

  python3 scripts/sign-request.py \
    --app-key approval-system --app-secret approval-dev-secret \
    --method POST --path /openapi/approval/grant \
    --body '{"systemCode":"crm",...}'

签名规范（与 docs/impl/contracts.md §4.1 一致）:

  canonical = appKey \\n timestamp \\n nonce \\n METHOD \\n path \\n sortedQuery \\n sha256Hex(body)
  X-Signature = HMAC-SHA256(appSecret, canonical) 的小写 hex

--base-url 默认 http://localhost:8080；--execute 时直接执行请求。
"""

import argparse
import hashlib
import hmac
import json
import secrets
import subprocess
import sys
import time


def canonical_string(app_key: str, timestamp: str, nonce: str, method: str,
                     path: str, query: str, body: bytes) -> str:
    # 契约 §4.1：原始 query 片段按 key 排序后直接拼接（不做解码/重编码）
    fragments = [item for item in query.split("&") if item]
    fragments.sort(key=lambda f: f.split("=", 1)[0])
    sorted_query = "&".join(fragments)
    body_hash = hashlib.sha256(body).hexdigest()
    return "\n".join([app_key, timestamp, nonce, method.upper(), path, sorted_query, body_hash])


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 AppKey+HMAC-SHA256 签名请求")
    parser.add_argument("--app-key", required=True)
    parser.add_argument("--app-secret", required=True)
    parser.add_argument("--method", default="GET")
    parser.add_argument("--path", required=True)
    parser.add_argument("--query", default="")
    parser.add_argument("--body", default="")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--execute", action="store_true", help="生成后立即执行 curl")
    parser.add_argument("--quiet", action="store_true", help="只输出响应，不打印 curl")
    args = parser.parse_args()

    body_bytes = args.body.encode("utf-8")
    timestamp = str(int(time.time() * 1000))
    nonce = secrets.token_hex(12)
    canonical = canonical_string(args.app_key, timestamp, nonce, args.method,
                                 args.path, args.query, body_bytes)
    signature = hmac.new(args.app_secret.encode("utf-8"), canonical.encode("utf-8"),
                         hashlib.sha256).hexdigest()

    url = args.base_url.rstrip("/") + args.path
    if args.query:
        url += "?" + args.query

    cmd = [
        "curl", "-sS", "-X", args.method.upper(), url,
        "-H", f"X-App-Key: {args.app_key}",
        "-H", f"X-Timestamp: {timestamp}",
        "-H", f"X-Nonce: {nonce}",
        "-H", f"X-Signature: {signature}",
    ]
    if body_bytes:
        cmd += ["-H", "Content-Type: application/json", "--data-binary", "@-"]

    if not args.quiet:
        print("curl " + " ".join(shell_quote(c) for c in cmd), file=sys.stderr)
        if body_bytes:
            print("  <body 直接作为 stdin 传入>", file=sys.stderr)

    if args.execute:
        proc = subprocess.run(cmd, input=body_bytes, capture_output=True)
        out = proc.stdout.decode("utf-8", "replace")
        try:
            print(json.dumps(json.loads(out), ensure_ascii=False, indent=2))
        except Exception:
            print(out)
        if proc.returncode != 0:
            print(proc.stderr.decode("utf-8", "replace"), file=sys.stderr)
        return proc.returncode

    print("curl " + " ".join(shell_quote(c) for c in cmd))
    if body_bytes:
        print("body:", args.body)
    return 0


def shell_quote(s: str) -> str:
    if s and all(c.isalnum() or c in "-_./:=@%+" for c in s):
        return s
    return "'" + s.replace("'", "'\\''") + "'"


if __name__ == "__main__":
    sys.exit(main())
