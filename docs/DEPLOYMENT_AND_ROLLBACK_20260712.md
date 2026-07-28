# DayZero AI Gateway G2 部署与回滚指南

> 本文为 Phase G2 本地准备材料，**不得**在本轮执行任何 ECS 写操作。
> 域名：`api.dayzero.cn`
> 目标 IP：`39.106.156.166`

## 一、前置条件

1. 已完成 Phase G2 本地实现并通过全部检查：
   - `deno fmt --check`
   - `deno lint`
   - `deno check src/main.ts`
   - `deno task test`
2. 已构建并验证 Docker 镜像标签、OCI labels 与 image digest。
3. 已使用临时证书验证 `nginx -t` 与 `docker compose --env-file .env.compose.test.example -f docker-compose.production.yml config`。
4. 已准备真实域名、HTTPS 证书、DNS 解析（当前仍为缺失项，见最终报告）。
5. 已确认 `.env` 中：
   - `APP_ENV=production`
   - `ENABLE_AUTH=true`
   - `SUPABASE_JWKS_URL`、`SUPABASE_ISSUER`、`SUPABASE_AUDIENCE` 已配置
   - `ALLOWED_ORIGINS` 为实际生产来源，不能是 `*`

## 二、Source Bundle Hash 算法

Source bundle hash 用于追溯生产镜像对应的源代码快照。

范围：`server/dayzero-ai-gateway/` 下进入版本控制且参与构建的所有文件，排除：
- `.git/`、`.idea/`、`.vscode/`、`.env*`、日志、测试输出、临时证书、Docker build 缓存。

算法（Git Bash / Linux）：
```bash
cd server/dayzero-ai-gateway
find . -type f \
  ! -path './.git/*' \
  ! -path './.idea/*' \
  ! -path './.vscode/*' \
  ! -path './.env*' \
  ! -path './logs/*' \
  ! -path './coverage/*' \
  ! -path './test-output/*' \
  ! -name '*.log' \
  ! -name '*.pem' \
  ! -name '*.key' \
  ! -name '*.p12' \
  ! -name '*.pfx' \
  | sort \
  | xargs -I {} sha256sum {} \
  | sha256sum \
  | cut -d' ' -f1
```

最终输出为 64 位十六进制 SHA-256，作为 `SOURCE_BUNDLE_SHA` build arg 写入镜像 OCI label `com.dayzero.source_bundle_sha`。

## 三、首次证书签发（ACME）

1. 从至少两种独立来源确认：
   - 阿里云 DNS 控制台：`api.dayzero.cn A 39.106.156.166`
   - ECS 服务器自身 DNS 查询：
     ```bash
     dig api.dayzero.cn A +short
     nslookup api.dayzero.cn
     ```
   - 不经过 Clash 的外部设备或权威公共 DNS 工具。
2. 确认无冲突 AAAA 记录。
3. 保持旧 v1 和旧 HTTP Nginx 运行。
4. 准备 ACME webroot：
   ```bash
   mkdir -p /var/www/certbot
   ```
5. 只将 `/.well-known/acme-challenge/` 指向 webroot。
6. 使用 certbot webroot 申请：
   ```bash
   certbot certonly --webroot -w /var/www/certbot -d api.dayzero.cn
   ```
7. 校验证书：
   ```bash
   openssl x509 -in /etc/letsencrypt/live/api.dayzero.cn/fullchain.pem -noout -subject -dates -issuer
   ls -l /etc/letsencrypt/live/api.dayzero.cn/
   ```
8. 生成 G2 Nginx 配置（替换 `__DAYZERO_DOMAIN__`、`__SSL_CERTIFICATE_PATH__`、`__SSL_CERTIFICATE_KEY_PATH__`）。
9. 执行 `nginx -t`。
10. 证书或配置失败：
    - 不切换 G2。
    - 不停止 v1。
    - 保留旧 HTTP 服务。
11. 成功后进入 Gateway/Nginx 切换。

> 注意：Codex 环境解析到 `198.18.0.47`，可能受本地 Clash/TUN Fake-IP DNS 改写影响，不能作为权威公网 DNS 结论。

## 四、镜像构建与推送

生产部署必须使用 digest-only 引用。

```bash
cd server/dayzero-ai-gateway

SOURCE_BUNDLE_SHA=$(find . -type f \
  ! -path './.git/*' ! -path './.idea/*' ! -path './.vscode/*' \
  ! -path './.env*' ! -path './logs/*' ! -path './coverage/*' \
  ! -path './test-output/*' ! -name '*.log' \
  ! -name '*.pem' ! -name '*.key' ! -name '*.p12' ! -name '*.pfx' \
  | sort | xargs -I {} sha256sum {} | sha256sum | cut -d' ' -f1)

IMAGE_VERSION=g2-$(date -u +%Y%m%d-%H%M%S)
VCS_REF=$(git rev-parse HEAD)
BUILD_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

LOCAL_TAG=dayzero-ai-gateway:${IMAGE_VERSION}
ACR_REPO=<your-acr-region>.cr.aliyuncs.com/dayzero

docker build \
  --build-arg VCS_REF="${VCS_REF}" \
  --build-arg SOURCE_BUNDLE_SHA="${SOURCE_BUNDLE_SHA}" \
  --build-arg BUILD_DATE="${BUILD_DATE}" \
  --build-arg IMAGE_VERSION="${IMAGE_VERSION}" \
  -t "${LOCAL_TAG}" \
  .

docker tag "${LOCAL_TAG}" "${ACR_REPO}/dayzero-ai-gateway:${IMAGE_VERSION}"
docker push "${ACR_REPO}/dayzero-ai-gateway:${IMAGE_VERSION}"

# 取得不可变 digest（Compose 必须使用此值，不能使用 tag）
GATEWAY_IMAGE_DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' "${ACR_REPO}/dayzero-ai-gateway:${IMAGE_VERSION}" | cut -d'@' -f2)
echo "GATEWAY_IMAGE_DIGEST=${GATEWAY_IMAGE_DIGEST}"
```

`.env` 中必须设置：
```bash
GATEWAY_IMAGE_REPOSITORY=${ACR_REPO}/dayzero-ai-gateway
GATEWAY_IMAGE_DIGEST=sha256:...
```

部署脚本必须校验 `GATEWAY_IMAGE_DIGEST` 非空且以 `sha256:` 开头，否则拒绝继续。

## 五、部署顺序

1. 本地最终测试与 source bundle SHA 计算。
2. 构建镜像并推送，取得 digest。
3. 本地启动容器验证 `/health`、`/ready`、401 行为（不调用 Kimi）。
4. 填写 `deployment.manifest.template.yml` 并保存为带日期的 manifest。
5. 上传 `docker-compose.production.yml`、`nginx.production.conf.template`、manifest 到服务器部署目录。
6. 在服务器上备份当前 Compose、Nginx 配置与 `.env` 文件 hash。
7. 将模板中的占位符替换为真实域名与证书路径，生成 `nginx.production.conf`。
8. 在服务器上执行：
   ```bash
   docker compose -f docker-compose.production.yml config
   nginx -t
   ```
9. 先启动新 Gateway 容器：
   ```bash
   docker compose -f docker-compose.production.yml up -d dayzero-ai-gateway
   ```
10. 验证 Gateway 内部 `/health` 与 `/ready`：
    ```bash
    docker exec dayzero-ai-gateway deno run --allow-net --allow-env src/healthcheck.ts
    ```
11. 再启动/重载 Nginx：
    ```bash
    docker compose -f docker-compose.production.yml up -d nginx
    ```
12. 公网 HTTPS 烟雾测试：
    - 旧路径 `/health`、`/assistant-turn-v2` 401 且不触发 Kimi。
    - 新路径 `/api/ai/assistant-turn-v2` 401 且不触发 Kimi。
    - 携带测试 JWT 验证流式路径 SSE 事件正常接收。
    - 确认响应头含 `X-Request-Id`。
13. 观察 Gateway 与 Nginx 日志无敏感信息泄露。

## 六、续期

1. 定期 dry-run：
   ```bash
   certbot renew --dry-run
   ```
2. Renewal hook 先 `nginx -t`：
   ```bash
   # /etc/letsencrypt/renewal-hooks/deploy/01-reload-nginx
   #!/bin/bash
   nginx -t && nginx -s reload || exit 1
   ```
3. 只有配置通过才安全 reload。
4. reload 失败保留旧进程。
5. 不自动删除旧证书。

## 七、回滚条件

满足任一即回滚：

- `/ready` 持续失败。
- `/health` 失败或容器重启循环。
- 401/403 行为异常（如关闭 auth、错误 audience/issuer）。
- SSE 流式中断或出现缓冲延迟。
- 新路径 404/405。
- 日志出现 Token、Prompt、完整请求体。
- 任何 5xx 持续出现。
- 证书未签发、验证失败或 Nginx 配置失败。

## 八、回滚步骤

> 不得使用 `docker compose ... down <service>`（该命令无效）。

1. 停止继续切流。
2. 恢复备份的 v1 Compose 文件（image 使用记录的 v1 digest）。
3. 恢复备份的旧 Nginx 配置。
4. 执行：
   ```bash
   docker compose -f docker-compose.production.yml up -d
   ```
5. 等待旧 `/health=200`。
6. 验证旧路径 401。
7. `nginx -t` 后 reload：
   ```bash
   nginx -t && nginx -s reload
   ```
8. 验证 HTTP/HTTPS 旧链路。
9. 不删除 G2 镜像、证书或故障证据。
10. 不修改 Supabase Edge、Android、Room 或同步系统。
11. 记录回滚原因与时间点。

## 九、证书失败策略

> 证书未签发、验证失败或 Nginx 配置失败时，保持旧 v1 和旧 HTTP 服务，不进入 Android 切流。

## 十、安全边界

- 不得在镜像层或 Compose 中写入 Secret。
- 不得覆盖 `dayzero-ai-gateway:v1` tag。
- 不得在回滚时修改 `.env` 中的 Secret 值。
- 回滚后仍需保持 `ENABLE_AUTH=true`。
- 生产 Compose 必须使用 `repository@sha256:<digest>`，禁止依赖浮动 tag。
