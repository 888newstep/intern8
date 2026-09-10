# intern8 安全攻击面审计与加固建议（2026-08-29）

> 范围：仅审计本地授权项目 `xiaozhaovip-intern8`。PoC 默认只连接 `127.0.0.1/localhost`，不删除数据、不上传文件、不发送系统通知、不扫描外部网络、不获取或展示真实密钥。
>
> 配套脚本：`scripts/security-audit.py`。当前审计时 8080 未运行，因此动态验证项需启动本地实例后执行；静态证据已由代码确认。

---

## 1. 总体评价

项目已有较好的安全工程基础：

- Spring Security 默认要求业务接口认证；
- JWT 使用 HMAC 签名并校验 issuer、有效期；
- 写接口从 SecurityContext 获取 userId，不信任请求体身份；
- 动态、通知删除存在资源所有者校验；
- DTO 普遍有 `@NotNull/@Positive/@Size/@Max`；
- 发布、点赞、评论有幂等与限流；
- OSS 上传目录做了路径段白名单，临时文件 finally 清理；
- 现有 Maven 测试全部通过（Surefire 报告 0 failures / 0 errors）。

但安全边界存在一个高风险组合：

> **默认启动 dev Profile + 默认演示密码 + 登录可指定任意 userId + 默认管理员 ID=1,2。**

如果应用被按默认配置暴露到公网，攻击者可以直接签发任意用户 JWT，进一步冒充管理员。这是当前最高优先级风险。

---

## 2. 风险分级

### P0 / Critical：开发登录可签发任意身份 JWT

**静态证据**：

- `application.properties:156`：`spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}`，未设置环境变量时默认 dev；
- `application.properties:163`：`demo.auth.password=${DEMO_AUTH_PASSWORD:intern-demo}`，存在固定默认密码；
- `DevAuthController.java:42-55`：登录请求接受任意正整数 `userId`，密码正确后直接签发 JWT，不检查用户存在性和白名单；
- `SecurityConfig.java:51`：`/api/user/login` 公开。

**攻击链**（PoC 只验证签发，不执行破坏操作）：

```http
POST /api/user/login
Content-Type: application/json

{"userId":987654321,"password":"intern-demo"}
```

若返回 token，即可冒充任意 userId。

**影响**：完全绕过真实身份认证；可读取/操作被冒充用户的数据。

**修复**：

1. 根配置不要默认 dev：删除 `:dev` 默认值，或默认 `prod`；
2. `DevAuthController` 再增加 `demo.auth.enabled=false` 显式开关；
3. dev 登录仅允许 loopback，并通过反向代理防止信任伪造的 `X-Forwarded-For`；
4. `DEMO_AUTH_PASSWORD` 不允许默认值，启动时必须提供随机高强度值；
5. 限定允许签发的 demo userId 白名单，不接受任意正整数；
6. 增加启动保护：`dev + server.address 非 loopback` 时 fail-fast。

---

### P0 / Critical：默认管理员 ID 与任意身份签发组合导致管理员冒充

**静态证据**：

- `application.properties:173`：`system.notification.admin-ids=${ADMIN_IDS:1,2}`；
- `NotificationController` 仅检查当前 userId 是否存在于配置集合；
- 上述开发登录允许请求 `userId=1/2` 并签发 JWT。

**攻击链**：登录为 userId=1 → 获得 JWT → 可通过 `/api/notification/system/send` 的管理员判断。

> 审计脚本不会调用 `/system/send`，避免产生通知数据；只验证管理员身份 token 是否能被签发。

**修复**：

- `ADMIN_IDS` 默认必须为空，生产缺失时拒绝启动；
- 管理员授权改为 RBAC/权限表或 JWT authority，不用硬编码 ID；
- 使用 `@PreAuthorize("hasRole('ADMIN')")`；
- 管理操作增加审计日志（操作者、目标用户、requestId、结果），并按管理员身份限流。

---

### P1 / High：CORS 允许任意来源并携带凭据

**静态证据**：`SecurityConfig.java:64-67`

```java
setAllowedOriginPatterns(List.of("*"));
setAllowedHeaders(List.of("*"));
setAllowCredentials(true);
```

Spring 会回显请求 Origin。任意恶意站点可通过浏览器发起带凭据的跨域请求。JWT 若存储在 localStorage，仍可能由受害前端/XSS 泄漏；若未来改 Cookie，风险会直接升级。

**修复**：

- 使用配置化显式 allowlist，例如 `https://app.example.com`；
- Bearer-only API 设置 `allowCredentials(false)`；
- 生产、测试、开发分别配置 Origin；
- 添加 CORS 集成测试，恶意 Origin 必须无 `Access-Control-Allow-Origin`。

---

### P1 / High：登录接口缺少爆破防护，失败状态语义不规范

`DevAuthController` 未使用 `RateLimiterService`；错误密码通过业务 `ResponseDO` 返回，方法本身不明确设置 HTTP 401，可能表现为 HTTP 200 + 业务 code 401。

**影响**：固定默认密码可被快速爆破；监控、WAF、客户端容易误判失败请求。

**修复**：

- IP + userId 双维度令牌桶；
- 连续失败指数退避，达到阈值短期锁定；
- 错误登录返回 HTTP 401，限流返回 HTTP 429；
- 只记录脱敏账号/IP 哈希，不记录密码和 token；
- 对异常失败速率告警。

---

### P1 / High：COS 对象缺少租户/用户级所有权隔离

**静态证据**：

- `OSSController.java:73` 向客户端声明 `allowPrefix="*"`，与真实 STS 策略 `COSUtils.java:111` 的 `uploads/*` 不一致；
- STS 的真实范围也是所有用户共享的 `uploads/*`，没有 `uploads/{userId}/...` 隔离；
- `/api/oss/delete` 只要已登录并知道 `fileName`，即可删除任意对象；
- `/api/oss/access/{fileName}` 可对任意已知对象名签发访问 URL；
- 服务端 `/upload` 接受调用方自选 `dir`，未强制绑定当前用户目录；
- 上传仅检查扩展名字符，不校验 MIME、魔数或允许类型；未看到 multipart 大小限制。

**影响**：水平越权删除/访问对象、对象覆盖/滥用存储、恶意文件托管、大文件导致磁盘/内存/带宽压力。

**修复**：

1. 对象键统一为 `uploads/{userId}/{uuid}.{ext}`，服务端生成，不接受任意 dir；
2. STS `allowPrefix` 收敛到当前用户前缀；响应中的 prefix 与真实策略保持一致；
3. 建立 `file_asset(id,user_id,object_key,sha256,size,mime,status)` 元数据表；access/delete 前验证所有权；
4. 文件类型使用 MIME + 魔数白名单；图片解码重编码；拒绝 HTML/SVG/脚本；
5. 配置 `spring.servlet.multipart.max-file-size`、`max-request-size`，网关同步限制；
6. 上传接口按 userId/IP 限流与日配额；COS bucket 最小权限、私有读；
7. 删除改为软删除/异步回收，重要文件开启版本控制。

---

### P2 / Medium：Actuator 暴露范围过宽

**静态证据**：

- `SecurityConfig.java:53`：`/actuator/**` 全部 permitAll；
- `application.properties:166-167`：公开 health/info/prometheus/metrics，且 health `show-details=always`。

**影响**：泄漏 JVM、缓存、队列、连接池、业务指标和依赖健康详情，辅助攻击者指纹识别与容量探测。

**修复**：

- 公网只允许 `/actuator/health/liveness`、`readiness`；
- `management.endpoint.health.show-details=when_authorized`；
- 管理端口与业务端口分离，绑定内网地址；
- Prometheus 使用网络 ACL/mTLS/专用凭据。

---

### P2 / Low：Swagger/OpenAPI 生产环境公开

`SecurityConfig.java:52` 公开 `/swagger-ui/**` 与 `/v3/api-docs/**`。

**影响**：接口、字段、鉴权头、内部操作路径完整暴露，降低攻击成本。

**修复**：生产设置 `springdoc.api-docs.enabled=false`、`swagger-ui.enabled=false`，或限制为管理员/内网。

---

### P2 / Medium：XSS Filter 不覆盖 JSON Body，且不应把过滤当作主要防线

`XssFilter` 只覆盖 request parameter/header；`@RequestBody` JSON 由 Jackson 直接读取输入流，不经过 `getParameter()`。动态/评论内容可保存 HTML/脚本字符串。

这不必然构成后端漏洞——正确防线是**输出上下文编码**。如果前端使用 React 普通文本插值通常安全；如果使用 `dangerouslySetInnerHTML` 或拼接 HTML，则可能形成存储型 XSS。

**修复**：

- 前端禁止不可信内容进入 `innerHTML`；
- 富文本场景在明确字段上用严格 HTML allowlist 清洗；纯文本原样存储、输出时编码；
- 添加存储型 XSS E2E 测试：`<img src=x onerror=...>` 必须作为文本展示；
- 不清洗 Authorization 等安全头，避免认证语义变化。

---

## 3. 已通过/正向安全控制

- 未认证业务接口应由 `anyRequest().authenticated()` 拒绝；
- JWT 使用签名校验、issuer、exp/nbf，篡改 payload 应被拒绝；
- 写接口从认证上下文获取 userId；
- 动态删除、通知详情/删除有所有权检查；
- 游标、limit、内容长度等 DTO 校验较完整；
- 幂等状态机、限流、数据库唯一约束构成写链路多层保护；
- OSS `dir` 路径段禁止 `.`、`..` 与非法字符，避免直接目录穿越；
- Maven 现有测试：0 failures / 0 errors。

---

## 4. PoC 脚本

文件：`scripts/security-audit.py`

本地实例启动后执行：

```bash
cd C:/Users/xiaohongfu/IdeaProjects/xiaozhaovip-intern8
python scripts/security-audit.py --base-url http://127.0.0.1:8080
```

若演示密码不是默认值：

```bash
python scripts/security-audit.py \
  --base-url http://127.0.0.1:8080 \
  --demo-password '<本地演示密码>'
```

输出：

- 控制台风险清单；
- `SECURITY_AUDIT_RESULTS.json` 机器可读结果；
- 退出码 `1` 表示发现风险，`0` 表示全部通过，`2` 表示目标不可达。

脚本会安全验证：

1. 未认证 API 是否拒绝；
2. 是否能为任意 userId 签发 JWT；
3. 是否能为默认管理员 ID 签发 JWT（不调用系统通知）；
4. JWT payload 篡改是否被拒绝；
5. Actuator/Swagger 匿名暴露；
6. 恶意 Origin 的 CORS 预检；
7. 8 次错误登录是否触发限流；
8. 5001 字符动态是否被 DTO 校验拒绝（不会落库）。

脚本明确不会调用：上传、删除、发布成功请求、关注、评论、点赞、系统通知或任何外网目标。

---

## 5. 修复路线（适合秋招 SP 项目叙事）

### 第一阶段：当天可完成（P0 边界收敛）

- 默认 Profile 改为 prod/无默认值；
- demo 登录增加 enabled + loopback + 白名单；
- 移除演示密码和管理员 ID 默认值；
- CORS 改配置化 allowlist；
- Actuator/Swagger 按 Profile 收敛；
- 增加安全集成测试。

### 第二阶段：1~2 天（对象安全与认证工程化）

- OSS 用户前缀隔离 + file_asset 所有权表；
- MIME/魔数/大小/配额验证；
- 登录限流、审计和 HTTP 状态规范；
- 管理员改 RBAC + `@PreAuthorize`。

### 第三阶段：持续工程化

- CI：依赖漏洞扫描（OWASP Dependency-Check/Trivy）、secret scanning、CodeQL；
- DAST：在 CI 启动本地实例后跑 `security-audit.py`；
- 安全指标：认证失败、限流命中、JWT 无效、越权拒绝、OSS 拒绝次数；
- 威胁建模：身份、对象存储、消息队列、缓存和管理面分区。

---

## 6. 面试表达建议

> “我没有把安全理解成加一个 JWT。先做威胁建模，发现默认 dev Profile、演示登录任意 userId 和默认管理员 ID 组合后会形成完整的身份冒充链；随后用无破坏 PoC 复现，并把修复拆成启动时 fail-fast、认证白名单/RBAC、CORS/Actuator 管理面收敛、OSS 对象级所有权校验。最后把 PoC 接入 CI，保证安全边界不会回归。”

这比泛泛描述“用了 Spring Security、JWT、XSS Filter”更能体现工程能力和安全思维。
