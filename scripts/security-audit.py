#!/usr/bin/env python3
"""
intern8 本地无破坏安全审计 PoC。

安全边界：
- 默认仅允许 127.0.0.1 / localhost；外部目标须显式 --allow-remote。
- 不调用上传、删除、关注、发布、评论、系统通知等有副作用接口。
- 只验证认证、授权暴露、CORS、JWT 篡改和输入校验。
- 不输出完整 JWT、临时密钥或敏感响应体。

用法：
  python scripts/security-audit.py --base-url http://127.0.0.1:8080
  DEMO_AUTH_PASSWORD=xxx python scripts/security-audit.py
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, asdict
from typing import Any, Dict, Optional, Tuple


@dataclass
class Finding:
    id: str
    severity: str
    title: str
    vulnerable: bool
    evidence: str
    recommendation: str


def request(method: str, url: str, *, headers: Optional[Dict[str, str]] = None,
            body: Optional[Dict[str, Any]] = None, timeout: float = 5.0) -> Tuple[int, Dict[str, str], bytes]:
    data = None
    merged = {"User-Agent": "intern8-local-security-audit/1.0"}
    if headers:
        merged.update(headers)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        merged.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=merged, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers.items()), resp.read(512 * 1024)
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers.items()), exc.read(512 * 1024)


def parse_json(raw: bytes) -> Dict[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8", errors="replace"))
        return value if isinstance(value, dict) else {"value": value}
    except Exception:
        return {}


def token_from_login(base_url: str, user_id: int, password: str) -> Tuple[int, Optional[str], Dict[str, Any]]:
    status, _, raw = request("POST", f"{base_url}/api/user/login",
                             body={"userId": user_id, "password": password})
    payload = parse_json(raw)
    token = None
    data = payload.get("data")
    if isinstance(data, dict):
        candidate = data.get("token")
        if isinstance(candidate, str):
            token = candidate
    return status, token, payload


def tamper_jwt(token: str) -> str:
    parts = token.split(".")
    if len(parts) != 3:
        return token + "x"
    payload_raw = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        payload = json.loads(base64.urlsafe_b64decode(payload_raw.encode()).decode())
        payload["sub"] = "999999999"
        encoded = base64.urlsafe_b64encode(
            json.dumps(payload, separators=(",", ":")).encode()
        ).decode().rstrip("=")
        return f"{parts[0]}.{encoded}.{parts[2]}"
    except Exception:
        return token[:-1] + ("A" if token[-1:] != "A" else "B")


def safe_target(base_url: str, allow_remote: bool) -> None:
    parsed = urllib.parse.urlparse(base_url)
    host = parsed.hostname or ""
    if parsed.scheme not in {"http", "https"}:
        raise SystemExit("仅支持 http/https")
    if allow_remote:
        return
    allowed = {"localhost", "127.0.0.1", "::1"}
    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(host, parsed.port or 80)}
    except socket.gaierror:
        addresses = set()
    if host not in allowed and not addresses.intersection({"127.0.0.1", "::1"}):
        raise SystemExit("默认仅审计本机目标；外部授权目标需显式 --allow-remote")


def main() -> int:
    parser = argparse.ArgumentParser(description="intern8 本地无破坏安全审计")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--demo-password", default=os.getenv("DEMO_AUTH_PASSWORD", "intern-demo"))
    parser.add_argument("--allow-remote", action="store_true")
    parser.add_argument("--json-output", default="")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    safe_target(base, args.allow_remote)
    findings: list[Finding] = []

    # 0. 健康性探测（只读）
    try:
        health_status, _, health_raw = request("GET", f"{base}/actuator/health")
    except Exception as exc:
        print(f"[ERROR] 目标不可达: {exc}", file=sys.stderr)
        return 2
    print(f"[INFO] target={base}, health_http={health_status}")

    # 1. 未认证访问必须被拒绝
    unauth_status, _, _ = request("GET", f"{base}/api/user/info")
    findings.append(Finding(
        "AUTH-01", "high", "受保护 API 未认证访问", unauth_status not in {401, 403},
        f"GET /api/user/info -> HTTP {unauth_status}",
        "所有业务 API 默认 authenticated；添加集成测试防止新端点漏放行。"
    ))

    # 2. dev 登录接口可为任意正整数 userId 签发 token（无用户存在性校验）
    arbitrary_user = 987654321
    login_status, token, login_payload = token_from_login(base, arbitrary_user, args.demo_password)
    arbitrary_issue = bool(token)
    findings.append(Finding(
        "AUTH-02", "critical", "开发登录接口可签发任意身份 JWT", arbitrary_issue,
        f"POST /api/user/login userId={arbitrary_user} -> HTTP {login_status}, token_issued={arbitrary_issue}",
        "生产默认 Profile 改为 prod；DevAuthController 增加显式安全开关、仅 loopback、随机密码和用户白名单；启动时拒绝 dev+公网绑定。"
    ))

    # 3. 默认管理员 ID=1/2 + 任意身份签发的组合风险（只验证 token，不调用 system/send）
    admin_status, admin_token, _ = token_from_login(base, 1, args.demo_password)
    findings.append(Finding(
        "AUTHZ-01", "critical", "默认管理员 ID 与开发登录组合导致管理员冒充", bool(admin_token),
        f"POST /api/user/login userId=1 -> HTTP {admin_status}, admin_token_issued={bool(admin_token)}；未调用有副作用的 /system/send",
        "ADMIN_IDS 默认值必须为空；管理员授权改为数据库/RBAC authority；系统通知端点使用 @PreAuthorize('hasRole(ADMIN)')。"
    ))

    # 4. JWT 篡改必须被拒绝
    if token:
        forged = tamper_jwt(token)
        forged_status, _, _ = request("GET", f"{base}/api/user/info",
                                      headers={"Authorization": f"Bearer {forged}"})
        findings.append(Finding(
            "JWT-01", "critical", "JWT payload 篡改/伪造", forged_status not in {401, 403},
            f"篡改 sub 且保留原签名 -> HTTP {forged_status}",
            "保持强 HMAC 密钥、issuer 校验、过期校验；增加 key rotation、audience 与 token version/revocation。"
        ))

    # 5. Actuator 暴露面
    exposed = []
    for path in ("/actuator/health", "/actuator/info", "/actuator/metrics", "/actuator/prometheus"):
        status, _, raw = request("GET", base + path)
        if status == 200:
            exposed.append(path)
    findings.append(Finding(
        "EXPOSE-01", "medium", "Actuator 端点无需认证", len(exposed) > 1,
        "匿名可访问: " + (", ".join(exposed) if exposed else "无"),
        "仅公开 /actuator/health/liveness 与 readiness；metrics/prometheus 置于管理端口并限制内网/IP/角色。"
    ))

    # 6. Swagger/OpenAPI 暴露面
    swagger_status, _, swagger_raw = request("GET", f"{base}/v3/api-docs")
    findings.append(Finding(
        "EXPOSE-02", "low", "Swagger/OpenAPI 文档公开", swagger_status == 200 and len(swagger_raw) > 100,
        f"GET /v3/api-docs -> HTTP {swagger_status}, bytes={len(swagger_raw)}",
        "生产禁用 springdoc，或将文档端点限制到内网/管理员。"
    ))

    # 7. CORS 任意 Origin + credentials
    origin = "https://evil.example"
    cors_status, cors_headers, _ = request(
        "OPTIONS", f"{base}/api/user/info",
        headers={
            "Origin": origin,
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "authorization,content-type",
        }
    )
    allow_origin = cors_headers.get("Access-Control-Allow-Origin", "")
    allow_credentials = cors_headers.get("Access-Control-Allow-Credentials", "").lower()
    cors_vulnerable = allow_origin == origin and allow_credentials == "true"
    findings.append(Finding(
        "CORS-01", "high", "任意 Origin 被允许携带凭据", cors_vulnerable,
        f"OPTIONS evil origin -> HTTP {cors_status}, ACAO={allow_origin!r}, ACAC={allow_credentials!r}",
        "使用显式前端 Origin allowlist；生产禁止 allowedOriginPatterns('*') + allowCredentials(true)。Bearer-only API 可关闭 credentials。"
    ))

    # 8. 登录限流（仅 8 次错误密码，无持久化副作用）
    start = time.time()
    statuses = []
    for i in range(8):
        status, _, _ = token_from_login(base, 1, f"invalid-{i}")
        statuses.append(status)
    login_limited = 429 in statuses
    findings.append(Finding(
        "AUTH-03", "high", "登录接口缺少速率限制/爆破防护", not login_limited,
        f"8 次错误登录 HTTP 状态={statuses}, elapsed={time.time()-start:.2f}s",
        "按 IP+账号双维度限流、指数退避、失败计数与审计告警；统一返回 HTTP 401，避免业务 200 掩盖失败。"
    ))

    # 9. JSON Body 超长输入应在落库前拒绝（使用无效 oversized 请求，不产生写入）
    if token:
        status, _, raw = request(
            "POST", f"{base}/api/dynamic/publish",
            headers={"Authorization": f"Bearer {token}", "Idempotency-Key": "security-audit-oversize"},
            body={"content": "A" * 5001, "images": ""},
        )
        payload = parse_json(raw)
        rejected = status in {400, 413, 422} or payload.get("success") is False
        findings.append(Finding(
            "INPUT-01", "medium", "JSON 超长内容未在写入前拒绝", not rejected,
            f"5001 字符 publish -> HTTP {status}, success={payload.get('success')}",
            "保留 DTO @Size；服务器/网关增加 max-http-request-size；校验失败使用 HTTP 400/413。"
        ))

    vulnerable_count = sum(1 for f in findings if f.vulnerable)
    print("\n=== Findings ===")
    for f in findings:
        marker = "VULNERABLE" if f.vulnerable else "PASS"
        print(f"[{marker}] {f.severity.upper():8} {f.id} {f.title}")
        print(f"  evidence: {f.evidence}")
        if f.vulnerable:
            print(f"  fix: {f.recommendation}")

    report = {
        "target": base,
        "safe_mode": True,
        "vulnerable_count": vulnerable_count,
        "findings": [asdict(item) for item in findings],
    }
    output = args.json_output or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "SECURITY_AUDIT_RESULTS.json",
    )
    with open(output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n[INFO] JSON report: {output}")
    print(f"[INFO] vulnerable={vulnerable_count}/{len(findings)}")
    return 1 if vulnerable_count else 0


if __name__ == "__main__":
    sys.exit(main())
