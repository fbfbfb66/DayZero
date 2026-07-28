# DayZero ECS 现网只读核验报告

## 1. 最终状态

`ECS_RUNTIME_AUDIT_COMPLETE`

本次核验严格只读，未修改服务器、容器、配置、环境变量，未部署、未重启、未调用 Kimi、未输出 Secret。

## 2. SSH 与系统状态

| 项目 | 结果 |
| --- | --- |
| ECS 可连接 | 是 |
| 主机名 | `iZ2ze6zl6unjxrg3q074t9Z` |
| 系统 | `Linux 5.10.134-19.7.al8.x86_64` |
| 当前时间 | `Sun Jul 12 05:38:56 PM CST 2026` |
| 运行时长 | 7h 15m |
| 负载 | `0.00, 0.01, 0.00` |
| 内存 | 总量 1.8 Gi，已用 437 Mi，可用 1.4 Gi |
| 磁盘 / | 40 G，已用 5.2 G（14%） |
| 公网监听端口 | `22/tcp`、`80/tcp` |
| 443 监听 | 否 |
| 8080 监听 | 否（仅容器内部网络） |

SSH Host Key（ed25519）指纹首次通过 `StrictHostKeyChecking=accept-new` 记录到本地 `known_hosts`，未使用 `StrictHostKeyChecking=no`，未读取或打印私钥内容。

## 3. 当前容器与镜像

| 服务 | 容器 | 镜像 | digest | 启动时间 | restart | health |
| -- | -- | -- | ------ | ---- | ------- | ------ |
| Gateway | `dayzero-ai-dayzero-ai-1` | `crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/dayzero-ai-gateway:v1` | `sha256:e0518d64a305be3f63bdcbf69a22953c20a74d06372ff883ac51463ba7324935` | 2026-07-12T06:39:12Z | `unless-stopped` | none |
| Nginx | `dayzero-ai-nginx-1` | `crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/nginx:alpine` | `sha256:35cd77497979abe70dc8d26f5ae60811eea233a2eb5dc03c2ee30972caeb303e` | 2026-07-12T06:39:12Z | `unless-stopped` | none |

- Gateway 镜像 RepoDigest 与已知的 G1 前 v1 digest **一致**。
- Gateway 容器端口映射为 `8080/tcp` 内部-only，未绑定宿主机端口。
- Nginx 容器将宿主机 `80/tcp` 映射到容器 `80/tcp`。
- 两容器共享 Compose 网络 `dayzero-ai_dayzero-net`。
- 容器 OCI labels 中**不存在** Git revision/source 标签。

## 4. Gateway 源码对应关系

| 对比项 | 结果 |
| --- | --- |
| 容器 `/app/src` bundle hash | `ad348fc00768241c277d527e695951b94a8edf384faefca7e1ae410ca9a51307` |
| 本地 G1 后 `src/` bundle hash | `67266b85348b96d6f6b08537001fd7bb9cd1d9e90c13da4690a6ba218ae51c1e` |
| 五份冻结文件（timeout、prompt、normalization、vision、explicit_photo_meal_assignment） | 容器与本地 **完全一致** |

结论：**现网运行的是 G1 前 v1 代码**，与本地 G1 后源码不一致；差异来自 G1 新增的路由、`/ready`、请求 ID、入口校验等文件，冻结的业务核心文件未变化。

## 5. 运行环境

| 变量 | 状态 |
| --- | --- |
| `KIMI_API_KEY` | `[PRESENT]` |
| `KIMI_API_URL` | `[PRESENT]` |
| `KIMI_MODEL` | `[MATCH]`（`kimi-k2.6`） |
| `SUPABASE_JWKS_URL` | `[MISSING]` |
| `SUPABASE_ISSUER` | `[MISSING]` |
| `SUPABASE_AUDIENCE` | `[MISSING]`（存在 `SUPABASE_JWT_AUDIENCE`） |
| `ENABLE_AUTH` | `[MATCH]`（`true`） |
| `PORT` | `[MATCH]`（`8080`） |
| `LOG_LEVEL` | `[PRESENT]` |
| `REQUEST_BODY_LIMIT_MB` | `[PRESENT]` |
| `ALLOWED_ORIGINS` | `[PRESENT]` |

环境变量值未输出；匹配/缺失结论通过容器内只读比较获得。

## 6. Nginx 生效配置

Nginx 以 Docker 容器运行，生效配置文件：`/opt/dayzero-ai/nginx/nginx.conf`（ro mount 到容器 `/etc/nginx/nginx.conf`）。

关键指令摘要：

| 指令 | 值/状态 | 说明 |
| --- | --- | --- |
| `listen` | `80` | 仅 HTTP |
| `server_name` | `_` | 默认服务器 |
| `client_max_body_size` | `10m` | 符合预期 |
| `limit_req_zone` | `perip:10m rate=10r/s` | 存在按 IP 限流 |
| `proxy_pass` upstream | `dayzero-ai:8080` | 仅通过内部网络访问 Gateway |
| `proxy_http_version` | `1.1` | 仅流式路由 |
| `proxy_buffering` | `off` | 仅流式路由 |
| `proxy_cache` | `off` | 仅流式路由 |
| `proxy_request_buffering` | 未设置 | 默认 on |
| `proxy_read_timeout` | `3600s` | 仅流式路由 |
| `proxy_set_header Authorization` | `$http_authorization` | **转发** |
| `proxy_set_header X-Request-Id` | `$http_x_request_id` | **转发** |
| `add_header X-Accel-Buffering` | 未设置 | 未关闭 Nginx 层缓冲 |
| `gzip` | 未配置 | 默认可能压缩，未排除 `text/event-stream` |

路由配置：

- `location = /health`
- `location = /assistant-turn-v2`
- `location = /assistant-turn-v2-stream`（已配 SSE 相关缓冲关闭）

**未配置**：

- `/api/ai/assistant-turn-v2`
- `/api/ai/assistant-turn-v2-stream`
- `/ready`

因此现网 Nginx 只能代理旧路径；新正式路径请求会命中 Nginx 默认 404/405。

## 7. 路由与健康探测

所有探测均未携带真实 JWT/Token，请求体为空 JSON `{}`，不会触发 Kimi。

### 7.1 直接访问 Gateway 内部端口（`172.23.0.2:8080`）

| 方法 | 路径 | 状态码 | Content-Type | X-Request-Id | 结论 |
| -- | -- | --: | ------------ | ---------- | -- |
| GET | `/health` | 200 | `application/json` | 有 | 健康检查正常 |
| GET | `/ready` | 404 | `application/json` | 有 | **当前 v1 无 readiness 接口** |
| OPTIONS | `/assistant-turn-v2` | 200 | `text/plain` | 有 | 路由存在 |
| OPTIONS | `/assistant-turn-v2-stream` | 200 | `text/plain` | 有 | 路由存在 |
| OPTIONS | `/api/ai/assistant-turn-v2` | 200 | `text/plain` | 有 | 通用 OPTIONS 响应，POST 验证为 404 |
| OPTIONS | `/api/ai/assistant-turn-v2-stream` | 200 | `text/plain` | 有 | 通用 OPTIONS 响应，POST 验证为 404 |
| POST | `/assistant-turn-v2` | 401 | `application/json` | 有 | 认证层拦截，**不会触发 Kimi** |
| POST | `/assistant-turn-v2-stream` | 401 | `application/json` | 有 | 认证层拦截，**不会触发 Kimi** |
| POST | `/api/ai/assistant-turn-v2` | 404 | `application/json` | 有 | **Gateway 无此 handler** |
| POST | `/api/ai/assistant-turn-v2-stream` | 404 | `application/json` | 有 | **Gateway 无此 handler** |

### 7.2 通过 Nginx（`127.0.0.1:80`）

| 方法 | 路径 | 状态码 | Content-Type | X-Request-Id | 结论 |
| -- | -- | --: | ------------ | ---------- | -- |
| GET | `/health` | 200 | `application/json` | 有 | Nginx 已代理旧 health |
| GET | `/ready` | 404 | `text/html` | 无 | Nginx 未配置 `/ready` |
| OPTIONS | `/assistant-turn-v2` | 200 | `text/plain` | 有 | Nginx 已代理旧路径 |
| OPTIONS | `/api/ai/assistant-turn-v2` | 405 | `text/html` | 无 | Nginx 未配置，静态文件 405/404 |
| POST | `/assistant-turn-v2` | 401 | `application/json` | 有 | Nginx 已代理旧路径 |
| POST | `/api/ai/assistant-turn-v2` | 404 | `text/html` | 无 | Nginx 未配置新路径 |

## 8. 日志安全

检查了 Gateway 与 Nginx 容器最近约 200 行日志，以及 `/opt/dayzero-ai/logs/`（该目录为空，日志未做宿主机持久化）。

| 检查项 | 结果 |
| --- | --- |
| 敏感样式（Token、Bearer、Base64/data URL、API Key、完整 Prompt、JWT `sub` 原始值等） | 未发现 |
| 5xx 错误 | Gateway 0 条；Nginx 0 条（仅本次探测产生的 404 错误） |
| 异常堆栈 | 未发现 |
| 重启循环 | 未发现（RestartCount = 0） |
| healthcheck failure | 未配置 healthcheck |
| 完整请求 JSON / 用户聊天内容 | 未发现 |

Gateway 日志中仅记录了本次无 Authorization 探测的 401 警告（含 requestId 与 path），属于正常认证拒绝，不泄露 Secret。

## 9. 阻塞项

| 级别 | 项 | 说明 |
| --- | --- | --- |
| CRITICAL | Nginx 缺少 `/api/ai/assistant-turn-v2[-stream]` 路由 | 部署 G1 后公网无法访问新路径 |
| CRITICAL | Nginx 缺少 `/ready` 路由 | G1 新增的 readiness 检查无法通过公网/内部代理访问 |
| HIGH | Nginx 未全局关闭 SSE/request buffering | 仅流式路由关闭；非流式路径存在压缩/缓冲风险 |
| HIGH | 未配置 `X-Accel-Buffering: no` | SSE 可能被 Nginx 缓冲 |
| HIGH | 未配置 `proxy_request_buffering off` | 请求体可能被缓冲 |
| HIGH | 现网未启用 HTTPS（443 未监听） | 仅 80 端口，Authorization/JWT 明文传输 |
| MEDIUM | `ALLOWED_ORIGINS` 实际为 `["*"]` | CORS 完全开放 |
| MEDIUM | 缺少 `SUPABASE_JWKS_URL` / `SUPABASE_ISSUER` / `SUPABASE_AUDIENCE` | 当前认证依赖 `SUPABASE_URL` + `SUPABASE_JWT_AUDIENCE`，JWKS 轮询与 issuer/audience 校验不完整 |
| MEDIUM | 容器未配置 healthcheck | 无法自动发现 Gateway 内部 readiness |
| LOW | 日志未持久化到宿主机 | 容器重建后日志丢失 |

## 10. 下一阶段施工建议

1. **JWT/JWKS 与日志加固**
   - 在 `.env` 中补齐 `SUPABASE_JWKS_URL`、`SUPABASE_ISSUER`、`SUPABASE_AUDIENCE`。
   - 将 `ALLOWED_ORIGINS` 从 `*` 收紧到实际域名/APP。
   - 增加日志脱敏与落盘策略，避免记录 Authorization、JWT sub、图片 data URL、完整 Prompt。

2. **Nginx/SSE 配置**
   - 新增 `/api/ai/assistant-turn-v2[-stream]` 与 `/ready` 的 `location`。
   - 对所有 SSE/流式相关 location 设置 `proxy_http_version 1.1`、`proxy_buffering off`、`proxy_cache off`、`proxy_request_buffering off`、`proxy_read_timeout 3600s`、`add_header X-Accel-Buffering no`。
   - 配置 `gzip off` 或 `gzip_types` 排除 `text/event-stream`。
   - 建议启用 443/HTTPS 并配置证书。

3. **镜像追溯与部署**
   - 将本地 G1 后镜像推送到 ACR 并更新 `docker-compose.yml` image tag。
   - 部署前在 staging 环境验证 `/ready`、新路径 SSE buffering、JWT 校验。

4. **公网烟雾测试**
   - 部署后从公网发送无 Token/空请求，确认 401/400 且不触发 Kimi；再使用测试 JWT 进行端到端流式握手测试。

5. **Android 切流**
   - 确认 Android 客户端目标 URL 切换到 `/api/ai/assistant-turn-v2[-stream]`，并携带 `Authorization` 与 `X-Request-Id`。

## 11. 执行命令

以下命令在本地或 ECS 上只读执行，退出码均为 `0`（已省略最终未使用的早期失败尝试）。

```bash
# 本地 Gateway 源码与配置 bundle hash
cd /d/Goings/APPProjects/DayZero/server/dayzero-ai-gateway
find src deno.json deno.lock -type f | sort | while read f; do echo "$(sha256sum "$f" | cut -d' ' -f1)  $f"; done | sha256sum
(printf '%s\n' Dockerfile docker-compose.yml nginx.example.conf | sort | while read f; do [ -f "$f" ] && echo "$(sha256sum "$f" | cut -d' ' -f1)  $f"; done) | sha256sum
deno task test

# SSH 与系统检查
ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes -i "C:\Users\Goings\.ssh\id_ed25519" -p 22 root@39.106.156.166 'hostname; uname -a; date; uptime; whoami; pwd; df -h; free -h; ss -lntp'

# Docker 状态
ssh ... 'docker ps --no-trunc; docker compose ls; docker images --digests --no-trunc'
ssh ... 'docker inspect dayzero-ai-dayzero-ai-1 dayzero-ai-nginx-1'

# 容器环境变量只读检查
ssh ... 'docker exec dayzero-ai-dayzero-ai-1 sh -c '"'"'[ "$KIMI_MODEL" = "kimi-k2.6" ] && echo MATCH || echo MISMATCH'"'"''
ssh ... 'docker exec dayzero-ai-dayzero-ai-1 sh -c '"'"'[ "$ENABLE_AUTH" = "true" ] && echo MATCH || echo MISMATCH'"'"''
ssh ... 'docker exec dayzero-ai-dayzero-ai-1 sh -c '"'"'[ "$PORT" = "8080" ] && echo MATCH || echo MISMATCH'"'"''

# 容器源码 bundle hash
ssh ... 'docker exec dayzero-ai-dayzero-ai-1 sh -c '"'"'cd /app && find src -type f | sort | while read f; do echo "$(sha256sum "$f" | cut -d" " -f1)  $f"; done'"'"' | sha256sum

# 部署目录与配置
ssh ... 'ls -la /opt/dayzero-ai/'
ssh ... 'python3 -c "# 解析并红显 docker-compose.yml，仅输出服务名、image、restart、ports、networks、env 名称"'
ssh ... 'python3 -c "# 按行输出 nginx.conf，仅 redact ssl_certificate_key"'

# 健康/路由探测（Python urllib，内部端口与 Nginx 80）
ssh ... 'python3 -c "# 请求 172.23.0.2:8080 /health /ready /assistant-turn-v2 /api/ai/..."'
ssh ... 'python3 -c "# 请求 127.0.0.1:80 /health /ready /assistant-turn-v2 /api/ai/..."'

# 日志
ssh ... 'docker logs --tail 200 dayzero-ai-dayzero-ai-1'
ssh ... 'docker logs --tail 200 dayzero-ai-nginx-1'
ssh ... 'ls -la /opt/dayzero-ai/logs/'
```

## 12. 范围声明

| 项 | 是否 |
| --- | --- |
| 修改服务器文件 | 否 |
| 修改容器 | 否 |
| 部署新镜像 | 否 |
| 重启服务 | 否 |
| 调用 Kimi / 发送真实 AI 请求 | 否 |
| 输出 Token / API Key / 密码 / 私钥 / 完整 Prompt | 否 |
| 修改本地源码 | 否 |
| Git 状态保持一致 | 是（核验前后未做任何 Git 写操作） |

---

## 完成条件核对

1. ECS 是否可连接：**是**
2. Gateway 是否正在运行：**是**
3. 当前运行哪个镜像：`dayzero-ai-gateway:v1`
4. 是否为已知 v1 digest：**是**（`sha256:e0518d64a305be3f63bdcbf69a22953c20a74d06372ff883ac51463ba7324935`）
5. 是否运行 G1 前代码：**是**
6. 当前是否存在 `/ready`：**否**（Gateway 返回 404，Nginx 未配置）
7. Nginx 是否有 `/api/ai/` 路由：**否**
8. SSE buffering 是否关闭：**仅流式旧路径关闭；全局/新路径未关闭**
9. request buffering 是否关闭：**否**（未配置）
10. `X-Accel-Buffering` 是否设置：**否**
11. Authorization 是否转发：**是**
12. `X-Request-Id` 是否转发：**是**
13. JWT 是否启用：**是**（`ENABLE_AUTH=true`）
14. `KIMI_MODEL` 是否匹配：**是**（`kimi-k2.6`）
15. 8080 是否直接暴露公网：**否**（仅容器内部）
16. 日志是否有明显敏感信息：**否**
17. 当前是否具备安全部署 G1/G2 的条件：**尚不具备；需先补齐 Nginx 路由、SSE/请求缓冲、HTTPS、JWKS 与日志脱敏**
