# DayZero Gateway Phase G2-F1 完成报告

## 1. 最终状态

**PHASE_G2_F1_COMPLETE**

本轮只修复 `server/dayzero-ai-gateway/` 中的安全与部署阻塞项，未触碰 AI 业务协议、Android、Supabase、Room、ECS 现网，未执行 Git 写操作，未部署。

## 2. 审计问题逐项修复表

| 审计类别 | 修复项 | 关键文件 | 状态 |
|---|---|---|---|
| 日志安全 | 改为白名单式事件记录 | `src/logger.ts` | ✅ |
| 日志安全 | 不再记录 raw error/message/detail/cause/stack | `src/logger.ts`, `src/errors.ts`, handlers | ✅ |
| 日志安全 | 不再泄露 Base64/data URL/Authorization/JWT/sub/userText/Prompt | `src/logger.ts` | ✅ |
| 异常处理 | Kimi response body 不回显给客户端或进入日志 | `src/errors.ts`, `src/handlers/assistant_turn_v2.ts` | ✅ |
| 异常处理 | 使用固定 UpstreamErrorCode 与安全文案 | `src/errors.ts` | ✅ |
| Readiness | `/ready` 只检查规范化 Supabase 字段 | `src/handlers/ready.ts` | ✅ |
| Healthcheck | Docker healthcheck 访问 `/ready` | `src/healthcheck.ts`, `docker-compose*.yml` | ✅ |
| JWKS | fetch 超时 4s，映射为 JWKS_UNAVAILABLE | `src/auth.ts` | ✅ |
| JWKS | 全局 single-flight，并发共享一次 fetch | `src/auth.ts` | ✅ |
| JWKS | 未知 kid 单请求刷新一次，并发共享刷新 | `src/auth.ts` | ✅ |
| JWKS | 失败后 5s cooldown，成功后清除 | `src/auth.ts` | ✅ |
| JWT | `aud` 字符串/数组测试 | `src/auth_test.ts` | ✅ |
| JWT | 0 秒 clock tolerance 策略明确并测试 | `src/auth.ts`, `src/auth_test.ts` | ✅ |
| Docker | 新增 `.dockerignore` | `.dockerignore` | ✅ |
| Docker | 生产 Compose 使用 digest-only image 引用 | `docker-compose.production.yml` | ✅ |
| Docker | source bundle hash 算法写入部署文档 | `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` | ✅ |
| Compose | 安全测试 fixture 可用 | `.env.compose.test.example` | ✅ |
| Compose | `docker compose config` 通过 | `docker-compose.production.yml` | ✅ |
| ACME/回滚 | 首签、续期、失败不切流流程明确 | `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` | ✅ |
| ACME/回滚 | v1 回滚命令有效（不使用 `down <service>`） | `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` | ✅ |
| Nginx | `proxy_next_upstream off` | `nginx.production.conf.template` | ✅ |

## 3. 日志安全

- `src/logger.ts` 已改为显式白名单模型。
- 只允许 `requestId`、`userIdDigest`、`routePath`、`errorCode`、`httpStatus`、`retryable`、timing 数值等固定字段。
- 丢弃所有对象、数组、异常、`message`、`detail`、`cause`、`stack`、`imagePath`、`userText`、`prompt`、Base64、data URL、`Authorization`、`JWT`、原始 `sub`。
- `path` 在 `src/main.ts` 中已重命名为 `routePath`，仅记录 HTTP route path。
- 新增 17 个 logger 安全测试。

## 4. Readiness 与 Healthcheck

- `src/handlers/ready.ts` 仅检查 `supabaseJwksUrl`、`supabaseIssuer`、`supabaseAudience` 三个规范化字段，不感知变量原始名称。
- 兼容 legacy 的转换仍只发生在 `src/config.ts` 的 `loadConfig()`。
- `/ready` 静态检查，不联网、不拉 JWKS、不访问 Supabase。
- `src/healthcheck.ts` 改为访问 `/ready`。
- `docker-compose.production.yml` 中 Gateway healthcheck 使用 readiness；Nginx 使用 `condition: service_healthy`。
- 新增 ready 测试覆盖新/旧变量、缺字段、URL 非法、config 值不回显等场景。

## 5. JWKS 稳定性

- `src/auth.ts` 中 `JWKS_FETCH_TIMEOUT_MS = 4000`，使用 `AbortSignal.timeout`。
- 引入基于 epoch 的 single-flight：`getJWKS(url, allowRefresh)` 保证同一时刻只有一个 fetch，并发未知 kid 共享一次刷新。
- 失败后进入 `JWKS_COOLDOWN_MS = 5000` 退避；成功后清除失败状态。
- 未知 kid 触发 refresh 一次，刷新后仍找不到返回 `JWT_KEY_NOT_FOUND`。
- 不记录 JWKS response body、key 内容、JWT、kid 值。
- 新增 4 个 JWKS 并发/退避/清除测试。

## 6. Docker/Compose

- 新增 `.dockerignore`，排除 `.env`、证书、私钥、`.git`、日志、测试输出等，保留 `deno.json`、`deno.lock`、`src`。
- `docker-compose.production.yml` 使用 `image: ${GATEWAY_IMAGE_REPOSITORY}@${GATEWAY_IMAGE_DIGEST}` 强制 digest-only。
- `ENV_FILE` 变量使测试 fixture 可替换真实 `.env`。
- 新增 `.env.compose.test.example`（全假值，标注 TEST ONLY）。
- 本地 Docker build 成功，OCI labels 正确；容器 `/health=200`、`/ready=200`、无 Token AI POST 返回 401、配置缺失时 `/ready=503` 且 healthcheck 失败。
- `docker compose --env-file .env.compose.test.example -f docker-compose.production.yml config` 通过。

## 7. ACME 与回滚

- `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` 更新：
  - 明确首次 ACME webroot 签发流程、DNS 验证要求（至少两种独立证据）。
  - 明确续期 dry-run 与 `nginx -t` renewal hook。
  - 明确证书失败不切流。
  - 修正回滚命令为有效的 `docker compose up -d`，恢复 v1 digest 与旧 Nginx 配置。
  - 增加 source bundle hash 算法与镜像 digest-only 构建命令。

## 8. 测试结果

```text
deno fmt --check  ✅
deno lint         ✅
deno check src/main.ts ✅
deno task test    ✅ 127 passed / 0 failed
```

额外验证：
- Docker build 成功（`dayzero-ai-gateway:g2-f1-local`）。
- Docker image inspect OCI labels正确。
- Docker history 无 obvious secrets。
- 容器 `/health=200`、`/ready=200`、无 Token AI POST → 401。
- 配置缺失容器 `/health=200`、`/ready=503`、healthcheck 失败。
- `nginx -t` 通过（临时自签名证书）。
- `docker compose config` 通过（使用安全 fixture）。
- 日志假敏感标记扫描通过（新增测试断言）。
- Kimi 错误 body 不回显测试通过（400/429/500/非 JSON）。

## 9. 冻结文件 SHA-256

开始与结束 hash 一致，未发生变化：

```text
src/shared/assistant_upstream_timeout.ts      9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37
src/shared/prompt.ts                          f616f8b177f83fcffd94bc55483c526521233beb9e34fbede329934d2483f66a
src/shared/normalization.ts                   bba2537fbb09f76dbedfae53d93fcb63fd16ba81fe8da2d8a63545d14920d369
src/shared/assistant_vision.ts                847974511740d89bc616f5f2153c2a16a8abac54d19160acf1388ca36f04c9bd
src/shared/explicit_photo_meal_assignment.ts  c28f3e2918c7489bb2ba5e781eb3307fed3ade039477689126657ea11009aad7
```

本轮 source bundle hash：`310e0d622aa7c8254ffef3060e0467e80d62ae23c0613fde234a01858d2d4ddf`

## 10. 未完成项

- 真实域名、DNS、生产 HTTPS 证书尚未提供（按计划等待后续阶段）。
- ECS 现网尚未部署，Nginx 未切换。
- 未执行 Gemini 3.1 Pro High 独立只读复验（待本轮完成后由用户安排）。

## 11. 范围声明

确认：
- 未修改 Android。
- 未修改 Supabase Edge Functions / Auth / Database / RLS / Storage。
- 未修改 Room。
- 未修改同步系统。
- 未修改 ECS 现网。
- 未部署。
- 未调用真实 Kimi。
- 未使用真实 JWT/Secret。
- 未执行 Git add / commit / push / pull / checkout / reset / clean / restore。
- 未删除已有文件。

## 12. 下一阶段

建议下一阶段：
- 使用 Gemini 3.1 Pro High 对 `server/dayzero-ai-gateway/` 进行独立只读安全复验。
- 提供真实域名、DNS、生产证书后，按 `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` 执行 staging 验证与受控 ECS 部署。
