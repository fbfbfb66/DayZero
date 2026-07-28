# DayZero AI Gateway G2-F1 ECS 部署报告

> 报告生成时间：2026-07-12 UTC
> 部署目标：api.dayzero.cn / 39.106.156.166
> 任务范围：G2-F1 生产镜像发布、HTTPS 证书签发、ECS 受控部署与公网烟雾测试

---

## 1. 最终状态

**GATEWAY_G2F1_DEPLOYED_SERVER_SMOKE_PASSED**

G2-F1 Gateway 已部署至 ECS，HTTPS 已启用，公网烟雾测试通过，旧 v1 镜像与配置保留，具备回滚能力。

---

## 2. 本地发布基线

| 项目 | 值 |
| --- | --- |
| Git commit | `2bd958e2444256f289c7f14dc37629c26eab9669` |
| Source bundle SHA | `310e0d622aa7c8254ffef3060e0467e80d62ae23c0613fde234a01858d2d4ddf` |
| `deno fmt --check` | 40 files checked，通过 |
| `deno lint` | 35 files checked，通过 |
| `deno check src/main.ts` | 通过 |
| `deno task test` | **127 passed / 0 failed** |

### 冻结文件 SHA-256

| 文件 | 哈希 |
| --- | --- |
| `src/shared/assistant_upstream_timeout.ts` | `9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37` |
| `src/shared/prompt.ts` | `f616f8b177f83fcffd94bc55483c526521233beb9e34fbede329934d2483f66a` |
| `src/shared/normalization.ts` | `bba2537fbb09f76dbedfae53d93fcb63fd16ba81fe8da2d8a63545d14920d369` |
| `src/shared/assistant_vision.ts` | `847974511740d89bc616f5f2153c2a16a8abac54d19160acf1388ca36f04c9bd` |
| `src/shared/explicit_photo_meal_assignment.ts` | `c28f3e2918c7489bb2ba5e781eb3307fed3ade039477689126657ea11009aad7` |

> 临时文件 `tmp_nginx.conf` 与 `tmp_ssl/` 导致首次 Source bundle SHA 计算不匹配；移除后复现成功。

---

## 3. 镜像

| 项目 | 值 |
| --- | --- |
| Tag | `g2f1-20260712-140815-310e0d622aa7` |
| 本地 Image ID | `sha256:04ce558bcf2a68cbf584f731ee674ab4a946811903443740242f4f234396ad16` |
| ACR RepoDigest | `crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/dayzero-ai-gateway@sha256:04ce558bcf2a68cbf584f731ee674ab4a946811903443740242f4f234396ad16` |
| 运行用户 | `dayzero`（非 root） |

### OCI Labels

| Label | 值 |
| --- | --- |
| `org.opencontainers.image.revision` | `2bd958e2444256f289c7f14dc37629c26eab9669` |
| `org.opencontainers.image.created` | `2026-07-12T14:08:15Z` |
| `org.opencontainers.image.version` | `g2f1-20260712-140815-310e0d622aa7` |
| `com.dayzero.source_bundle_sha` | `310e0d622aa7c8254ffef3060e0467e80d62ae23c0613fde234a01858d2d4ddf` |

### 镜像安全扫描结论

- 镜像内不存在 `.env`、私钥、证书、`local.properties`、Git 凭证、Android Secret
- Docker history 未出现 Secret
- `/app/src` 下所有源文件哈希与本地一致
- 本地 fake 环境验证：`/health=200`、`/ready=200`、无 Authorization 的 AI POST 返回 401、未调用 Kimi

---

## 4. 现网备份

| 项目 | 值 |
| --- | --- |
| 备份目录 | `/opt/dayzero-ai-backups/20260712-141306/` |
| 备份目录权限 | `700`（仅 root 可读） |
| 备份 `.env` 权限 | `600` |
| 备份内容 | `docker-compose.yml`、`nginx.conf`、`.env`、容器 inspect、v1 image inspect、文件哈希与元数据、回滚说明 |
| v1 digest | `sha256:e0518d64a305be3f63bdcbf69a22953c20a74d06372ff883ac51463ba7324935` |
| 备份哈希一致性 | 已验证 `docker-compose.yml` 与 `nginx.conf` 哈希与现网一致 |

> `.env` 内容未在终端打印、未下载到本地。

---

## 5. DNS/JWKS

### DNS 验证

| 解析来源 | A 记录 | 结论 |
| --- | --- | --- |
| ECS 系统解析器 (`getent`) | `39.106.156.166` | ✅ 正确 |
| Aliyun DOH | `39.106.166` | ✅ 正确 |
| Python socket | `39.106.156.166` | ✅ 正确 |

- **AAAA 记录**：Aliyun DOH 返回 SOA，无冲突 AAAA 记录 ✅

### JWKS 验证

- 端点：`https://sybenxmxnwwtlvkeojtj.supabase.co/auth/v1/.well-known/jwks.json`
- HTTP 200，JSON 合法
- 存在 **ES256** 公钥（`kid` 已记录但未在报告中输出完整 key）
- key metadata 合法

---

## 6. HTTPS 证书

| 项目 | 值 |
| --- | --- |
| Issuer | `C = US, O = Let's Encrypt, CN = YR2` |
| Domain / CN | `api.dayzero.cn` |
| SAN | `DNS:api.dayzero.cn` |
| Not Before | `2026-07-12 13:31:58 GMT` |
| Not After | `2026-10-10 13:31:57 GMT` |
| fullchain 路径 | `/opt/dayzero-ai/nginx/ssl/fullchain.pem` |
| private key 路径 | `/opt/dayzero-ai/nginx/ssl/privkey.pem` |
| 私钥权限 | `600` |
| 证书链验证 | `Verify return code: 0 (ok)` |

---

## 7. Candidate Gateway 验证

- 容器名：`dayzero-ai-gateway-candidate`
- 网络：仅加入现有 `dayzero-ai_dayzero-net`，未映射宿主机端口
- 使用生产 `.env.production`

### 验证结果

| 检查项 | 结果 |
| --- | --- |
| 启动成功 | ✅ |
| `/health` | `200` |
| `/ready` | `200` |
| 新 fallback 无 Token | `401 AUTH_HEADER_MISSING` |
| 新 stream 无 Token | `401 AUTH_HEADER_MISSING` |
| 旧 fallback 无 Token | `401 AUTH_HEADER_MISSING` |
| 旧 stream 无 Token | `401 AUTH_HEADER_MISSING` |
| `X-Request-Id` 存在 | ✅ |
| 非法 `X-Request-Id` 被替换 | ✅ |
| 有效 JWT + 非法 body | `400 Missing userText`（非 401，证明 JWT 验证通过） |
| Candidate 日志无 Secret | ✅ |

Candidate 已通过全部检查，已停止并保留作为验证证据。

---

## 8. Gateway 部署

- 使用镜像：`crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/dayzero-ai-gateway@sha256:04ce558bcf2a68cbf584f731ee674ab4a946811903443740242f4f234396ad16`
- Compose 文件：`/opt/dayzero-ai/docker-compose.production.yml`
- 环境文件：`/opt/dayzero-ai/.env.production`（`ENV_FILE` 已显式设置）
- 容器名：`dayzero-ai-gateway`
- 只 expose `8080`，未映射宿主机端口
- restart：`unless-stopped`
- healthcheck：`/ready`
- 日志轮转：`json-file`，`max-size=50m`，`max-file=5`
- 使用内部网络 `dayzero-ai_dayzero-net`

### 部署后验证

| 检查项 | 结果 |
| --- | --- |
| Gateway healthy | ✅ |
| `/health` | `200` |
| `/ready` | `200` |
| 无 Token AI POST | `401` |
| 有效 JWT + 非法 body | `400` |
| 日志安全 | ✅ |

---

## 9. Nginx 切换

- 配置文件：`/opt/dayzero-ai/nginx/nginx.production.conf`（由 `nginx.production.conf.template` 渲染）
- 镜像：`crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/nginx:alpine`
- 容器名：`dayzero-ai-nginx`
- 端口：`80:80`、`443:443`
- 证书：`/opt/dayzero-ai/nginx/ssl/fullchain.pem`、`privkey.pem`

### 配置检查结论

| 检查项 | 结果 |
| --- | --- |
| 80 ACME challenge | ✅ |
| 80 其余请求跳转 HTTPS | ✅ |
| 443 TLS 1.2/1.3 | ✅ |
| `/health` 代理 | ✅ |
| `/ready` 代理 | ✅ |
| 新 fallback/stream 路径 | ✅ |
| 旧兼容路径 | ✅ |
| `Authorization` 透传 | ✅ |
| `X-Request-Id` 透传 | ✅ |
| SSE 防缓冲 (`proxy_buffering off`、`X-Accel-Buffering no`) | ✅ |
| `proxy_next_upstream off` | ✅ |
| 无 Supabase Edge upstream | ✅ |
| 无 host 8080 直连公网 | ✅ |

### 切换流程

1. 临时容器测试新配置 `nginx -t` ✅
2. 原子替换 `/opt/dayzero-ai/nginx/nginx.conf` ✅
3. 停止旧 Nginx，启动新 Nginx ✅
4. 新容器内 `nginx -t` ✅
5. Nginx healthcheck healthy ✅

> 部署中发现原模板使用 Docker Hub `nginx:alpine`，ECS 无法拉取；已切换为 ACR `dayzero/nginx:alpine` 镜像。Nginx healthcheck 已改为 `https://localhost/ready` 以避免 HTTP 301 跳转导致健康检查失败。

---

## 10. 公网烟雾测试

### TLS / 路由

| 测试 | 状态码/事件 | Request ID | 结论 |
| --- | ------ | ---------- | --- |
| `GET https://api.dayzero.cn/health` | `200` | `mrhx...` | ✅ |
| `GET https://api.dayzero.cn/ready` | `200` | `mrhx...` | ✅ |
| `POST /api/ai/assistant-turn-v2` 无 Token | `401 AUTH_HEADER_MISSING` | 有 | ✅ |
| `POST /api/ai/assistant-turn-v2-stream` 无 Token | `401 AUTH_HEADER_MISSING` | 有 | ✅ |
| `POST /assistant-turn-v2` 无 Token | `401 AUTH_HEADER_MISSING` | 有 | ✅ |
| `POST /assistant-turn-v2-stream` 无 Token | `401 AUTH_HEADER_MISSING` | 有 | ✅ |
| `GET /unknown-path` | `404` | 有 | ✅ |
| 有效 JWT + 非法 body | `400 Missing userText` | `reqid-pub-test2` | ✅ |
| HTTP 自动跳转 HTTPS | `301` → `https://api.dayzero.cn/health` | - | ✅ |
| 证书链可信 | `Verify return code: 0` | - | ✅ |

### SSE 烟雾测试

- 端点：`POST https://api.dayzero.cn/api/ai/assistant-turn-v2-stream`
- Request ID：`sse-smoke-20260712-145845`
- Content-Type：`text/event-stream; charset=utf-8`
- 事件顺序：`status` → 多个 `reply_delta` → `final` → `debug_timing` → `done` ✅
- `final` 仅出现一次 ✅
- `actions` 仅出现在 `final` 中 ✅
- 至少一个 `reply_delta`（实际 30 个）✅
- 事件为流式推送，非请求结束后聚合 ✅
- 未执行 Vision ✅
- 未确认正式饮食记录 ✅
- 未写 Android Room ✅
- 未调用 Supabase Edge fallback ✅

### Fallback 最小受控文字请求

- 端点：`POST https://api.dayzero.cn/api/ai/assistant-turn-v2`
- Request ID：`fallback-smoke-20260712-145903`
- 返回合法 JSON，含 `reply` / `actions` / `debugTiming` 结构 ✅
- 不含内部 detail ✅

> 本轮共调用真实 Kimi **2 次**（1 次 SSE + 1 次 fallback）。

---

## 11. 日志安全扫描

- 最近 Gateway/Nginx 日志中未发现 Bearer token、JWT、原始 `sub`、Kimi key、Base64、data URL、Prompt、请求正文、用户文本、raw error
- 日志中 `userText` 仅记录长度字段（`userTextLength`），不记录内容
- 未出现持续 5xx
- 未出现容器重启循环
- JWKS 请求无风暴（single-flight 生效）
- Nginx SSE 缓冲已关闭

---

## 12. 重启恢复

1. 执行 `docker compose -f docker-compose.production.yml restart dayzero-ai-gateway`
2. Gateway healthcheck 变为 healthy ✅
3. 容器内 `/ready` 返回 `200` ✅
4. 首次通过 Nginx 访问返回 `502`（Nginx 缓存了旧 Gateway IP）
5. 执行 `docker exec dayzero-ai-nginx nginx -s reload` 后 HTTPS 路由恢复 `200` ✅

> 发现项：Nginx 默认会缓存 upstream 解析的 IP；Gateway 重启获得新 IP 后，需要一次 Nginx reload 才能使公网 HTTPS 恢复。建议在运维脚本中将 Nginx reload 作为 Gateway 重启后的标准步骤。

---

## 13. 回滚能力

- 备份目录 `/opt/dayzero-ai-backups/20260712-141306/` 完整可用
- 旧 v1 镜像仍保留：
  `crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/dayzero-ai-gateway:v1@sha256:e0518d64a305be3f63bdcbf69a22953c20a74d06372ff883ac51463ba7324935`
- 旧 Compose 与 Nginx 配置已备份
- 回滚步骤已写入备份目录 `ROLLBACK_INSTRUCTIONS.txt`
- 未删除旧镜像、旧配置、旧证书或日志证据

---

## 14. 未完成项

- Android 尚未切流
- Supabase Edge 仍 ACTIVE
- 图片公网验收尚未执行
- 完整迁移尚未完成

---

## 15. 范围声明

| 项目 | 是否执行 |
| --- | --- |
| 修改 Android | 否 |
| 修改 Supabase Edge Functions | 否 |
| 修改 Supabase Auth / 数据库 / RLS / Storage | 否 |
| 修改 Room | 否 |
| 调用真实 Kimi | 是，2 次（SSE + fallback 烟雾测试） |
| Git 写操作（add/commit/push/pull/reset/clean/restore） | 否 |
| 保留 v1 镜像与配置 | 是 |
| 输出 Secret（Kimi key、JWT、Token、.env 内容等） | 否 |

---

## 附录：生产运行容器快照

```
NAMES                STATUS                   PORTS
dayzero-ai-nginx     Up ... (healthy)         0.0.0.0:80->80/tcp, :::80->80/tcp, 0.0.0.0:443->443/tcp, :::443->443/tcp
dayzero-ai-gateway   Up ... (healthy)         8080/tcp
```

- Gateway 8080 **未暴露到宿主机/公网**
- 公网唯一入口为 Nginx 443/80
