# DayZero Gateway G2 独立安全验收

审计日期：2026-07-12  
审计性质：G1 + G2 独立、只读安全验收；未部署、未修改 ECS、未调用 Kimi。  
审计对象：本地 `server/dayzero-ai-gateway/` 的 G1/G2 实现、生产模板、部署文档，以及 ECS 上仍在运行的 G1 前 v1。

## 1. 最终判定

**NOT_ACCEPTABLE_FOR_DEPLOYMENT**

当前本地 G2 不具备进入受控 ECS 部署的准入条件。已验证的阻断原因如下：

1. **CRITICAL**：日志净化器未将 `pendingDraft`、`detail`、`message`、`imagePath`、`error`、`cause`、`stack` 等字段视为敏感字段；异常路径实际将原始异常 `message` 写入日志。使用只含假标记的数据进行的本地捕获验证证明上述字段可原样穿透。`pendingDraft`、图片路径、上游/解析异常文本或嵌套错误对象可能包含用户内容或敏感上下文。
2. **HIGH**：直接使用 G2 推荐的 `SUPABASE_JWKS_URL`、`SUPABASE_ISSUER`、`SUPABASE_AUDIENCE`，而不设置 legacy `SUPABASE_URL` / `SUPABASE_JWT_AUDIENCE` 时，`/ready` 实测返回 `503 INVALID_SUPABASE_URL`。这与 `src/config.ts` 的优先级设计冲突。
3. **HIGH**：Compose 健康检查请求 `/health` 而非 `/ready`，因此上述不可接流量状态仍会被 Docker 视为健康；G2 报告中“healthcheck 使用 readiness”的陈述与实际源码不一致。
4. **HIGH**：部署文档没有真实 ACME/Let’s Encrypt 申请与证书失败策略；回滚命令 `docker compose ... down dayzero-ai-gateway` 无效，不能作为可靠的 v1 回滚步骤。
5. **HIGH**：本审计环境经指定递归解析器观测到 `api.dayzero.cn` 的 A 记录为 `198.18.0.47`，并非要求的 `39.106.156.166`；该结果也可能受到本地网络的 DNS 改写影响，故不能据此证明生产 DNS 已正确生效。部署前必须从独立、可追溯的权威 DNS 视角复核。

Nginx HTTPS/SSE 模板、镜像非 root 运行、8080 不映射宿主机、JWT 基本验签约束、冻结业务文件哈希等项目有可验证正向证据，但不足以抵消上述 Critical/High 风险。

## 2. 验收基线

| 项目 | 当前值 | 证据 |
| --- | --- | --- |
| Git commit | `2bd958e2444256f289c7f14dc37629c26eab9669` | `git rev-parse HEAD` |
| Supabase Streaming 基线 | `assistant-turn-v2-stream` v25 | 用户提供的权威基线；本轮未变更 Supabase |
| Supabase Fallback 基线 | `assistant-turn-v2` v31 | 同上 |
| 模型 | `kimi-k2.6` | 用户提供的冻结基线；本轮未调用 Kimi |
| Stream Prompt 版本 | `stream_compact_v7_deterministic_multi_meal_photo_assignment` | 用户提供的冻结基线；哈希见第 12 节 |
| Fallback Prompt 版本 | `compact_v8_deterministic_multi_meal_photo_assignment` | 用户提供的冻结基线；哈希见第 12 节 |
| Streaming candidate race | text 15 s；continuation media 20 s；current attachments 35 s | 用户提供的权威基线；冻结 timeout 文件哈希已匹配 |
| Fallback timeout | 50 s total | 用户提供的权威基线；冻结 timeout 文件哈希已匹配 |
| 本地 G2 部署状态 | 未部署 | `docs/PHASE_G2_COMPLETION_REPORT_20260712.md`；本轮 ECS 只读检查也确认仍为 v1 镜像 |
| 生产证书 | 未签发 | 用户提供状态；ECS `ss` 仅见本机 80 监听，无 443 本机监听 |

开始 Git 快照：`git status --short -uall` 共 706 条既有改动（304 删除、12 修改、390 未跟踪）；状态文本 SHA-256 为 `6f2c8b3b8c0c9095936faccf9151acc322d28963799cecd28d0cc5efce37dcb5`。这些改动在本审计开始前已存在，未恢复、覆盖或归因于本轮。

## 3. G1 协议入口

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 正式与旧兼容路径 | **已验证**。四条路径均直接复用同一 stream/fallback handler；未见内部 HTTP 转发或第二次请求。 | `server/dayzero-ai-gateway/src/main.ts:12-17,83-130` |
| `/health` | **已验证**。GET 返回进程存活语义。 | `src/main.ts:63-71`、`src/handlers/health.ts:1-19` |
| `/ready` | **不通过**。仅校验静态配置，不访问 Kimi、DB 或 JWKS 是有意的热路径设计；但它错误依赖 legacy 配置字段，见第 4 节。 | `src/handlers/ready.ts:22-107` |
| Request ID | **已验证**。所有主路由均套用响应头；合法入站 ID 复用，不合法 ID 替换。 | `src/main.ts:29-53`、`src/request_id.ts:1-34`、`src/main_test.ts:142-162` |
| 非对象 / null / primitive / array / malformed JSON | **已验证**。稳定 400，解析发生在 prompt/Kimi 前。 | `src/body_reader.ts:44-68`、`src/handlers/assistant_turn_v2.ts:57-109` |
| `interaction_result` 入口规则 | **已验证**。缺少 `interactionResult` 被拒绝，携带附件被拒绝。 | `src/shared/request_parser.ts:139-150`、`src/shared/assistant_vision.ts:76-105`、相应 handler tests |
| 404/405/CORS | **部分已验证**。OPTIONS 与 CORS 在入口统一处理，未知路径 404；405 的专门测试证据不足。 | `src/main.ts:50-81,132-140`、`src/cors.ts:1-44` |
| SSE 事件顺序 | **已验证（静态及单测）**。正常分支为 `status → reply_delta* → final → debug_timing → done`，actions 仅在 final。 | `src/handlers/assistant_turn_v2_stream.ts:223-242`、`src/handlers/assistant_turn_v2_stream_test.ts` |

`/ready` 不探测在线 JWKS 本身不是阻塞：readiness 不应把每个探针变成 Supabase 热路径，也不应因瞬态网络波动重启服务。风险在于当前静态配置检查与实际 G2 直接配置不一致；应修复为检查 `supabaseJwksUrl`、`supabaseIssuer`、`supabaseAudience` 的格式与非空性，并保留“不联网”的语义。

## 4. JWT/JWKS

### 已验证的安全属性

| 项目 | 结论 | 证据 |
| --- | --- | --- |
| 仅 ES256 | 已验证 | `src/auth.ts:162-167,195-202`；`src/auth_test.ts` |
| 必须有 `kid` | 已验证 | `src/auth.ts:165-167` |
| `iss` / `aud` / 签名 | 已验证 | `src/auth.ts:195-207` |
| 必须有 `exp`、非空 `sub` | 已验证 | `src/auth.ts:210-216` |
| unknown `kid` 单次刷新（单请求） | 已验证 | `src/auth.ts:180-190`；`src/auth_test.ts:162-193` |
| JWKS 失败不绕过 | 已验证，返回固定 401 code | `src/auth.ts:172-187`；`src/auth_test.ts:338-372` |
| production 禁用 auth fail-fast | 已验证 | `src/config.ts:127-135`；`src/config_test.ts:39-58` |
| 新变量优先 legacy alias | 已验证 | `src/config.ts:139-154`；`src/config_test.ts:61-79` |
| auth 失败不进入 Kimi | 已验证（handler 调用顺序及测试 fake fetch） | `src/handlers/assistant_turn_v2.ts:45-119`、`src/handlers/assistant_turn_v2_stream.ts:57-159` |
| 客户端不接收原始 jose 错误 | 已验证 | `src/auth.ts:204-207` |
| `APP_ENV` 缺失 | 已验证为 fail-closed：默认 production，不会误入 development | `src/config.ts:79-90` |

### 不通过、未验证与建议

| 风险 | 严重度 | 证据与影响 | 精确建议 |
| --- | --- | --- | --- |
| 直接 JWKS 配置使 `/ready` 503 | HIGH | `src/config.ts:139-154` 允许只设置新变量；但 `src/handlers/ready.ts:71-86` 仅检查 legacy `supabaseUrl` / `supabaseJwtAudience`。使用仅含假值的新变量的本地 Deno 和临时容器均复现 `/health=200`、`/ready=503 INVALID_SUPABASE_URL`。 | `ready.ts` 只使用规范字段 `supabaseJwksUrl`、`supabaseIssuer`、`supabaseAudience`；新增 direct-config readiness 测试。 |
| JWKS 获取无超时、无并发 single-flight | HIGH | `src/auth.ts:45-75` 的 `fetch` 没有 AbortSignal/timeout，缓存也不保存 in-flight Promise。并发首请求或并发未知 kid 会各自 fetch；“每请求最多一次刷新”不等于“全局一次刷新”。 | 加有限时的 fetch、in-flight promise 去重、失败短暂退避；新增并发 unknown-kid/JWKS 超时测试。 |
| `aud` 为 JWT 合法数组的专门测试缺失 | MEDIUM | 实现把 audience 交给 `jose.jwtVerify`（通常支持 string/array claim）；现有测试只覆盖字符串错误 audience。 | 用 fake ES256 JWT 明确覆盖 `aud: [expected, other]` 和不含 expected 的数组。 |
| 时钟偏差策略未显式定义 | MEDIUM | `jwtVerify` 没有 `clockTolerance` 参数，依赖库默认行为；测试未说明边界。 | 以安全需求指定容忍值（或明确 0），写入配置/文档并测试 exp 边界。 |
| 真实 Supabase 当前算法未能独立确认 | MEDIUM / UNVERIFIED | 本轮没有真实用户 token；受限网络下公共 JWKS 的只读请求未得到可解析结果，不能证明现网一定为 ES256。代码对非 ES256 必拒绝。 | 部署前使用公开 JWKS 元数据或专用 fake-token 验收确认项目签名算法、issuer、audience；不得记录 key 内容。 |

## 5. 日志安全

**结论：不通过（CRITICAL）。**

`digestUserId()` 确实使用 SHA-256，并默认截取 16 个十六进制字符，稳定且不可逆性足以避免直接记录原始 `sub`：`src/logger.ts:82-99`。但整体日志安全不成立。

本地、无外部调用的捕获测试以明显的假敏感标记验证 `sanitizeContext()`：`userText` 被遮蔽，而 `pendingDraft`、`detail`、嵌套 `error` 和 `imagePath` 保留原值。这是实际可复现结果，不是推测。

| 发现 | 严重度 | 证据 | 后果与建议 |
| --- | --- | --- | --- |
| key allow-list 不完整 | CRITICAL | `src/logger.ts:5-35,60-79` 只含 `usertext` 等少量字段，不含 `pendingDraft`、`detail`、`message`、`error`、`cause`、`stack`、`path`/`imagePath` 及常见 snake_case。 | 嵌套对象按 key 递归保留，用户数据/异常正文可进入日志。改为默认最小化白名单（requestId、摘要 user id、枚举、计数、时延、状态），或补齐递归拒绝规则与值类型限制。 |
| 通用异常直接记录 raw error message | CRITICAL | `src/errors.ts:28-40` 将 `error.message` 放入 `detail`；`detail` 不敏感。 | 上游、解析或库异常可带入请求片段/第三方文本。日志只记录固定 error enum 和安全 requestId。 |
| SSE 异常直接记录 raw error message | CRITICAL | `src/handlers/assistant_turn_v2_stream.ts:243-257` 把异常 message 放入 context。 | SSE 处理失败时可泄露上游或用户相关文本。只记录映射后的 code。 |
| Fallback 将 Kimi 4xx/5xx body message 回显客户端 | HIGH | `src/handlers/assistant_turn_v2.ts:131-144` 读取 `response.json()` 并返回 `detail`。 | 违反“不输出第三方响应正文”；可能泄露上游细节。固定安全错误码/文案，详细信息仅采用安全枚举。 |
| 现有测试覆盖不足 | HIGH | `src/logger_test.ts` 覆盖 base64、data URL、Authorization、Bearer 和 `userText`，未覆盖上表字段或 Error/cause/stack。 | 补齐正反例和所有 logger 调用点的捕获测试；在修复前禁止部署。 |

## 6. Nginx HTTPS/SSE

### 通过项

对 `server/dayzero-ai-gateway/nginx.production.conf.template` 用临时自签名证书、临时 BOM-free 配置及临时 Docker 网络执行 `nginx -t`，退出码 0。临时资源已清除，未申请真实证书。

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 80 与 ACME | 模板正确：`/.well-known/acme-challenge/` 不跳转，其余 301 HTTPS；本地验证 ACME fixture 404（不跳转） | `nginx.production.conf.template:31-41`；临时 Nginx 检查 |
| TLS 与 server name | TLS 1.2/1.3，`server_name api.dayzero.cn` | `nginx.production.conf.template:45-54` |
| 路由 | `/health`、`/ready`、新旧 stream/fallback 均存在 | 模板各 location；`nginx -t` |
| 转发头 | Authorization、X-Request-Id、Host、X-Forwarded-* 均转发 | 模板各 proxy location |
| SSE 防缓冲 | 已验证：HTTP/1.1、`proxy_buffering off`、`proxy_cache off`、`proxy_request_buffering off`、3600 s read/send、`X-Accel-Buffering no always`、`gzip off` | 模板 stream locations（新路径约 `100-122`，旧路径约 `145-166`）；临时 401 SSE 响应也含 `X-Accel-Buffering: no` |
| 无跨服务 fallback | 已验证静态配置中无 Supabase upstream、无第二 Gateway upstream | 模板全文 |
| Nginx 不覆盖有效 request ID | 已验证：临时 HTTPS `/health` 返回传入的合法 ID | 临时网络验证 |

### 必须补齐或确认

| 发现 | 严重度 | 证据 | 建议 |
| --- | --- | --- | --- |
| 未显式禁用 `proxy_next_upstream` | LOW | 模板无该 directive；当前只有一个 upstream，未发现会自动调用 Supabase 或第二 Gateway 的配置。 | 为审计可读性明确加 `proxy_next_upstream off`（或等价限定），并测试 5xx 不重放。 |
| HSTS 尚未配置 | LOW（非首发阻塞） | 模板未设置 HSTS。 | 证书稳定、子域名策略确认后分阶段引入，首次 ACME/回滚期间不作为硬阻塞。 |
| 证书续期的安全 reload 未定义 | HIGH | 模板需要证书文件；部署文档没有 certbot/ACME 续期及 `nginx -t` 后安全 reload 流程。 | 在部署文档明确 ACME webroot、首次签发、续期 hook、先 `nginx -t` 再 reload，且失败不切流。 |

Android OkHttp 不依赖浏览器 CORS；模板的 CORS 是兼容性配置，不应把是否需要 CORS 作为 Android HTTPS 准入条件。

## 7. Docker/Compose/镜像

### 已验证

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Docker build | 成功；临时本地审计 tag，未推送 | `docker build` 退出码 0 |
| Deno / lockfile | `denoland/deno:2.9.0`、`--frozen` 可成功 build | `Dockerfile:1-38`、build 结果 |
| 非 root | 已验证为 `dayzero`（容器 uid 999） | `Dockerfile:31-38`、`docker image inspect`/临时容器 |
| 镜像标签 | OCI revision、created、version、source bundle labels 均存在且符合本次构建输入 | `Dockerfile:3-30`、`docker image inspect` |
| 文件系统与历史扫描 | 临时镜像未见 `.env`、`local.properties`、私钥/SSL 目录；history 未发现常见 Secret 字样 | `docker history`、临时 `test ! -e` 检查；未输出值 |
| 网络暴露 | Gateway 仅 `expose: 8080`，无 host 8080；Nginx 仅发布 80/443 | `docker-compose.production.yml:10-18,39-42` |
| 日志轮转与 restart | `unless-stopped`，json-file 50m × 5 | `docker-compose.production.yml:10-35,39-64` |
| manifest 回滚记录 | 模板记录 v1 tag/digest、G2 image、source bundle、VCS revision | `deployment.manifest.template.yml:1-43` |

### 不通过或缺口

| 发现 | 严重度 | 证据 | 建议 |
| --- | --- | --- | --- |
| Gateway healthcheck 错误使用 `/health` | HIGH | `src/healthcheck.ts:1-15`；Compose `:27-32`。直接新 JWT 配置复现 `/health=200`、`/ready=503`。 | 改为仅访问 `/ready`，并新增容器级健康测试。 |
| Nginx 仅依赖 `service_started` | MEDIUM | `docker-compose.production.yml:50-52`。Gateway 未 ready 时 Nginx 仍会启动。 | 结合正确 healthcheck 使用 `service_healthy`，或清楚记录可预期的 502/503 及启动顺序。 |
| Nginx healthcheck 请求 HTTP `/health` | MEDIUM | `docker-compose.production.yml:53-59`；生产模板把 HTTP 重定向 HTTPS。 | 使用 container 内 HTTPS probe（有证书后）或明确的 loopback health location；避免依赖 redirect/wget 行为。 |
| 缺少 `.dockerignore` | MEDIUM | `server/dayzero-ai-gateway/` 无 `.dockerignore`；Dockerfile 虽未 `COPY .env`，Docker build context 仍可能把未来 `.env` 发送给 daemon。 | 添加 `.dockerignore` 排除 `.env`、证书、私钥、local.properties、Git 凭据与构建产物。 |
| Compose 解析命令未能通过 | MEDIUM | 运行规定命令（以 `.env.example` 作为 CLI env）退出 1：项目目录不存在 `.env`，Compose 的 service `env_file: .env` 因而中止。 | 不创建真实 `.env`；提供安全的未跟踪 deployment fixture 或在受控服务器以实际 secret file 执行 `docker compose ... config` 并只记录变量名。 |
| tag 可被写成可变 tag | MEDIUM | Compose 仅引用 `${GATEWAY_IMAGE_TAG}`；manifest 虽留 digest 字段，模板未强制 image 使用 digest。 | 部署时设为 `repository@sha256:...`，核对 RepoDigest 后写入 manifest；保留 v1 digest。 |
| source bundle hash 计算规则未文档化 | MEDIUM | 五个文件可复算，canonical bundle 值为 `d41917f5a4189500291f0fb662251ae9a797316692629783b8df08784117f507`，但未发现正式算法说明。 | 文档固定排序、分隔符、文件清单和 SHA-256 算法，确保回滚可重现。 |

## 8. 域名与 ECS 预检

### 已完成的只读 ECS 检查

连接方式为 SSH root@`39.106.156.166`:22，仅运行 `docker ps`、`docker inspect`、`ss`、`df`、`free`、`nginx -t` 和容器内只读 HTTP probe；未上传、未 exec 修改、未重启。

| 项目 | 观察结果 | 结论 |
| --- | --- | --- |
| 现有 v1 | gateway v1 与 nginx 容器运行；Gateway 仅容器 8080，Nginx 发布 80 | 已验证旧链路容器仍在运行 |
| 当前 `/health` | ECS Nginx 容器 loopback probe 为 200 | 已验证 v1 健康端点可用 |
| 监听 | ECS `ss` 显示 80；未显示 host 8080 或 443 | 8080 未公网暴露；443 尚无本机 TLS listener，符合证书未签发状态 |
| 当前 Nginx | 1.31.2；`nginx -t` 成功；配置 SHA-256 `4969e95478df00ef07cb97b99a58c20183f25fcfce99822a44af668e1fe2ba1d` | 当前 v1 只有 HTTP/旧路径，不能视为 G2 HTTPS 配置已部署 |
| 资源 | 磁盘约 33 GiB 可用（40 GiB 中约 5.2 GiB 已用）；内存约 1.4 GiB 可用 | 拉取镜像空间初步可用；未执行 build/pull 压力操作 |
| DNS A | 通过指定的 `223.5.5.5` 与 `1.1.1.1` 查询均得到 `198.18.0.47` | 与要求 IP 不一致；因审计网络可能劫持 DNS，结果为 **UNVERIFIED / HIGH**，必须独立复核 |
| AAAA | 查询未返回 AAAA | 未发现冲突 AAAA 的证据，但仍建议部署前从权威 DNS 控制台复核 |

未能证明的项目：ECS 上 G2 源码/镜像尚未部署，故不存在可审计的 G2 RepoDigest、真实 production `.env`（值不应读取）、证书、ACME 目录或 G2 Compose。现有 v1 镜像的远端 history 未完成安全扫描，不能把本地 G2 镜像扫描结论外推给 v1。

## 9. 部署与回滚

`docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` 已包含 build/push、RepoDigest、备份、`nginx -t`、health/ready、无 token 401、SSE 观察、日志扫描和保留旧镜像等章节，属于可复用基础。

但以下事项使其尚非可执行的受控部署/runbook：

| 缺口 | 严重度 | 精确证据 | 必要修复 |
| --- | --- | --- | --- |
| ACME 流程缺失 | HIGH | 文档把证书当作前置条件，未给出申请、webroot、验证、续期或失败步骤。 | 明确 80 开放 → ACME challenge 配置 → 签发 → 校验文件权限 → `nginx -t` → 安全 reload；失败时不替换 v1 HTTP 服务。 |
| 回滚命令无效 | HIGH | 文档第 84 行：`docker compose -f docker-compose.production.yml down dayzero-ai-gateway`；`down` 不接受 service 参数。 | 以备份文件和已记录 v1 digest 为准，定义有效的 stop/up 或完整 compose restore 命令；每一步带检查点。 |
| 证书失败策略缺失 | HIGH | 未定义是保持旧 HTTP v1、停止切流还是恢复旧 Nginx。 | 明确定义“证书未签发或验证失败即不切换；旧 v1/旧 80 配置保持运行”的原则。 |
| manifest 与 image 不可变绑定不强制 | MEDIUM | 模板有 digest 字段，Compose 不强制 digest image reference。 | 部署批准记录必须同时含 image reference、RepoDigest、v1 rollback digest、配置与 Nginx SHA。 |
| G2 readiness 不能作为 smoke gate | HIGH | 第 4、7 节的 direct-config/readiness/healthcheck 矛盾。 | 先修复并在真实但不输出的环境变量名条件下验证 `/ready=200`。 |

## 10. 阻塞项

### Critical

1. **日志敏感内容泄露路径**：根因是 `sanitizeContext` 的黑名单不完整，而 `handleUnexpectedError` 和 SSE catch 传入 raw exception message。用户可见后果为聊天上下文、待处理卡片、图片路径、第三方错误文本或其他敏感异常信息进入服务器日志。证据：`src/logger.ts:5-79`、`src/errors.ts:28-40`、`src/handlers/assistant_turn_v2_stream.ts:243-257`，以及本轮 fake-marker 捕获结果。修改模块：logger、errors、fallback/stream handler；测试：递归对象、Error/cause/stack、pendingDraft、图片路径、Kimi error、SSE error 的日志快照，且不得含原标记。

### High

1. **G2 direct JWT 配置导致 `/ready` 失败**：见第 4 节。后果是部署后永远 unready；修改 `ready.ts` 并添加 container smoke test。
2. **Docker healthcheck 绕过 readiness**：见第 7 节。后果是编排器把不可接流量实例标为 healthy；修改 `healthcheck.ts` / Compose。
3. **JWKS fetch 无 timeout/并发合并**：见第 4 节。后果是认证依赖不可用时悬挂或放大请求；修改 `auth.ts` 并做并发测试。
4. **Kimi error body 客户端回显**：`src/handlers/assistant_turn_v2.ts:131-144`。后果是第三方响应正文暴露；用固定错误枚举取代 detail。
5. **ACME、证书失败策略和可靠回滚缺失**：见第 9 节。后果是无法安全完成 HTTPS 首发或失败恢复；先补 runbook，再部署。
6. **DNS 目标无法独立验证**：本环境解析不匹配目标 IP。后果是证书/流量可能到达错误主机；以权威控制台和外部 DNS 节点复核后记录证据。

### Medium

1. `aud` 数组与时钟偏差缺少边界测试/策略。
2. Nginx `service_started` 与 HTTP healthcheck 不反映 Gateway readiness。
3. 无 `.dockerignore`，未来 secret 可能进入 Docker build context。
4. Compose 规定的 config 命令因缺少安全 fixture `.env` 无法在本地通过。
5. image digest 和 source bundle 算法未强制/未文档化。
6. 真实 Supabase JWKS 算法、issuer/audience 仍未独立验证。

### Low

1. Nginx 未显式 `proxy_next_upstream off`；虽未发现第二 upstream，建议防御性固定。
2. HSTS 可在证书与回滚方案稳定后再引入，不作为首发硬阻塞。

## 11. 测试结果

所有下列测试均使用本地代码、fake JWT/JWKS/fake fetch 或临时容器；没有调用真实 Kimi，没有使用真实用户 Token。

| 命令 / 检查 | 退出码 | 实际结果 |
| --- | ---: | --- |
| `deno fmt --check` | 0 | Checked 40 files |
| `deno lint` | 0 | Checked 35 files |
| `deno check src/main.ts` | 0 | 类型检查通过 |
| `deno task test` | 0 | 100 passed / 0 failed，约 579 ms |
| fake direct-JWKS readiness 复现 | 0 | `/health=200`，`/ready=503 INVALID_SUPABASE_URL` |
| fake 日志捕获扫描 | 0 | `userText` 被遮蔽；`pendingDraft`、`detail`、嵌套 error、imagePath 未被遮蔽 |
| 本地 Docker build | 0 | 临时审计镜像构建成功 |
| `docker image inspect` | 0 | uid 999、仅 8080、OCI labels 存在 |
| Docker history / filesystem secret scan | 0 | 本地临时镜像未发现常见 secret 文件/字样；未输出任何值 |
| 临时容器 `/health` | 0 | 200 |
| 临时容器 `/ready`（direct 配置） | 0 | 503，发现问题 |
| 无 Token AI POST | 0 | 401；未触发 Kimi |
| 临时自签名 Nginx `nginx -t` | 0 | `syntax is ok` / `test is successful` |
| 临时 Nginx 网络测试 | 0 | HTTP redirect、ACME 非 redirect、HTTPS health、四条路由 401、SSE `X-Accel-Buffering: no` 均符合预期 |
| `docker compose --env-file .env.example -f docker-compose.production.yml config` | 1 | 因 service `env_file: .env` 不存在而失败；未创建或修改真实 `.env` |
| DNS A / AAAA 查询 | 0 | A 为 `198.18.0.47`，未见 AAAA；与要求 IP 不一致，需独立复核 |
| ECS SSH 只读状态 | 0 | v1 container running、v1 `/health` 200、Nginx `-t` 成功、host 8080 未暴露 |

## 12. 冻结文件校验

以下 SHA-256 均与 `docs/PHASE_G2_COMPLETION_REPORT_20260712.md` 的 G2 冻结值逐项一致：

| 文件 | SHA-256 | 结果 |
| --- | --- | --- |
| `src/shared/assistant_upstream_timeout.ts` | `9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37` | MATCH |
| `src/shared/prompt.ts` | `f616f8b177f83fcffd94bc55483c526521233beb9e34fbede329934d2483f66a` | MATCH |
| `src/shared/normalization.ts` | `bba2537fbb09f76dbedfae53d93fcb63fd16ba81fe8da2d8a63545d14920d369` | MATCH |
| `src/shared/assistant_vision.ts` | `847974511740d89bc616f5f2153c2a16a8abac54d19160acf1388ca36f04c9bd` | MATCH |
| `src/shared/explicit_photo_meal_assignment.ts` | `c28f3e2918c7489bb2ba5e781eb3307fed3ade039477689126657ea11009aad7` | MATCH |

按本轮审计定义（排序后的 `path + 空格 + file SHA-256`、LF 连接）得到 canonical bundle SHA-256：`d41917f5a4189500291f0fb662251ae9a797316692629783b8df08784117f507`。该算法尚未成为项目正式部署文档的一部分，不能把它当作现有发布准则。

## 13. 范围声明

* 未修改 Gateway、Android、Supabase Edge/Auth/Database/RLS/Storage、Room 或任何 ECS 配置。
* 未部署、未重启容器、未 reload Nginx、未申请或签发真实证书、未 push ACR。
* 未调用 Kimi，未使用真实用户 Token，未输出 Token、API Key、私钥、密码、Base64、完整 Prompt、完整请求或用户聊天内容。
* 未执行 `git add`、commit、push、pull、reset、clean、restore 或其他 Git 写操作。
* 本轮唯一预期本地文件写入是本报告 `docs/PHASE_G2_SECURITY_ACCEPTANCE_20260712.md`，这是用户明确要求的交付物；未改动项目源码/配置。开始前已有的 706 条 Git 状态记录保持原样，不作恢复或归因。
* 结束 Git 快照：`git status --short -uall` 共 707 条（304 删除、12 修改、391 未跟踪），状态文本 SHA-256 为 `8e95f845b2ab906bef2e7682e9c64ed983c036070401b03c0d10f2838d44f9b9`。相较开始快照，唯一新增状态项为本报告；其余既有状态未被本轮改变。`git diff --check` 未报告空白错误（仅输出既有 CRLF 提示）。

## 14. 下一步

在本轮发现全部修复并通过独立复验前，**不得部署 G2**。建议按依赖顺序创建受控施工任务：

1. 修复日志为白名单式安全事件记录；消除 raw exception/Kimi body 的日志与客户端回显；新增回归测试。
2. 修复 `/ready` 使用 direct JWKS/issuer/audience，令 Docker healthcheck 实际探测 `/ready`；增加容器级 direct-env smoke test。
3. 为 JWKS 增加连接/总超时、并发 single-flight、失败退避，明确 clock skew，补齐 `aud` 数组与并发刷新测试。
4. 补 `.dockerignore`，用 digest-only image reference，正式记录 source-bundle hash 算法。
5. 补全 ACME/证书续期/失败不切流/可执行 v1 回滚 runbook，并在安全 fixture 或真实受控环境中通过 `docker compose ... config`。
6. 从独立权威 DNS 视角确认 `api.dayzero.cn → 39.106.156.166`，确认无冲突 AAAA；再签发生产证书。
7. 仅在上述全绿后执行：唯一镜像 tag 与 RepoDigest、推送 ACR、签发证书、部署、Nginx 切换、健康/ready/401/SSE 烟雾测试；失败则按已验证命令回滚 v1。以上均不在本轮执行。
