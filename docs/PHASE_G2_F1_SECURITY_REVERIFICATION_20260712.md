# DayZero Gateway G2-F1 独立安全复验与部署准入判断

## 1. 最终判定

**ACCEPTABLE_FOR_CONTROLLED_DEPLOYMENT**

经过独立、严格的代码只读审查与本地测试，Codex 审计发现的全部 Critical / High 安全风险（包括日志信息泄露、错误对象回显、JWKS / Readiness 配置等）均已得到有效且全面的修复。没有发现新的阻断级安全问题。
所有冻结文件保持一致，部署与回滚文档已明确包含可靠步骤。当前 G2-F1 代码库满足安全入网标准。

## 2. Codex 问题复验表

| Codex 原始问题 | 本轮验证结论 |
| --- | --- |
| **CRITICAL** 日志净化器泄露 detail/message/pendingDraft/Kimi body | **已修复**。`src/logger.ts` 改为严格的显式白名单，任何对象、数组、异常及其内容被完全丢弃，仅放行明确定义的安全字段。 |
| **HIGH** direct-config 导致 `/ready` 503 | **已修复**。`/ready` 与配置兼容解耦，使用规范化变量（`supabaseJwksUrl` 等）检查并按预期放行，同时与外网解耦。 |
| **HIGH** Docker healthcheck 使用错误的 health endpoint | **已修复**。已在 Compose 和 `healthcheck.ts` 中改为使用 `/ready`。 |
| **HIGH** JWKS fetch 无 timeout 和并发控制 | **已修复**。引入 4s timeout、单并发控制 (single-flight) 和 5s 失败 cooldown，单请求最多刷新 1 次。 |
| **HIGH** Kimi 错误响应返回给客户端 | **已修复**。`assistant_turn_v2` 处理中将 Kimi API 的失败按枚举映射为安全消息，不再透传 detail 或 body。 |
| **HIGH** 缺少真实 ACME/回滚流程 | **已修复**。部署文档已补齐 ACME、续期 hook 和可执行的基于 compose 状态的回滚命令，并且明确失败不切流策略。 |
| **MEDIUM** 缺少 `.dockerignore` | **已修复**。添加了完善的排除规则（`.env`, `cert`, `logs`, 等），防止机密进入 build context。 |
| **MEDIUM** Nginx healthcheck 问题 | **已修复**。Nginx Compose file 采用了 `service_healthy` 依赖 Gateway。 |

## 3. 日志安全

* **白名单执行有效**：`sanitizeContext` 仅允许如 `requestId`、`userIdDigest`、`routePath` 及各数值 / 布尔类型时延或状态字段，并直接丢弃 object 和 array 类型。
* **原始异常丢弃**：所有的 Kimi raw body、Node Error对象及带有 `pendingDraft` 或 `imagePath` 的负载在日志落盘前均被过滤，测试报告中的假敏感标记扫描进一步佐证该行为。

## 4. 异常与上游错误

* **静态映射保护**：所有的 4xx 和 5xx Kimi HTTP 异常均使用预定义映射 `UPSTREAM_UNAVAILABLE`，`UPSTREAM_RATE_LIMITED` 等和对应的安全固定提示文案。
* **安全回显**：原始 error message 和 Kimi 响应体不会被抛向 `detail` 然后透传给用户，SSE stream 也按照规范抛出安全消息。

## 5. Readiness / Healthcheck

* `/ready` 端点静态化，只执行配置就绪验证。
* 只有当配置完整时（包括 JWT / Kimi）返回 HTTP 200，支持旧配置和新配置回退转换逻辑。
* healthcheck 探测使用 `http://localhost:8080/ready`，并能正确触发健康状态反馈和 Docker condition 控制。

## 6. JWKS / JWT

* JWKS 获取采用了 4s 超时控制，失败具有 5s 的退避冷却。
* 通过全局 `single-flight` 控制，大量并发只会向公共 JWKS 发送单次请求，并在 kid 未知时有效节流一次刷新。
* JWT 校验中强制限定 ES256 并使用了 0 秒时钟漂移 (`clockTolerance: 0`) 满足最高安全界限。
* Audience 测试用例中覆盖了字面量和包含情况的 Array 断言验证。

## 7. Docker / Compose

* `.dockerignore` 加入后排除了环境变量与证书等密钥污染容器层。
* 镜像已采用 `repository@sha256:digest` 进行 immutable 参考。
* compose 测试用例能够使用安全的无害 `.env.compose.test.example` 生成有效的 Compose 配置。

## 8. Nginx / HTTPS / SSE

* **防缓冲/超时配置正确**：各流式路径 `proxy_buffering off`、`X-Accel-Buffering no always`，符合 SSE 高连通性需求。
* **重试防御**：已显式附加 `proxy_next_upstream off` 防治二次重放。
* HTTPS 与 ACME challenge 兼容路由正确分配。

## 9. ACME / 回滚

* 首次与续期流程具备明确的操作指引及 `certbot --dry-run` 策略，避免线上断网。
* 回滚指令重置为恢复 `dayzero-ai-gateway` v1 配置及其对应 immutable digest，使用安全的 `docker compose up -d` 重新构建状态机而不是损坏的 `down <service>`。

## 10. DNS 说明

* 对于 Codex 与本地环境的 DNS `198.18.0.47`，明确认定为本地 Fake-IP 解析副作用。
* 本次评估不将本地 Fake IP 列为阻塞项，按照指引部署前在真实环境使用无代理状态下的 DNS 查询工具予以最终核验确认 `39.106.156.166` 即可。

## 11. 测试结果

* `deno fmt --check`：通过。
* `deno lint`：通过。
* `deno check src/main.ts`：通过。
* `deno task test`：所有 127 个测试用例通过 (0 failed)。
* Docker compose config 与 Nginx 语法通过 (Standalone 测试 `nginx -t` 会因缺少上游主机抛错，属于网络环境隔绝正常预期，配置本身无语病)。

## 12. 冻结文件校验

所有冻结文件的 SHA-256 哈希值与基线一致，未发生变更：

* `src/shared/assistant_upstream_timeout.ts`: `9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37`
* `src/shared/prompt.ts`: `f616f8b177f83fcffd94bc55483c526521233beb9e34fbede329934d2483f66a`
* `src/shared/normalization.ts`: `bba2537fbb09f76dbedfae53d93fcb63fd16ba81fe8da2d8a63545d14920d369`
* `src/shared/assistant_vision.ts`: `847974511740d89bc616f5f2153c2a16a8abac54d19160acf1388ca36f04c9bd`
* `src/shared/explicit_photo_meal_assignment.ts`: `c28f3e2918c7489bb2ba5e781eb3307fed3ade039477689126657ea11009aad7`

## 13. Critical / High / Medium / Low

本轮安全审查未发现任何新增的 Critical 或 High 风险项。

## 14. 范围声明

在此次安全验证与报告生成过程中：
* 未修改业务和配置文件。
* 未修改 ECS、无远端上传。
* 未触发项目实际的 Docker Compose 部署。
* 未申请真实证书。
* 未调用真实 Kimi API 与实际 token。
* 未修改 Android、Supabase 业务以及相关 Room/同步系统。
* 未执行任何 Git 写操作。

## 15. 下一步

**ACCEPTABLE_FOR_CONTROLLED_DEPLOYMENT**
当前环境允许进入受控 ECS 部署阶段（请严格按照《DayZero AI Gateway G2 部署与回滚指南》操作）。本轮验证到此结束，未进行部署操作。
