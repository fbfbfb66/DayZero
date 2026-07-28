# DayZero AI Gateway

独立的 Deno 2 AI Gateway，部署到阿里云 ECS Docker，暴露三个接口：

- `GET /health`
- `POST /assistant-turn-v2`（非流式 fallback）
- `POST /assistant-turn-v2-stream`（流式 SSE）

网关最大限度复用了现有 Supabase Edge Function 的生产逻辑，并新增：

- Supabase Auth Bearer JWT 鉴权（JWKS 验证）
- 严格 CORS、JSON 请求体大小限制
- 结构化脱敏日志、请求 ID
- 健康检查、优雅错误响应

## 目录

- `src/main.ts`：入口与路由
- `src/handlers/`：三个接口的 handler
- `src/shared/`：从 Edge Function 复用/提取的共享模块
- `src/auth.ts`：JWT 鉴权
- `src/logger.ts`：结构化脱敏日志
- `src/config.ts`：环境变量读取

## 环境变量

复制 `.env.example` 为 `.env` 并填写：

| 变量                    | 说明                               | 必需 |
| ----------------------- | ---------------------------------- | ---- |
| `PORT`                  | 监听端口，默认 `8080`              | 否   |
| `KIMI_API_KEY`          | Kimi API Key                       | 是   |
| `KIMI_API_URL`          | Kimi chat completions URL          | 是   |
| `KIMI_MODEL`            | 模型名                             | 是   |
| `SUPABASE_URL`          | Supabase 项目 URL（用于 JWKS）     | 是   |
| `SUPABASE_JWT_AUDIENCE` | JWT audience，默认 `authenticated` | 否   |
| `ALLOWED_ORIGINS`       | CORS 来源，默认 `*`                | 否   |
| `REQUEST_BODY_LIMIT_MB` | JSON body 限制，默认 `10`          | 否   |
| `LOG_LEVEL`             | 日志级别，默认 `info`              | 否   |
| `ENABLE_AUTH`           | 是否启用 JWT 鉴权，默认 `true`     | 否   |

## 本地开发

```bash
cd server/dayzero-ai-gateway
cp .env.example .env
# 编辑 .env 填入真实密钥

deno task check
deno task lint
deno task fmt
deno task test
deno task start
```

## Docker 本地验证

```bash
cd server/dayzero-ai-gateway
docker build --build-arg DENO_IMAGE=denoland/deno:2.1.4 -t dayzero-ai-gateway .
docker run --env-file .env -p 8080:8080 dayzero-ai-gateway
```

或：

```bash
docker compose up --build
```

## 阿里云 ECS 部署步骤（不执行部署，仅说明）

1. ECS 安装 Docker 与 Docker Compose。
2. 上传本项目到服务器（如 `/opt/dayzero-ai-gateway`）。
3. 配置 `.env` 并将 `.env` 权限设为 `600`。
4. 可选：将 `DENO_IMAGE` 构建参数替换为阿里云 ACR 镜像地址。
5. `docker compose up -d` 启动。
6. 配置 Nginx（参考 `nginx.example.conf`），绑定域名与 SSL 证书。
7. 安全组放行 443/80，并限制 8080 仅允许本机/Nginx 访问。

## 协议兼容性

- 请求协议与 Android `AiAssistantRequestDto` 完全一致。
- fallback 响应：`{reply, actions, debugTiming}`。
- streaming SSE 事件序：`status → reply_delta* → final → debug_timing → done`。
- Vision 校验失败在 streaming 路径以 **HTTP 200 + `event: error` + `{message, code}`** 的 in-band 事件返回，与现有 Edge Function SSE 协议一致（客户端据此拿到具体错误码）。
- fallback 路径 Vision 校验失败仍返回对应 HTTP 状态（400/413）+ `{error, errorCode}`。
- 所有响应回传 `X-Request-Id`，便于排障。

## 鉴权（重要）

- 公网接口只接受**当前 Supabase 已登录用户的 access token**：`Authorization: Bearer <user_access_token>`。
- `auth.ts` 用 JWKS 验证，`issuer` 严格为 `规范化(SUPABASE_URL) + /auth/v1`，`audience=authenticated`，**仅允许 ES256**，按 `kid` 选公钥，带 JWKS 缓存与密钥轮换能力。
- **不兼容**仅凭 publishable/anon key 调用：legacy anon key 是 HS256、新版 `sb_publishable_…` 非 JWT，均被拒绝（不使用、不暴露任何 JWT secret / service role key）。
- Android 侧通过专用动态认证路径复用现有 `SupabaseAuthSessionProvider`（固定密码账号会话）在请求前取得有效 access token，临近过期复用现有刷新机制，401 最多刷新并重试一次。
- 备注：现网 Edge Function `assistant-turn-v2` / `assistant-turn-v2-stream` 实际为 `verify_jwt=false`（平台层不校验 JWT），因此"现网可调用"并非因为 anon key 通过了用户 JWT 验证。本网关自行实现用户 JWT 鉴权，不把 `verify_jwt=false` 语义带到公网。

## 安全

- 敏感配置仅从环境变量读取，不进源码、日志、测试快照或 Docker 镜像。
- 日志自动脱敏 Base64、Authorization header、Kimi key、prompt cache key；access token 不入日志/异常/测试快照。
- 生产 Docker Compose **不发布 8080 到公网**，仅经内部 `dayzero-net` 供 Nginx 反代访问；Nginx 增加基础限流并对 SSE 关闭 buffering。
- Dockerfile 复制 `deno.lock` 并以 `--frozen` 构建/运行，保证依赖可复现与完整性。
