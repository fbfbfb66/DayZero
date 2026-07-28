# DayZero Gateway Phase G2 完成报告

## 1. 最终状态

`PHASE_G2_COMPLETE`

本轮完成了 `server/dayzero-ai-gateway/` 本地的 JWT/JWKS 加固、日志脱敏、生产 Nginx/HTTPS/SSE 模板、Docker 可追溯性与 Compose 安全配置，并提供了部署/回滚材料。**未执行 ECS 部署**，真实域名与生产 HTTPS 证书仍缺失。

## 2. 修改文件

| 文件 | 说明 |
| --- | --- |
| `src/config.ts` | 新增 `APP_ENV`、`SUPABASE_JWKS_URL`、`SUPABASE_ISSUER`、`SUPABASE_AUDIENCE`；兼容旧变量；production auth fail-fast |
| `src/config_test.ts` | 新增配置测试（legacy alias、新变量优先、production fail-fast 等） |
| `src/auth.ts` | 严格 JWT 校验、固定 `AuthErrorCode`、单次 JWKS 刷新、无 Secret 日志 |
| `src/auth_test.ts` | 覆盖全部要求认证场景，共 20 个测试 |
| `src/logger.ts` | 新增 `digestUserId()`、扩展敏感 key 脱敏 |
| `src/logger_test.ts` | 新增 digest 与日志无敏感内容测试 |
| `src/main.ts` | 使用认证错误 code、向 handler 传递 `userIdDigest`、记录 `appEnv`/`usedLegacyEnv` |
| `src/handlers/assistant_turn_v2.ts` | 接受并记录 `userIdDigest` |
| `src/handlers/assistant_turn_v2_stream.ts` | 接受并记录 `userIdDigest` |
| `src/test_helpers.ts` | 补齐新 config 字段 |
| `.env.example` | 推荐新环境变量名，说明 legacy 兼容 |
| `Dockerfile` | 增加构建参数与 OCI labels，基础镜像升级为 `denoland/deno:2.9.0` |
| `docker-compose.production.yml` | 新建生产 Compose 模板 |
| `nginx.production.conf.template` | 新建生产 Nginx HTTPS/SSE 模板 |
| `deployment.manifest.template.yml` | 新建部署清单模板 |
| `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` | 部署顺序与回滚指南 |
| `docs/PROJECT_CONTEXT_FOR_CHATGPT.md` | 追加 Phase G2 摘要 |
| `docs/DEVELOPMENT_LOG.md` | 追加 Phase G2 详细记录 |

## 3. JWT/JWKS 行为

- 仅接受 `alg: ES256`。
- 必须存在非空 `kid`。
- 必须存在数值 `exp`；过期 Token 拒绝。
- payload 必须存在非空 `sub`。
- `iss` 与 `aud` 严格匹配配置值。
- 未知 `kid` 触发一次 JWKS 刷新并重试；刷新后仍失败返回 `JWT_KEY_NOT_FOUND`。
- JWKS 获取失败返回 `JWKS_UNAVAILABLE`，不绕过认证。
- `APP_ENV=production` 且 `ENABLE_AUTH=false` 时启动 fail-fast。
- 认证错误响应携带固定 `errorCode`，不暴露 jose 原始错误、claim 值或 Token 片段。
- 认证成功返回稳定的 SHA-256 用户摘要 `u_<hex>`，原始 `sub` 不进入日志。

## 4. 日志安全

- 新增 `digestUserId(rawSub)`，使用 Web Crypto SHA-256，输出 `u_<16位十六进制>`。
- 日志上下文自动脱敏：`authorization`、`sub`、`userText`、`recentMessages`、`todayRecord`、`interactionResult`、`prompt`、Base64、data URL、API Key 等。
- 认证错误日志仅记录固定 `errorCode` 与 `status`，不记录 jose message。
- 助手请求/成功日志携带 `userIdDigest` 与 `requestId`，不携带原始 sub 或用户聊天内容。

## 5. Nginx HTTPS/SSE 配置

`nginx.production.conf.template` 包含：

- HTTP 80：ACME challenge 路径 + 其余请求 301 到 HTTPS。
- HTTPS 443：TLS 1.2/1.3、证书/私钥占位符、`server_name __DAYZERO_DOMAIN__`。
- 路由：`/health`、`/ready`、`/api/ai/assistant-turn-v2`、`/api/ai/assistant-turn-v2-stream`，迁移期保留 `/assistant-turn-v2`、`/assistant-turn-v2-stream`。
- 通用代理 Header：`Host`、`X-Real-IP`、`X-Forwarded-For`、`X-Forwarded-Proto`、`Authorization`、`X-Request-Id`。
- Streaming 路径：`proxy_http_version 1.1`、`proxy_buffering off`、`proxy_cache off`、`proxy_request_buffering off`、`proxy_read_timeout 3600s`、`proxy_send_timeout 3600s`、`add_header X-Accel-Buffering no always`、`gzip off`。
- 明确的 `location / { return 404; }`，无 Supabase Edge fallback。
- 按 IP 限流 `limit_req zone=perip burst=10 nodelay`。

## 6. Docker 与 Compose

- `Dockerfile` 新增构建参数：`VCS_REF`、`SOURCE_BUNDLE_SHA`、`BUILD_DATE`、`IMAGE_VERSION`。
- OCI labels：`org.opencontainers.image.revision`、`created`、`version`、`source`，以及自定义 `com.dayzero.source_bundle_sha`。
- 基础镜像更新为 `denoland/deno:2.9.0` 以兼容当前 `deno.lock` v5。
- `docker-compose.production.yml`：
  - Gateway 仅 `expose: 8080`，不映射宿主机。
  - Nginx 独占 `ports: ["80:80", "443:443"]`。
  - 两者位于独立内部网络 `dayzero-net`。
  - Gateway healthcheck 使用 `/ready`。
  - 日志轮转 `max-size: 50m`、`max-file: 5`。
  - 镜像 tag 由变量显式提供。

## 7. 部署与回滚材料

- `deployment.manifest.template.yml` 记录 image tag/digest、rollback image、source bundle SHA、VCS revision、build date、baseline versions、deployment date/operator/smoke-test。
- `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` 给出：
  - 部署前置条件与 15 步部署顺序。
  - 回滚条件与 7 步回滚流程。
  - 明确不删除 v1、不覆盖 v1 tag、不修改 Supabase/Android/Room。

## 8. 测试结果

| 命令 | 退出码 | 结果 |
| --- | ---: | --- |
| `deno fmt --check` | 0 | 通过（40 文件） |
| `deno lint` | 0 | 通过 |
| `deno check src/main.ts` | 0 | 通过 |
| `deno task test` | 0 | **100 passed / 0 failed** |
| `docker build ... -t dayzero-ai-gateway:g2-local .` | 0 | 构建成功，镜像 OCI labels 已验证 |
| `docker run ... /health` | 0 | 返回 200 |
| `docker run ... /ready` | 0 | 返回 200 |
| `docker run ... POST /api/ai/assistant-turn-v2`（无 auth） | 0 | 返回 401，未触发 Kimi |
| `nginx -t`（临时自签名证书） | 0 | `syntax is ok / test is successful` |
| `docker compose -f docker-compose.production.yml config` | 0 | 配置有效 |

## 9. 冻结文件 SHA-256

以下文件在 G2 前后哈希保持一致：

```
9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37  src/shared/assistant_upstream_timeout.ts
f616f8b177f83fcffd94bc55483c526521233beb9e34fbede329934d2483f66a  src/shared/prompt.ts
bba2537fbb09f76dbedfae53d93fcb63fd16ba81fe8da2d8a63545d14920d369  src/shared/normalization.ts
847974511740d89bc616f5f2153c2a16a8abac54d19160acf1388ca36f04c9bd  src/shared/assistant_vision.ts
c28f3e2918c7489bb2ba5e781eb3307fed3ade039477689126657ea11009aad7  src/shared/explicit_photo_meal_assignment.ts
```

## 10. 未完成项

- 真实域名、DNS 解析、生产 HTTPS 证书尚未提供。
- 因此 `nginx.production.conf.template` 中的 `__DAYZERO_DOMAIN__`、`__SSL_CERTIFICATE_PATH__`、`__SSL_CERTIFICATE_KEY_PATH__` 仍为占位符。
- ECS 现网部署、Nginx 切换、公网烟雾测试属于下一阶段独立安全验收后执行。

## 11. 范围声明

| 项 | 是否 |
| --- | --- |
| 修改 Android | 否 |
| 修改 Supabase Edge/Auth/Database/RLS/Storage | 否 |
| 修改服务器 ECS 文件/配置 | 否 |
| 部署到 ECS | 否 |
| 重启 ECS 服务 | 否 |
| 调用真实 Kimi | 否 |
| 输出 Secret/Token/API Key/私钥 | 否 |
| 执行 Git add/commit/push/reset/clean/restore | 否 |
| 修改冻结文件 | 否 |

## 12. 下一阶段

**独立安全验收，通过后进行受控 ECS 部署。**

验收内容建议：

1. 提供真实域名与证书，替换 Nginx 模板占位符。
2. 在 staging 环境使用测试 JWT 验证 `/ready`、新旧路径 401、SSE 流式事件、X-Request-Id 链路。
3. 验证生产 `.env` 中 `APP_ENV=production`、`ENABLE_AUTH=true`、JWKS/issuer/audience、CORS 来源收紧。
4. 日志扫描确认无 Token/Prompt/原始 sub/完整请求体。
5. 按 `docs/DEPLOYMENT_AND_ROLLBACK_20260712.md` 执行灰度上线与回滚演练。
