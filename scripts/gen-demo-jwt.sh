#!/usr/bin/env bash
# 生成演示用 HS256 JWT（仅本地联调/验收；生产使用外部认证系统签发的 JWT）
#
# 用法: ./scripts/gen-demo-jwt.sh [externalUserId] [有效期秒数]
#   示例: ./scripts/gen-demo-jwt.sh 13800138000
#         ./scripts/gen-demo-jwt.sh platform-admin-001
#
# 注意: 密钥必须与权限中心 application-demo.yml 的
#       rbac.security.jwt.hs256-secret 一致（默认值见下）。
set -euo pipefail

SUBJECT="${1:-13800138000}"
TTL="${2:-86400}"
SECRET="${RBAC_JWT_HS256_SECRET:-telbank-rbac-demo-secret-please-change}"
ISSUER="${RBAC_JWT_ISSUER:-demo-auth}"

python3 - "$SUBJECT" "$TTL" "$SECRET" "$ISSUER" <<'PY'
import base64
import hashlib
import hmac
import json
import sys
import time

subject, ttl, secret, issuer = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
now = int(time.time())


def b64(raw: bytes) -> bytes:
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
payload = b64(json.dumps({
    "sub": subject,
    "iss": issuer,
    "iat": now,
    "exp": now + ttl,
}, separators=(",", ":")).encode())
signing_input = header + b"." + payload
signature = b64(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest())
print((signing_input + b"." + signature).decode())
PY
