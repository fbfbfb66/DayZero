# DayZero 生产运行基线回收报告

## 1. 最终状态

**PARTIAL_BASELINE_CAPTURED**

- Supabase 远端生产基线已取得：完整回读了 `assistant-turn-v2` v31 与 `assistant-turn-v2-stream` v25 的源码、metadata、配置和 bundle hash。
- 阿里云现网服务器只读检查被阻塞：本机未找到 ECS 公网 IP、域名或已配置 SSH Host；无法建立安全 SSH 目标。
- 已验证本地 `server/dayzero-ai-gateway/` 候选源码与已推送 ACR 镜像 `crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/dayzero-ai-gateway:v1` 的 `/app/src` 字节级一致。
- 当前仍不能回答阿里云现网是否运行该镜像、生效 Nginx 配置、公网路由、`/ready`、JWT、SSE buffering 等运行时问题。
- 进入本地 Gateway 施工阶段的证据仍然不足：远端 Supabase Streaming timeout 基线已可确定，但阿里云现网状态无法验证。

阻塞编码：**BLOCKED_MISSING_ALIYUN_CONNECTION_INFO**

## 2. Git 起始状态

| 项目 | 值 |
| --- | --- |
| HEAD | `2bd958e2444256f289c7f14dc37629c26eab9669` |
| `git status --short -uall` SHA-256 | `7f0b9fa88737a28223ea17d199f95f52726c5efddeb84e5bf1f50334bcee379c` |
| `git diff --stat` SHA-256 | `6eae87931526b73d3641a3dcc1be16db04bbc7f87d358811c52efe8b7ad68821` |
| `git diff --check` SHA-256 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| 状态说明 | 任务开始前已存在大量未提交改动、删除项和未跟踪文件；本轮未恢复、未删除、未提交、未 reset/clean |

结束状态与起始状态完全一致。

## 3. Supabase 远端函数

| 函数 | 版本 | ACTIVE | verify_jwt | 部署时间（Supabase 内部 ms） | bundle hash | Prompt | timeout | 证据 |
| --- | ---: | --- | --- | --- | --- | --- | --- | --- |
| assistant-turn-v2 | 31 | ACTIVE | false | 1783704169145 | `25dad4a55d3c9fe745fde4ba3aa72d4a8978153645d4176cdb704ba14b65e2ef` | `compact_v8_deterministic_multi_meal_photo_assignment` | 50s total (`FALLBACK_UPSTREAM_TOTAL_TIMEOUT_MS`) | MCP `list_edge_functions` + `get_edge_function` |
| assistant-turn-v2-stream | 25 | ACTIVE | false | 1783708411745 | `f938d843818b56d968dd823c1998ead7ecce603d165563717cf92b22aa4c56e9` | `stream_compact_v7_deterministic_multi_meal_photo_assignment` | 15s/20s/35s candidate-race budget + 动态 header timeout 仅写入 debugTiming | MCP `list_edge_functions` + `get_edge_function` |

远端文件清单与 SHA-256：

**assistant-turn-v2 v31**

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `_shared/assistant_upstream_timeout.ts` | 1,055 | `9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37` |
| `_shared/assistant_vision.ts` | 10,185 | `0524a31a440833532dda37863695527534dc685a40fb6165cde6f1c5358e1b46` |
| `_shared/explicit_photo_meal_assignment.ts` | 6,818 | `245076f31668a5021a8dc6d7fd928543f1cf41446b551600da97bdfe5a78a177` |
| `source/handler.ts` | 21,238 | `e60fd88ee2d71172f5e4c11e0d3acf93310fac83899b223bad46318c65a5d21a` |
| `source/index.ts` | 119 | `902afa06899ffb1dd711287c206f29b619754f92e105b693aa6fc367c6e1e550` |
| `source/normalization.ts` | 25,410 | `0b1f66fcac095655b00c60ba6359761a29bdd859d385d4e898b5c0ffea6ae03c` |

**assistant-turn-v2-stream v25**

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `_shared/assistant_upstream_timeout.ts` | 1,055 | `9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37` |
| `_shared/assistant_vision.ts` | 10,185 | `0524a31a440833532dda37863695527534dc685a40fb6165cde6f1c5358e1b46` |
| `_shared/explicit_photo_meal_assignment.ts` | 6,818 | `245076f31668a5021a8dc6d7fd928543f1cf41446b551600da97bdfe5a78a177` |
| `source/handler.ts` | 31,027 | `9d42e388cc11ecb8bca72027e9c57f5b70a311cdd6a089eff0c5d1ff2b623946` |
| `source/index.ts` | 119 | `902afa06899ffb1dd711287c206f29b619754f92e105b693aa6fc367c6e1e550` |
| `source/normalization.ts` | 25,410 | `0b1f66fcac095655b00c60ba6359761a29bdd859d385d4e898b5c0ffea6ae03c` |

公共常量（两个函数共享）：

- `TEXT_STREAM_HEADER_TIMEOUT_MS = 15_000`
- `BASE_VISION_HEADER_TIMEOUT_MS = 15_000`
- `PER_MIB_TIMEOUT_MS = 10_000`
- `MIN_VISION_HEADER_TIMEOUT_MS = 25_000`
- `MAX_VISION_HEADER_TIMEOUT_MS = 50_000`
- `FALLBACK_UPSTREAM_TOTAL_TIMEOUT_MS = 50_000`

关键环境变量（仅名称，不输出值）：

- `MOONSHOT_API_KEY=[PRESENT]`
- 模型在源码中硬编码为 `kimi-k2.6`

## 4. Supabase Streaming 实现结论

**明确结论：远端 v25 同时存在动态 header timeout 计算 与 多个 candidate 并行竞速，但动态 header timeout 不控制实际 fetch AbortSignal。**

证据：

1. `selectStreamHeaderTimeoutMs(attachmentCount, totalDecodedAttachmentBytes)` 被调用并赋值给 `selectedStreamHeaderTimeoutMs`。
2. `selectedStreamHeaderTimeoutMs` 最终只写入 `debugTiming.selectedStreamHeaderTimeoutMs`，未传入 `fetch` 的 `AbortSignal`。
3. 实际控制上游请求的是 `raceMoonshotCandidates` 中的 `setTimeout(input.budgetMs, ...controllers.forEach(abort))`：
   - text-only / 无 continuation media：`15_000` ms
   - continuation 含 media：`20_000` ms
   - 当前请求含 attachments：`35_000` ms
4. 并行 candidate 数量：
   - `hedgedAttemptCount = attachmentCount > 0 || continuationHasMedia ? 3 : 1`
   - candidate 0 为 streaming reader，其他为 JSON 非流请求
   - 第一个产生合法 final JSON 的 candidate 获胜，其余立即 `controller.abort()`
5. `debugTiming` 还记录了 `hedgedAttemptCount`、`winnerAttemptIndex`、`cancelledAttemptCount`、`provisionalTextReplaced`，证明 candidate race 是真实运行时行为。

因此：

- 文档中“v17 动态 header timeout”的描述已部分过期；v25 的 header timeout 只是观测指标。
- Fallback v31 保持 50 秒总 timeout，无 candidate race，与文档一致。
- 远端 Prompt 版本字符串与本地 Gateway 完全一致：
  - fallback：`compact_v8_deterministic_multi_meal_photo_assignment`
  - stream：`stream_compact_v7_deterministic_multi_meal_photo_assignment`

## 5. 阿里云现网运行状态

| 项目 | 当前值 | 证据 | 状态 |
| --- | --- | --- | --- |
| 域名/端口 | 未知 | 未找到 ECS IP、域名或 SSH Host | BLOCKED |
| 容器 | 未知 | 无 SSH 目标；本地 `docker ps` 为空 | BLOCKED |
| 镜像 digest | `sha256:e0518d64a305be3f63bdcbf69a22953c20a74d06372ff883ac51463ba7324935` | 本地 ACR 镜像 `docker inspect` | VERIFIED (本地镜像) |
| 源码 hash | 镜像内 `/app` bundle: `aca9de60818e49727147492d160f3930fe63c96b3ae449a5155feb3fc473ee3d` | `docker run --rm --read-only` 内 `find + sha256sum` | VERIFIED (本地镜像) |
| restart policy | 未知 | 未访问服务器 | BLOCKED |
| health | `/health` 在本地 Gateway 源码中存在；公网未知 | `server/dayzero-ai-gateway/src/handlers/health.ts` | UNVERIFIED |
| ready | 不存在 `/ready` | `server/dayzero-ai-gateway/src/main.ts` 仅注册 `/health` | VERIFIED_NO (本地候选) |
| JWT | 本地候选默认 `ENABLE_AUTH=true`；现网未知 | `src/config.ts:72`、`.env.example:23` | UNVERIFIED |
| Kimi 模型 | 本地候选 `.env.example` 要求 `KIMI_MODEL=kimi-k2.6`；现网运行值未知 | `.env.example:9` | UNVERIFIED |

已确认镜像信息：

- 仓库：`crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/dayzero-ai-gateway`
- tag：`v1`
- image ID：`e0518d64a305`
- digest：`sha256:e0518d64a305be3f63bdcbf69a22953c20a74d06372ff883ac51463ba7324935`
- 创建时间：`2026-07-12T05:54:32Z`
- 架构：`amd64/linux`
- 暴露端口：`8080/tcp`
- 工作目录：`/app`
- Entrypoint：`/tini -- docker-entrypoint.sh`
- CMD：`deno run --frozen --allow-net --allow-env --allow-read src/main.ts`
- 镜像内源文件 31 个，总字节 136,674
- 镜像标签仅包含 Deno 基础镜像 OCI 标签，无 DayZero commit/source hash label

其他已推送镜像：

- `crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/deno:2.0.5`
- `crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/deno:2.9.0`
- `crpi-475iv1vc2zvscamw.cn-beijing.personal.cr.aliyuncs.com/dayzero/nginx:alpine`

## 6. Nginx 生效配置

未能读取服务器生效 Nginx 配置（无 SSH 目标）。本地仅有示例配置：

- 文件：`server/dayzero-ai-gateway/nginx.example.conf`
- SHA-256：`b933a394e58f25ef937694dd63a5603659e335c07b353c16db08f570412d02aa`
- 关键 SSE 指令状态（示例）：
  - `proxy_http_version 1.1`：有
  - `proxy_buffering off`：有
  - `proxy_cache off`：有
  - `proxy_request_buffering off`：无
  - `X-Accel-Buffering no`：无
  - `proxy_read_timeout 120s`：有
  - `proxy_send_timeout 120s`：有
  - gzip 对 `text/event-stream` 规则：未明确排除

现网真实配置未知，因此 SSE 是否被缓冲：**UNVERIFIED**。

## 7. 现网与本地 Gateway 对比

### 7.1 镜像内源码 vs 本地源码

| 文件或模块 | 本地 hash | 现网（镜像内）hash | 是否一致 | 影响 |
| --- | --- | --- | --- | --- |
| `deno.json` | `99b658924c03ec33ef5a1929aba6cd9a22b339e425ad16d4eac6d4ae2e9eaf44` | `99b658924c03ec33ef5a1929aba6cd9a22b339e425ad16d4eac6d4ae2e9eaf44` | 一致 | 无 |
| `deno.lock` | （未在本地根目录单独列出） | `15cf4c6e678c2f9ccf5288a8850d75d3684a7e5c5b60e9c44cbcf708722ef350` | — | lockfile 仅存在于镜像 |
| `src/auth.ts` | `296d2334...` | `296d2334...` | 一致 | 无 |
| `src/handlers/assistant_turn_v2.ts` | `ee0abd71...` | `ee0abd71...` | 一致 | 无 |
| `src/handlers/assistant_turn_v2_stream.ts` | `14a669f3...` | `14a669f3...` | 一致 | 无 |
| `src/shared/assistant_upstream_timeout.ts` | `9e5cd566...` | `9e5cd566...` | 一致 | timeout 基线一致 |
| `src/shared/assistant_vision.ts` | `84797451...` | `84797451...` | 一致 | 无 |
| `src/shared/kimi_stream.ts` | `f2fb8542...` | `f2fb8542...` | 一致 | candidate race 逻辑一致 |
| `src/shared/normalization.ts` | `bba2537f...` | `bba2537f...` | 一致 | 无 |
| `src/shared/prompt.ts` | `f616f8b1...` | `f616f8b1...` | 一致 | Prompt 版本一致 |
| 其他 20 个 src 文件 | 均一致 | 均一致 | 一致 | 无 |

结论：**本地 `server/dayzero-ai-gateway/` 与已推送 ACR 镜像 `dayzero-ai-gateway:v1` 的 `/app/src` 字节级一致（31/31 文件匹配）。**

不匹配文件：镜像未包含 `README.md`、`docker-compose.yml`、`nginx.example.conf`，这是 Dockerfile 只复制 `deno.json`、`deno.lock`、`src` 的预期行为。

### 7.2 远端 Supabase vs 本地 Gateway

| 文件或模块 | 远端 hash | 本地 hash | 是否一致 | 说明 |
| --- | --- | --- | --- | --- |
| `assistant_upstream_timeout.ts` | `9e5cd566...` | `9e5cd566...` | 一致 | timeout 常量与 `selectStreamHeaderTimeoutMs` 字节级相同 |
| Prompt 版本字符串 | `compact_v8...` / `stream_compact_v7...` | 完全相同 | 一致 | 本地 `prompt.ts` 常量与远端 handler 硬编码一致 |
| Kimi 模型 | 硬编码 `kimi-k2.6` | 默认 `kimi-k2.6`（`KIMI_MODEL`） | 意图一致 | 本地通过环境变量注入，需现网值确认 |
| `assistant_vision.ts` | `0524a31a...` | `84797451...` | 不一致 | 需进一步语义 diff |
| `explicit_photo_meal_assignment.ts` | `245076f3...` | `c28f3e29...` | 不一致 | 需进一步语义 diff |
| `normalization.ts` | `0b1f66fc...` | `bba2537f...` | 不一致 | 需进一步语义 diff |
| fallback handler | `e60fd88e...` | `ee0abd71...` | 不一致 | 本地已重构为 config/logger/errors/client 模块化；逻辑等价待确认 |
| stream handler | `9d42e388...` | `14a669f3...` | 不一致 | 本地已重构；candidate race 逻辑保留 |

**bundle 级对比**：

- 远端 fallback v31 bundle：`25dad4a55d3c9fe745fde4ba3aa72d4a8978153645d4176cdb704ba14b65e2ef`
- 远端 stream v25 bundle：`f938d843818b56d968dd823c1998ead7ecce603d165563717cf92b22aa4c56e9`
- 本地 Gateway 源码 bundle：`de59a60a0cdd9c0afba9021c6b46e96c575fff6f8dab778a9dc684b27c2661c1`
- 镜像内 `/app` 源码 bundle：`aca9de60818e49727147492d160f3930fe63c96b3ae449a5155feb3fc473ee3d`

## 8. 关键问题答案

| # | 问题 | 答案 | 证据 |
| ---: | --- | --- | --- |
| 1 | Supabase 远端 stream 是否为 Version 17 | VERIFIED_NO | MCP 回读版本为 **25** |
| 2 | Supabase 远端 fallback 是否为 Version 28 | VERIFIED_NO | MCP 回读版本为 **31** |
| 3 | 远端 stream 是否使用动态 header timeout | VERIFIED_YES | 调用 `selectStreamHeaderTimeoutMs`；但仅写入 `debugTiming` |
| 4 | 远端 stream 是否使用多个 candidate 并行竞速 | VERIFIED_YES | `hedgedAttemptCount` 1/3，`Promise.race`，`cancelledAttemptCount` |
| 5 | 当前本地 Supabase 源码是否与远端相同 | UNVERIFIED | 本地 `supabase/functions/` 未与远端做逐文件 hash 比对；本轮只读了远端，未比对本地 Edge 目录 |
| 6 | 阿里云现网是否使用本地 Gateway 候选 | UNVERIFIED | 已证明 ACR 镜像与本地源码字节一致，但无法访问 ECS 确认现网运行容器 |
| 7 | 阿里云现网是否已有 `/api/ai/` 路由 | UNVERIFIED | 本地候选 `src/main.ts` 无此前缀；现网 Nginx 未知 |
| 8 | 阿里云现网是否已有 `/ready` | VERIFIED_NO (本地候选) | 本地候选无 `/ready`；现网未知 |
| 9 | 阿里云生效 Nginx 是否会缓冲 SSE | UNVERIFIED | 仅读取 example 配置，无现网 `nginx -T` |
| 10 | 阿里云生产是否启用 JWT 验证 | UNVERIFIED | 本地候选默认 `ENABLE_AUTH=true`；现网环境变量未知 |
| 11 | 生产镜像是否能追溯到 Git commit 或 source hash | VERIFIED_NO | 镜像无自定义 commit/source label；仅含 Deno 基础镜像 OCI 标签 |
| 12 | Gateway 当前 Kimi 模型是否与 Supabase 基线相同 | UNVERIFIED | 本地 `.env.example` 要求 `kimi-k2.6`，与远端硬编码一致；但现网运行值未知 |
| 13 | 是否已经具备进入 Gateway 本地施工阶段的充分证据 | VERIFIED_NO | 阿里云现网状态未知；无法确认路由/Nginx/JWT/SSE/ready |

## 9. 仍缺少的信息

1. **ECS 公网 IP 或域名**：本机无 SSH config、无 known_hosts 记录、无 PowerShell/VS Code Terminal 历史中的 SSH 目标。
2. **SSH 用户与端口**：未找到 root/ubuntu/ecs-user 等登录信息。
3. **现网运行容器 inspect**：无法执行 `docker ps`、`docker inspect`、`nginx -T`、`ss -lntp`。
4. **现网生效 Nginx 配置**：无法证明 SSE buffering 状态。
5. **现网 `/health`、`/ready`、路由探测**：无公网地址。
6. **现网环境变量值**：`KIMI_MODEL`、`ENABLE_AUTH` 等运行值未知。
7. **本地 `supabase/functions/` 是否与远端 v31/v25 一致**：未做本地 Edge 源码与远端的逐文件 hash 比对。

## 10. 下一阶段允许修改的内容

基于本轮证据：

**可以修改（本地 Gateway 施工）：**

- `server/dayzero-ai-gateway/src/main.ts`：增加 `/ready`、增加 `/api/ai/` 路由前缀或别名。
- `server/dayzero-ai-gateway/src/handlers/`：新增 readiness handler。
- `server/dayzero-ai-gateway/src/request_id.ts`：接受入站 `X-Request-Id`、转发、回显。
- `server/dayzero-ai-gateway/nginx.example.conf`：补齐 `proxy_request_buffering off`、`X-Accel-Buffering no`、`X-Request-Id`、gzip SSE 排除。
- `server/dayzero-ai-gateway/Dockerfile`：增加 OCI source/commit/digest labels。
- `server/dayzero-ai-gateway/docker-compose.yml`：固定 image tag/digest，增加 rollback 镜像机制。
- `server/dayzero-ai-gateway/src/auth.ts`、`src/logger.ts`：按审计要求加固 JWT、脱敏日志。

**必须冻结：**

- Supabase 远端 v31 fallback 行为：50s total timeout、`kimi-k2.6`、Prompt version `compact_v8...`。
- Supabase 远端 v25 stream 行为：candidate race 预算 15s/20s/35s、动态 header timeout 仅观测、`stream_compact_v7...`。
- `assistant_upstream_timeout.ts` 常量：已与远端字节一致，不得改动。
- Prompt 版本字符串：已与远端一致。

**必须人工确认：**

- 阿里云 ECS 访问方式（IP/域名/SSH key/用户/端口）。
- 公开路径契约：是否保留旧 `/assistant-turn-v2[-stream]`，是否强制 `/api/ai/...`。
- 生产 `KIMI_MODEL`、`ENABLE_AUTH` 等环境变量真实值。
- 部署窗口与回滚策略。

**仍然禁止修改：**

- Supabase Edge Function 源码/部署/Secrets。
- Supabase Auth/Database/RLS/Storage。
- 阿里云服务器任何文件、容器、Nginx、防火墙。
- Android 源码。
- 真实 Kimi API 调用或用户聊天内容发送。
- Git 写操作。

## 11. 执行过的命令

| 命令 | 退出码 | 结果摘要 | 是否涉及远端 |
| --- | ---: | --- | --- |
| `git rev-parse HEAD` | 0 | `2bd958e...` | 否 |
| `git status --short -uall` | 0 | 大量既有变更；hash 已固定 | 否 |
| `git diff --stat` | 0 | 316 tracked 文件，`+894/-49,756` | 否 |
| `git diff --check` | 0 | 通过；仅有 LF/CRLF 警告 | 否 |
| 本地文件搜索（Glob/Grep） | 0 | 定位 Gateway、Edge、文档、部署文件 | 否 |
| `cat ~/.ssh/config` | 0 | `NO_SSH_CONFIG` | 否 |
| `cat ~/.ssh/known_hosts` | 0 | 仅 `github.com` | 否 |
| PowerShell 历史搜索 | 0 | 发现 ACR 镜像 push 记录；无 ECS SSH | 否 |
| `mcp__dayzero-supabase__list_edge_functions` | 0 | 取得 v31/v25 版本与 metadata | 是（Supabase 只读） |
| `mcp__dayzero-supabase__get_edge_function` ×2 | 0 | 取得两个函数完整源码 | 是（Supabase 只读） |
| Python 解析远端 JSON + SHA-256 | 0 | 计算 bundle hash 与关键字段 | 否 |
| `docker images` | 0 | 发现本地 ACR 镜像 `dayzero-ai-gateway:v1` | 否 |
| `docker inspect` + Python 解析 | 0 | 取得镜像 ID/digest/配置/labels | 否 |
| `docker run --rm --read-only ... sha256sum` | 0 | 计算镜像内 `/app/src` 各文件 hash | 否（本地只读容器） |
| `docker ps --no-trunc` | 0 | 无运行容器 | 否 |
| 本地 Gateway 文件 hash 脚本 | 0 | 34 个文件 hash；与镜像 31 个文件一致 | 否 |
| 远端 vs 本地共享文件对比 | 0 | `assistant_upstream_timeout.ts` 字节一致；vision/normalization/photo_assignment 不一致 | 否 |
| `git status/diff --check` 最终 | 0 | 与起始 hash 一致 | 否 |

未执行：

- SSH 远端命令：无可访问 Host。
- `curl /health`、`/ready`：无权威公网地址或本地运行容器。
- `docker inspect`（现网容器）、`nginx -T`、`journalctl`：无 ECS 访问。
- 真实 Kimi 请求。

## 12. 安全与范围声明

- 是否修改本地文件：**否**（仅创建了本审计报告 `docs/DAYZERO_PRODUCTION_BASELINE_REPORT_20260712.md`）。
- 是否修改服务器：**否**。
- 是否部署：**否**。
- 是否重启/停止/reload/重建容器：**否**。
- 是否调用 Kimi：**否**。
- 是否写 Supabase：**否**（仅使用 MCP 只读工具）。
- 是否输出 Secret：**否**；所有敏感值显示为 `[PRESENT]`、`[REDACTED]` 或未输出。
- Git 状态是否与开始一致：**是**；HEAD、status hash、diff stat hash、`git diff --check` hash 均未变化。
