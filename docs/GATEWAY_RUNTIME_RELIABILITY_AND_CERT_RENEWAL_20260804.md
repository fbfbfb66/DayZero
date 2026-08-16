# DayZero Gateway 运行可靠性修复与证书自动续期报告

> 报告生成时间：2026-08-04（本地）
> 目标域名：`api.dayzero.cn` / ECS `39.106.156.166`
> 任务范围：Nginx upstream 动态解析修复、Gateway restart/recreate 自动恢复实测、Let's Encrypt 自动续期配置、本地模板与运维文档同步

---

## 1. 最终状态

**GATEWAY_RUNTIME_RELIABILITY_PASSED**（2026-08-15 更新）

2026-08-04 时曾因本机无 ECS SSH 凭据而 BLOCKED；2026-08-15 凭据恢复后，第 9 节恢复执行清单已全部落地：动态解析 nginx 配置已部署并经 restart/recreate 双模式烟测通过，certbot timer + deploy hook 已安装且 `renew --dry-run` 通过，同时部署了含 conversation-title-jobs 与 title-worker 的新网关镜像（r4，`sha256:003e1743…c254d3eb`）。详细记录见 `docs/DEVELOPMENT_LOG.md` 2026-08-15 条目。

---

## 2. 阻塞根因：本机无 ECS SSH 访问凭据

目标第 1–3 项均要求在 ECS（`root@39.106.156.166`）上执行只读确认、配置变更与实测。连续 3 个目标轮排查结果一致：

| 排查项 | 结果 |
| --- | --- |
| `C:\Users\Goings\.ssh\` | 目录不存在（7 月部署时使用的 `id_ed25519` 已不在本机） |
| 全盘搜索 `id_ed25519` / `id_rsa` / `*.ppk` / `*.pem`（C:\Users、D:\Goings 各常见目录） | 无结果 |
| `ssh-add -l`（ssh-agent） | agent 未运行 |
| plink / PuTTY 注册表会话 / pageant | 均不存在 |
| 阿里云 CLI / API 凭证（env） | 无 |
| Windows 凭据管理器（`cmdkey /list`） | 无 ECS/SSH 相关条目 |
| 本项目及 docs 内密钥文件 | 无 |

另：上一会话（2026-08-03）遇到完全相同的阻塞，已请求用户提供 `id_ed25519` 私钥，截至本报告生成时密钥未就位。

SSH 私钥属于用户持有的 Secret，无法也不应由执行侧自行生成或绕过（ECS 侧未授权新公钥前，本地生成密钥对无效）。

---

## 3. 只读验证结果（公网，无需 ECS 访问）

本轮全部实测，未修改任何现网状态：

| 检查项 | 命令/方式 | 结果 |
| --- | --- | --- |
| HTTP 根路径 | `curl -I http://api.dayzero.cn` | `301 Moved Permanently`，`Server: nginx`，`Location: https://api.dayzero.cn/` |
| ACME 挑战路径 | `curl -I http://api.dayzero.cn/.well-known/acme-challenge/test` | `404 Not Found`，`Server: nginx` |
| ICP 拦截状态 | 上述两项响应头 | 不再是 `Server: Beaver`，不再返回 ICP 403（备案拦截已解除） |
| HTTPS 健康 | `curl https://api.dayzero.cn/health` | `200` |
| HTTPS 就绪 | `curl https://api.dayzero.cn/ready` | `200` |
| 无 Token fallback | `POST /api/ai/assistant-turn-v2` | `401` |
| 无 Token stream | `POST /api/ai/assistant-turn-v2-stream` | `401` |
| 证书有效期 | `openssl s_client` | Issuer `Let's Encrypt YR2`，Not After `2026-10-10 13:31:57 GMT`，链验证通过 |

结论：当前生产状态与任务书描述一致；当前证书有效，无需重新签发。

---

## 4. 文档核对结论

已通读并核对：

- `docs/GATEWAY_G2F1_ECS_DEPLOYMENT_REPORT_20260712.md`
  - 第 12 节记录了本次要修的根因：Nginx 以静态 `upstream` 块在启动/reload 时解析并缓存 Gateway 容器 IP，Gateway 重启换 IP 后首次访问 502，需手动 `nginx -s reload` 恢复。
  - 现网关键事实：Compose 文件 `/opt/dayzero-ai/docker-compose.production.yml`；Nginx 配置 `/opt/dayzero-ai/nginx/nginx.conf`（由 `nginx.production.conf.template` 渲染）；Nginx 容器 `dayzero-ai-nginx`（ACR `dayzero/nginx:alpine`）；Gateway 容器 `dayzero-ai-gateway`（digest `sha256:04ce558b…ad16`）；证书 `/opt/dayzero-ai/nginx/ssl/{fullchain,privkey}.pem`（私钥 600）；内部网络 `dayzero-ai_dayzero-net`；Gateway 8080 未暴露宿主机。
- `docs/GATEWAY_G2F1_CERTIFICATE_CHECKPOINT_20260712.md`
  - 当时因阿里云 Beaver/ICP 拦截导致 ACME HTTP-01 不可达，决策保持现状不重签。本轮公网复测确认拦截已解除，HTTP-01 路径在 Nginx 层行为正确（404 而非 403），具备配置自动续期的外部条件。

未执行任何旧 v1 恢复或重新部署。

---

## 5. 本地已就绪产物（未部署）

以下产物已在本仓库工作区准备完毕并通过本地校验，获得 ECS 访问后可直接执行：

### 5.1 Nginx 模板动态解析改造（`server/dayzero-ai-gateway/nginx.production.conf.template`，工作区未提交修改）

- 移除静态 `upstream dayzero_ai_gateway { server dayzero-ai-gateway:8080; keepalive 32; }`（IP 缓存根因）。
- 新增 `resolver 127.0.0.11 valid=5s ipv6=off;`（Docker 内嵌 DNS）+ `set $dayzero_ai_gateway "dayzero-ai-gateway:8080";`，所有 `proxy_pass` 改为变量式 `http://$dayzero_ai_gateway`，强制每次请求运行时解析，不硬编码容器 IP。
- 变量式 `proxy_pass` 不带 URI 部分，按 Nginx 语义原样透传客户端原始 URI 与 query string。
- 保持不变项（逐行核对）：`Authorization`、`X-Request-Id` 透传；SSE 路径 `proxy_buffering off` / `proxy_request_buffering off` / `X-Accel-Buffering no` / `gzip off` / 3600s 超时；AI 路径 `proxy_next_upstream off`；限流配置；`location / { return 404; }`（无任何 Supabase Edge fallback）。

### 5.2 `server/dayzero-ai-gateway/ops/certbot-reload-nginx.sh`（新增）

certbot deploy hook（安装目标 `/etc/letsencrypt/renewal-hooks/deploy/01-dayzero-nginx-cert`）：续期成功后将 `$RENEWED_LINEAGE` 下的 fullchain/privkey 先 cp 为同目录临时文件再 `mv` **原子替换**到 `/opt/dayzero-ai/nginx/ssl/`，私钥 `chmod 600`，然后 `docker exec dayzero-ai-nginx nginx -t`，**通过才** `nginx -s reload`；不触碰 Let's Encrypt lineage，不覆盖失败现场的旧证书。`bash -n` 语法校验通过。

### 5.3 `server/dayzero-ai-gateway/ops/gateway-restart-smoke-test.sh`（新增）

支持 `restart`（compose restart）与 `recreate`（compose up -d --no-deps --force-recreate）两种模式；全程**不手动 reload Nginx**，轮询公网 `/health`、`/ready` 等待自动恢复 200（最长 120s，超时即判定持续故障），随后验证两条 AI 路径无 Token 返回 401。`bash -n` 语法校验通过。

---

## 6. 未执行项（全部因 ECS 访问阻塞）

1. 现网只读确认（运行镜像 digest、容器状态、Compose 与 Nginx 配置实况）。
2. Nginx 配置回滚点备份。
3. 动态解析配置部署与 `nginx -t` 验证。
4. Gateway restart/recreate 自动恢复实测（含 401 与持续 502 判定）。
5. certbot timer/cron 检查、deploy hook 安装、`certbot renew --dry-run`。
6. 运维文档同步（需在现网变更验证后按实写入，避免记录未验证内容）。

---

## 7. 回滚状态

- 本轮未对现网执行任何写操作（无 SSH 通道，亦未进行任何其他形式的变更）。
- 无需回滚；本轮开始前的 Nginx 配置、G2-F1 Gateway、HTTPS 服务与当前有效证书均原样保持。
- 旧 v1 镜像与 `/opt/dayzero-ai-backups/20260712-141306/` 备份未触碰。

---

## 8. 范围声明

| 项目 | 是否执行 |
| --- | --- |
| 修改 Android | 否 |
| 修改 Supabase Edge Functions / Auth / 数据库 / RLS / Storage | 否 |
| 修改 Room | 否 |
| 修改 Prompt / 模型 / 超时 / Vision / 卡片协议 / 冻结业务代码 | 否 |
| 删除旧 v1 镜像或备份 | 否 |
| 暴露 Gateway 8080 | 否 |
| 重新签发/覆盖当前有效证书 | 否 |
| 输出 Secret（Kimi key、JWT、Token、.env、私钥内容等） | 否 |
| Git commit / push / reset / clean | 否（工作区修改保持未提交状态） |
| 现网写操作 | 否（无访问通道） |

---

## 9. 解除阻塞与恢复执行步骤

1. 用户将 ECS SSH 私钥放置到 `C:\Users\Goings\.ssh\id_ed25519`（权限 600），或提供其他登录方式。
2. 恢复执行清单（严格按序）：
   1. 只读确认现网镜像 digest、容器、Compose、Nginx 配置与证书路径实况；
   2. 备份当前 `nginx.conf` 至带时间戳目录（回滚点）；
   3. 渲染并临时容器 `nginx -t` 验证动态解析配置，原子替换后重建 Nginx 容器；
   4. 执行 `gateway-restart-smoke-test.sh restart` 与 `... recreate`，实测不手动 reload 下公网自动恢复 200、无 Token 401、无持续 502；
   5. 检查 certbot timer/cron，安装 deploy hook（5.2），执行 `certbot renew --dry-run`；
   6. 同步本地生产模板与运维文档，更新本报告状态。

> 只有状态变为 `GATEWAY_RUNTIME_RELIABILITY_PASSED`，才允许进入下一阶段的公网文字、SSE、JWT、卡片和 Vision 完整协议验收。
