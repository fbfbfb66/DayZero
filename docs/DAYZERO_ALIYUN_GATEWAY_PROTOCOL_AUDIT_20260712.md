# DayZero 阿里云 Gateway 协议等价审计报告

## 1. 最终判定

**BLOCKED_INSUFFICIENT_ACCESS**

无法证明阿里云当前部署的 Gateway、镜像、环境变量名称、Nginx 生效配置与本地候选源码一致，原因是：

- 本地 Gateway 位于 `server/dayzero-ai-gateway/`，但整个目录未被 Git 跟踪。
- 本机没有 SSH Host 配置，没有可识别的 ECS 访问别名。
- `docker ps` 未发现本地运行中的 Gateway 容器。
- 本地仅有 `nginx.example.conf`，不是服务器生效配置。
- 没有镜像 digest、源码 commit label、部署 manifest 或回滚镜像记录。

即使暂不考虑远端证据缺失，本地候选也不能直接进入受控部署：路由缺少 `/api/ai/` 前缀和 `/ready`，实际 timeout 逻辑与远端 v17 文档基线不一致，Nginx SSE 配置不完整，镜像不可追溯，并存在认证与日志问题。

## 2. 审计范围

已检查：

- `supabase/functions/assistant-turn-v2/`
- `supabase/functions/assistant-turn-v2-stream/`
- `supabase/functions/_shared/`
- `server/dayzero-ai-gateway/`
- Android DTO、Mapper、Retrofit、SSE Client、Repository、Orchestrator、JWT interceptor。
- `docs/PROJECT_CONTEXT_FOR_CHATGPT.md`
- `docs/DEVELOPMENT_LOG.md`
- `docs/AI_ASSISTANT_TURN_V2_ARCHITECTURE.md`
- Dockerfile、Compose、Nginx example、Deno 配置和测试。
- 本地 SSH/Docker 可用性。
- Supabase 当前官方 Edge/Auth 变更记录；未发现会推翻本次本地证据的相关变更。

未找到：

- `服务器迁移.txt`
- `服务器迁移计划.txt`
- 阿里云生效 Nginx 配置。
- 当前容器 inspect、镜像 history/digest、journal。
- ECS 端部署目录和源码 hash。
- 当前公网 `/health`、`/ready` 地址。
- Supabase 远端 v17/v28 的实时只读 readback；只能采用 2026-07-10/12 文档记录。

当前 Git commit：

`2bd958e2444256f289c7f14dc37629c26eab9669`

开始与结束 Git 状态完全一致：

- 状态条目：691
- ` D`：304
- ` M`：12
- `??`：375
- 状态 SHA-256：`0f3537de289c8bddb450fb5523e276ca5d8a9638b96443ad3f2c4873e373be6f`
- tracked diff：`+894 / -49,756`
- `git diff --check`：通过，仅有既存 LF/CRLF 警告。

这些是任务开始前已有变更，本轮未恢复或覆盖。

## 3. 当前基线冻结

| 项目 | Supabase 当前值 | 阿里云当前值 | 证据 | 状态 |
| --- | --- | --- | --- | --- |
| 源码 commit / hash | 本地 tracked：HEAD `2bd958e…`; 9 个生产文件规范 bundle SHA `614f6893…ceb48` | 本地 untracked 候选；26 文件 bundle SHA `b836b4c1…543b`；部署对应关系未知 | Git 状态；Gateway scoped status 为 `?? server/dayzero-ai-gateway/` | UNVERIFIED |
| Streaming 版本 | 文档记录远端 v17 ACTIVE | 无可验证部署版本；`deno.json` 仅声明包版本 1.0.0 | `docs/PROJECT_CONTEXT_FOR_CHATGPT.md:44`，`server/dayzero-ai-gateway/deno.json:2` | UNVERIFIED |
| Fallback 版本 | 文档记录远端 v28 ACTIVE | 无可验证部署版本 | `docs/PROJECT_CONTEXT_FOR_CHATGPT.md:44` | UNVERIFIED |
| Prompt 版本 / hash | fallback `compact_v8…`；System Prompt 5,680 chars / 9,256 UTF-8 bytes / SHA `90e12b96…769d`。stream `stream_compact_v7…`；5,679 chars / 9,261 bytes / SHA `4781da9a…d7a9` | 两个版本字符串均存在；实际统一调用 fallback Prompt，SHA `90e12b96…769d` | `server/dayzero-ai-gateway/src/shared/prompt.ts:3`，`supabase/functions/assistant-turn-v2/handler.ts:72`，`supabase/functions/assistant-turn-v2-stream/handler.ts:71` | SAFE_DIFFERENCE |
| Kimi 模型 | 源码硬编码 `kimi-k2.6` | 环境变量 `KIMI_MODEL`，运行值未知；测试为 `kimi-k2.6` | `supabase/functions/assistant-turn-v2/handler.ts:142`，`server/dayzero-ai-gateway/src/config.ts:63` | UNVERIFIED |
| Streaming timeout | 远端 v17 文档：text 15s；Vision header `clamp(25s,15s+10s/MiB,50s)` | 本地候选实际为完整候选竞速预算：text 15s、Vision 35s、continuation 20s；动态 helper 只写入 timing，未控制 fetch | `docs/PROJECT_CONTEXT_FOR_CHATGPT.md:45`，`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2_stream.ts:120`，`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2_stream.ts:130` | BLOCKER |
| Fallback timeout | 50s total | 50s total | `supabase/functions/_shared/assistant_upstream_timeout.ts:6`，`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2.ts:108` | EQUIVALENT |
| Gateway 镜像 tag | 不适用 | Compose 只有 `build`，没有固定 `image` tag/digest | `server/dayzero-ai-gateway/docker-compose.yml:3` | BLOCKER |
| Nginx 配置版本 | Supabase 托管，不适用 | 仅 example，SHA `b933a394…02aa`；现网未知 | `server/dayzero-ai-gateway/nginx.example.conf:1` | UNVERIFIED |
| JWT 验证 | 文档记录 `verify_jwt=false` | 本地候选使用 JWKS/ES256/iss/aud/sub；现网 `ENABLE_AUTH` 未验证 | `docs/PROJECT_CONTEXT_FOR_CHATGPT.md:119`，`server/dayzero-ai-gateway/src/auth.ts:41` | INTENTIONAL_DIFFERENCE |

Prompt 说明：

- fallback 与 Gateway System Prompt 字节完全一致。
- streaming 与 Gateway 仅一处“any/任何”的同义措辞差异，未发现协议、工具、营养、Vision、continuation 或 sourceMediaIds 语义变化。
- 动态 User Prompt 的日期、最近消息、todayRecord、interaction_result 与 continuation 拼接逻辑一致；有效 hash 会随请求内容变化，因此没有伪造单一运行时 hash。
- Gateway 将 streaming 的 debug 版本标成 `stream_compact_v7…`，实际使用的却是 fallback 字节版本。虽然当前唯一差异语义等价，仍应拆分两个冻结常量，防止未来 Prompt 版本漂移。

## 4. 协议差异矩阵

| 编号 | 审计领域 | Supabase 行为 | 阿里云行为 | 状态 | 严重度 | 证据 | 必需修改 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 路由 | 托管路径为 `/functions/v1/assistant-turn-v2[-stream]` | 仅 `/assistant-turn-v2[-stream]`；无 `/api/ai/`；无 `/ready` | BLOCKER | CRITICAL | `server/dayzero-ai-gateway/src/main.ts:29` | 增加目标路由或明确 Nginx rewrite；实现独立 readiness |
| 2 | DTO | 实际字段为 `traceId,date,userText,todayRecord,pendingDraft,recentMessages,…,attachments` | 解析相同字段；不严格验证 date/turnType/interactionResult | EQUIVALENT | HIGH | `core/network/src/main/java/com/goings/dayzero/data/remote/dto/assistant/AiAssistantRequestDto.kt:7`，`server/dayzero-ai-gateway/src/shared/request_parser.ts:21` | 建 schema 验证；文档不要再把 requestId/conversationId/history 误写为线上 DTO |
| 3 | Prompt | 两个版本只有一处同义措辞差异 | Gateway 实际统一使用 fallback Prompt | SAFE_DIFFERENCE | LOW | 上述 Prompt hash；`server/dayzero-ai-gateway/src/shared/prompt.ts:7` | 分离并测试两套冻结 Prompt |
| 4 | Kimi body | URL/model 固定；system→user；JSON format；1500；0.6；thinking disabled | body 字段等价；URL/model/参数可由环境覆盖且现网值未知 | UNVERIFIED | HIGH | `supabase/functions/assistant-turn-v2-stream/handler.ts:606`，`server/dayzero-ai-gateway/src/shared/kimi_client.ts:18` | 只读核验运行环境名称和脱敏值摘要；冻结非 Secret 参数 |
| 5 | Vision | 1–6 JPEG；640 KiB/张；4 MiB 总计；严格 Base64；顺序保持；interaction_result 禁附件 | 实现行为等价 | EQUIVALENT | INFO | `supabase/functions/_shared/assistant_vision.ts:52`，`server/dayzero-ai-gateway/src/shared/assistant_vision.ts:51` | 增加 Gateway 六图、总超限、重复 mediaId 测试 |
| 6 | normalizeActions | 营养、体重、continuation、照片白名单、单餐默认、多餐去重 | 除格式和内部函数重命名外等价 | EQUIVALENT | INFO | `supabase/functions/assistant-turn-v2/normalization.ts:143`，`server/dayzero-ai-gateway/src/shared/normalization.ts:142` | 将 Edge normalization fixtures 直接复用于 Gateway |
| 7 | Card | 只允许四类服务器卡；Date Guard 为客户端本地卡 | 同样拒绝未知服务器 action type；不生成 Date Guard | EQUIVALENT | INFO | `server/dayzero-ai-gateway/src/shared/protocol.ts:23`，`core/network/src/main/java/com/goings/dayzero/data/remote/dto/assistant/AiChatCardDto.kt:44` | 补 local-only 边界测试 |
| 8 | 空 reply / unknown | Edge 和 Android mapper 均拒绝空 reply；Moshi 会丢弃未知响应字段 | Gateway 同样拒绝空 reply | EQUIVALENT | HIGH | `supabase/functions/assistant-turn-v2-stream/handler.ts:162`，`core/network/src/main/java/com/goings/dayzero/data/remote/mapper/AssistantTurnV2ResponseMapper.kt:27`，`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2_stream.ts:158` | 若“纯卡片空 reply 合法”仍是要求，需单独协议升级，不能在迁移中暗改 |
| 9 | SSE | 本地源码：status→delta*→final→timing→done；错误后不发 final/done | 本地候选等价；未设置 `X-Accel-Buffering: no` | EQUIVALENT | HIGH | `supabase/functions/assistant-turn-v2-stream/handler.ts:262`，`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2_stream.ts:237` | 增加响应头与断流/取消测试 |
| 10 | Streaming 上游实现 | 远端 v17 文档是动态 header timeout；当前本地 HEAD 已变为最多 3 个并行 Kimi candidates | Gateway 复制当前本地 candidate race | BLOCKER | CRITICAL | `docs/PROJECT_CONTEXT_FOR_CHATGPT.md:45`，`supabase/functions/assistant-turn-v2-stream/handler.ts:126`，`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2_stream.ts:130` | 先读回远端源码；选定唯一基线，禁止猜测 |
| 11 | Fallback | `{reply,actions,debugTiming}`；50s；无跨服务 fallback | 本地候选等价；未发现调用 Supabase Edge 的代码 | EQUIVALENT | INFO | `supabase/functions/assistant-turn-v2/handler.ts:125`，`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2.ts:224` | 增加“绝不调用 Supabase Edge”负向测试 |
| 12 | requestId | Supabase 使用客户端 `traceId`；业务 DTO无 requestId/conversationId | Gateway 忽略传入 `X-Request-Id`，每次生成新 ID；Nginx 未转发该 header | BLOCKER | HIGH | `server/dayzero-ai-gateway/src/main.ts:16`，`server/dayzero-ai-gateway/src/request_id.ts:1`，`server/dayzero-ai-gateway/nginx.example.conf:52` | 接受合法入站 ID，否则生成；转发给上游并回显 |
| 13 | 取消 | Edge/Gateway candidate race 会取消输掉的 Kimi 请求，但未把客户端断开连接到全部上游 controller | 同样未实现下游断开取消 | EQUIVALENT | HIGH | `server/dayzero-ai-gateway/src/shared/kimi_stream.ts:44`，`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2_stream.ts:79` | 接入 request signal/ReadableStream cancel，并补测试 |
| 14 | JWT/JWKS | 远端记录 `verify_jwt=false` | ES256、iss、aud、sub、JWKS cache；可用环境变量关闭；未强制存在 exp/kid | INTENTIONAL_DIFFERENCE | HIGH | `server/dayzero-ai-gateway/src/auth.ts:29`，`server/dayzero-ai-gateway/src/auth.ts:61`，`server/dayzero-ai-gateway/src/config.ts:72` | 生产禁止关闭；要求 exp/kid；增加错签名/缺 claim 测试 |
| 15 | Android 401 | 新 AI interceptor 可刷新一次并重放同一 Request | 与 Gateway 认证目标兼容，但 endpoint 仍指向 Supabase | INTENTIONAL_DIFFERENCE | INFO | `core/network/src/main/java/com/goings/dayzero/data/remote/auth/AiGatewayAuthInterceptor.kt:33`，`core/network/src/main/java/com/goings/dayzero/data/remote/stream/AssistantTurnStreamClient.kt:39` | 后续独立 endpoint abstraction |
| 16 | Nginx | 不适用 | `proxy_buffering off`、cache off、HTTP/1.1 已有；缺 request buffering off、X-Accel、X-Request-Id；实际配置未知 | BLOCKER | CRITICAL | `server/dayzero-ai-gateway/nginx.example.conf:50` | 完成生产模板并读回 `nginx -T` |
| 17 | 日志 | Edge 仅记录长度/timing | Gateway记录原始 JWT `sub`；认证库错误详情进入日志 | BLOCKER | HIGH | `server/dayzero-ai-gateway/src/auth.ts:75`，`server/dayzero-ai-gateway/src/auth.ts:78` | 用户 ID 不可逆摘要；只记录固定错误枚举 |
| 18 | Secret/APK | 旧链路含公开 publishable key | Debug BuildConfig 会嵌入固定账号密码；当前 local.properties 已配置该名称 | BLOCKER | CRITICAL | `core/network/build.gradle.kts:45`，`core/network/src/main/java/com/goings/dayzero/data/remote/SupabaseConfig.kt:8` | 禁止凭据进入任何 APK；改用安全测试身份注入 |
| 19 | Docker | 不适用 | restart/healthcheck/internal expose 良好；无 image digest/rollback/version label/readiness | BLOCKER | HIGH | `server/dayzero-ai-gateway/docker-compose.yml:10`，`server/dayzero-ai-gateway/docker-compose.yml:19` | 固定 ACR digest、OCI revision、回滚镜像 |
| 20 | Android切流 | 当前 URL仍为 Supabase `/functions/v1/…` | Gateway 路由尚未接入 | UNVERIFIED | INFO | `core/network/src/main/java/com/goings/dayzero/data/remote/api/AiDraftApiService.kt:21`，`core/network/src/main/java/com/goings/dayzero/data/remote/stream/AssistantTurnStreamClient.kt:39` | 后续阶段实施，不属于本轮修复 |

## 5. 阻塞项

### CRITICAL

1. **无法证明本地 Gateway 等于阿里云现网**

   - 根因：目录未跟踪、无 ECS 只读访问、无镜像 digest/commit label。
   - 后果：无法回答现网路由、Prompt、JWT、SSE buffering、Secret、模型是否正确。
   - 证据：`git status --short -- server/dayzero-ai-gateway` 为整个目录 `??`；本地 Docker 无容器；无 SSH config。
   - 修改模块：部署 manifest、Docker image labels、ACR tag/digest、运维只读权限。
   - 测试：镜像内源码 hash 与审计 bundle hash比对；`docker inspect` 脱敏核验。

2. **路由与 readiness 不符合目标契约**

   - 根因：只实现 `/health`、`/assistant-turn-v2`、`/assistant-turn-v2-stream`。
   - 后果：目标 `/api/ai/...` 请求为 404；负载均衡无法判断是否可接 AI 流量。
   - 证据：`server/dayzero-ai-gateway/src/main.ts:29`。
   - 修改模块：`src/main.ts`、health handler、路由测试、Nginx。
   - 测试：四个目标路径、错误方法、ready 上游依赖状态。

3. **Streaming timeout 基线不确定且静态证据冲突**

   - 根因：远端 v17 文档声称动态 header timeout；当前 Edge/Gateway 源码改成 15/35/20 秒的完整候选竞速预算，动态 helper 不控制 fetch。
   - 后果：Fallback 触发时机、并发 Kimi 费用、SSE 行为与远端基线可能改变。
   - 证据：`docs/PROJECT_CONTEXT_FOR_CHATGPT.md:45`、`supabase/functions/assistant-turn-v2-stream/handler.ts:126`、`server/dayzero-ai-gateway/src/handlers/assistant_turn_v2_stream.ts:130`。
   - 修改模块：必须先读回远端 v17，不得先改代码。
   - 测试：header delayed/body delayed、15/25/35/50 秒边界、并发请求计数。

4. **生效 Nginx 未验证**

   - 根因：仅有 example；缺少完整 SSE 指令。
   - 后果：现网仍可能缓冲 SSE，导致“最后一次性弹出”。
   - 证据：`server/dayzero-ai-gateway/nginx.example.conf:44`。
   - 修改模块：生产 Nginx 配置。
   - 测试：按时间读取多个 delta，证明首 delta 在 final 前到达；`nginx -T` 静态断言。

5. **固定开发账号密码进入 Debug APK**

   - 根因：从 local.properties/环境读取后写入 debug BuildConfig。
   - 后果：APK 反编译可取得账号凭据。
   - 证据：`core/network/build.gradle.kts:47`。
   - 修改模块：debug 身份注入/测试后端；不得继续使用 BuildConfig secret。
   - 测试：APK strings/dex 扫描不得出现凭据摘要。

### HIGH

1. `X-Request-Id` 入站值被忽略，Nginx 也未转发。
2. JWT 未显式要求 `exp`/`kid`，生产可通过 `ENABLE_AUTH=false` 关闭。
3. JWT `sub` 原值及库错误详情进入日志。
4. 客户端断开没有取消 Gateway 上游 Kimi 请求。
5. Docker 镜像没有固定 tag/digest、revision label 或明确回滚镜像。
6. 请求 schema 过宽：缺 date、非法 turnType、缺 interactionResult 等可进入 Prompt；JSON primitive/null 可能成为 500。
7. Kimi URL、model、max tokens、temperature 的生产值未验证。
8. “纯卡片且空 reply 合法”与真实 Edge、Gateway、Android mapper 均不一致。若该要求仍有效，必须作为独立协议升级处理。
9. card ID 在服务器缺失时由 `Math.random()` 生成；两个独立请求不能保证相同 ID。

### MEDIUM

1. Edge `deno lint` 当前失败：`startedAt` 未使用。
2. Edge `deno fmt --check` 当前有 4 个文件失败。
3. CORS 默认 `*`，生产来源策略未验证。
4. `recentMessages` 实际只保留最后 6 条、每条最多 160 字符；不是“完整 history”。
5. Nginx example 缺少 `proxy_request_buffering off`、明确 gzip/SSE 规则和 `X-Request-Id`。
6. Gateway 测试没有覆盖 `/ready`、路由前缀、请求体 primitive、六图、总大小、重复 mediaId、取消及 Kimi 429/5xx。

### LOW

1. streaming/fallback Prompt 唯一字节差异是同义措辞。
2. README 声称“三个接口”，但目标要求四个。
3. Gateway README 的本地 `docker run -p 8080:8080` 示例易造成误操作；生产说明虽要求内网，但应改为 loopback 示例。

## 6. 已确认等价项

以下仅指**当前本地 Edge 源码与本地 Gateway 候选源码**，不代表阿里云现网：

- Android 请求 JSON 字段名与 Gateway parser 兼容。
- text-only Kimi user content 为 string。
- Vision Kimi user content 为 array，没有被 `JSON.stringify` 成普通字符串。
- image_url 的顺序与 attachments 顺序一致。
- JPEG、Base64、1–6 张、640 KiB/张、4 MiB 总量规则一致。
- interaction_result 携带附件时拒绝。
- Vision 附加说明、attachment alias 和真实 mediaId 映射规则等价。
- normalizeActions、营养 null 规则、weightKg 优先级、continuationContext、sourceMediaIds 白名单逻辑等价。
- DateMismatchGuard 是客户端本地卡，Gateway 不生成它。
- SSE 正常事件顺序、final 单次、actions 只在 final、debug_timing 在 final 后等价。
- Fallback 返回 `{reply, actions, debugTiming}`。
- Fallback 上游总 timeout 均为 50 秒。
- Gateway 没有调用 Supabase Edge 的自动跨服务器 fallback。
- Compose 使用 `restart: unless-stopped`、内部 `expose: 8080`、healthcheck 和非 root 用户。
- Android JWT interceptor 在 401 时最多刷新并重试一次；定向 JVM 单测通过。

## 7. 有意差异

Supabase Edge 当前记录为 `verify_jwt=false`，阿里云 Gateway 执行真实 Supabase access token 本地验签属于迁移安全要求，应分类为 **INTENTIONAL_DIFFERENCE**，不是回归。

本地实现已验证：

- Bearer header 必需。
- 只允许 ES256。
- 校验 issuer、audience、签名、时间约束。
- 校验非空 subject。
- JWKS client 单例复用，cache max age 24 小时，cooldown 5 分钟。
- Android 401 最多刷新并重试一次。

仍需补齐：

- 明确要求 `exp`、`kid` 存在。
- 错签名、缺 sub、缺 exp、缺 kid 测试。
- 生产移除或硬禁止 `ENABLE_AUTH=false`。
- 日志使用 user ID 不可逆摘要。
- 只读确认项目实际签名算法与 audience。

## 8. Kimi Code 后续施工清单

### 1. Gateway 协议补齐

- 目标：实现四个正式路由、严格 DTO schema、request ID 契约。
- 修改文件：`src/main.ts`、`request_parser.ts`、`request_id.ts`、health handler。
- 禁止范围：Prompt、模型、normalization、Android、数据库。
- 测试：路由、方法、Content-Type、primitive JSON、未知字段、turnType。
- 完成条件：目标路径测试全部通过，旧协议别名是否保留由人工决定。
- 远端部署：否。
- 人工确认：需要确认公开路径及是否保留旧路径。

### 2. 远端 Supabase 基线回读

- 目标：取得 v17/v28 完整源码、metadata 和 bundle hash。
- 修改文件：无。
- 禁止范围：deploy、rollback、Secrets、数据库。
- 测试：与本地 Edge、Gateway 做字节/语义 diff。
- 完成条件：确定 candidate race 与动态 header timeout 哪一个才是生产基线。
- 远端部署：否。
- 人工确认：需要只读 Supabase 权限。

### 3. JWT/JWKS 加固

- 目标：生产不可关闭验签，补齐 required claims 和脱敏日志。
- 修改文件：`auth.ts`、`config.ts`、logger、测试。
- 禁止范围：Supabase Auth 配置、用户数据。
- 测试：无 token、过期、错签名、错 iss/aud、缺 sub/exp/kid、JWKS 轮换/失败/并发。
- 完成条件：所有无效 token 稳定 401/403，日志不含 sub 原值。
- 远端部署：后续需要。
- 人工确认：确认项目签名算法与 audience。

### 4. SSE / Nginx

- 目标：防缓冲、正确取消、完整 request ID 转发。
- 修改文件：stream handler、Kimi stream client、Nginx production template。
- 禁止范围：Prompt、card、Room。
- 测试：逐 delta 时间验证、断流、取消、gzip、buffering、120s 边界。
- 完成条件：首 delta 在 final 前到达；客户端断开后上游立即 abort。
- 远端部署：需要。
- 人工确认：需审核 `nginx -T` 脱敏输出。

### 5. 服务端自动化测试

- 目标：把 Edge 的 Vision/normalization fixtures 参数化复用到 Gateway。
- 修改文件：Gateway tests。
- 禁止范围：为了通过而降低断言。
- 测试：见第 9 节。
- 完成条件：Gateway 不再只有 33 个窄覆盖测试，关键错误与时序全覆盖。
- 远端部署：否。
- 人工确认：否。

### 6. 镜像与部署可追溯性

- 目标：镜像 digest、revision label、SBOM/manifest、回滚镜像。
- 修改文件：Dockerfile、Compose、部署文档。
- 禁止范围：本阶段不得重启现网。
- 测试：镜像 inspect、非 root、health/readiness、源码 bundle hash。
- 完成条件：任一运行容器可以反查源码 commit 与镜像 digest。
- 远端部署：需要受控窗口。
- 人工确认：必须。

### 7. 公网烟雾测试

- 目标：只验证健康、认证、协议和 SSE 时序。
- 修改文件：无。
- 禁止范围：业务确认、数据库写入、真实用户聊天、连续 Kimi 压测。
- 测试：health、ready、无 JWT、测试 JWT text-only、单个安全 Vision 样本。
- 完成条件：路径、header、事件顺序、日志脱敏通过。
- 远端部署：部署后执行。
- 人工确认：必须。

### 8. Android endpoint abstraction

- 目标：只切换 AI endpoint，保留 Supabase Auth/DB/Sync。
- 修改文件：NetworkModule、Retrofit/SSE URL provider、DI。
- 禁止范围：UI、Room Schema、Sync、Prompt、DTO。
- 测试：Supabase/Gateway 两套 endpoint contract；同一 prepared request 复用。
- 完成条件：无双发、无跨服务自动 fallback、401 只重试一次。
- 远端部署：否。
- 人工确认：必须。

### 9. 真机切流

- 目标：小范围切到阿里云。
- 禁止范围：清数据、uninstall、connectedDebugAndroidTest。
- 测试：text、interaction、1/3/6 图、取消、重试、应用重启后本地消息一致。
- 完成条件：无第二用户消息、无第二 placeholder、Room/Sync 不变。
- 远端部署：已完成 Gateway 后执行。
- 人工确认：必须。

## 9. 推荐的自动化测试矩阵

| 场景 | Gateway断言 | Nginx/Android断言 |
| --- | --- | --- |
| text-only | string content、单 Kimi request、正常 SSE/fallback | delta 实时可见 |
| 直接确认卡 | show_confirm_card 字段完整 | 单 placeholder |
| 询问意图卡 | options/continuation 正确 | 点击后单次 interaction_result |
| 询问缺失信息卡 | mealType options 完整 | 不重复询问 |
| interaction_result | 无附件、continuation 权威 | 不再上传图片 |
| 单图/三图/六图 | 顺序、alias、尺寸、mediaId 映射 | prepared request 不重建 |
| 纯图片 | Android有效占位文本路径通过 | UI语义为纯图片 |
| 图片加文字 | text block 后跟有序 image blocks | 文字不丢失 |
| 非 JPEG | 稳定 400 / SSE error | 不 fallback 到其他服务器 |
| 非法 Base64 | 稳定错误且不回显内容 | 日志无 payload |
| 重复 mediaId | DUPLICATE_MEDIA_ID | 客户端明确失败 |
| 单图超限 | 413 | 无 Kimi 请求 |
| 总大小超限 | 413 | 无 Kimi 请求 |
| interaction_result 带附件 | 稳定拒绝 | mapper 默认省略附件 |
| Kimi 429 | 固定错误枚举、retryable 策略 | 不无限重试 |
| Kimi 500/502/503 | 明确映射 | 不跨服务 fallback |
| header timeout | 精确边界与 stage | 客户端行为符合冻结基线 |
| total timeout | fallback 504 | 无第二 placeholder |
| SSE 中断 | 单 error，无 final/done | 状态清理 |
| 客户端取消 | 所有上游 abort | Room 用户消息不丢 |
| 无 JWT | 401 | 最多刷新一次 |
| 过期 JWT | 401 | 刷新后同 Request 重放 |
| 错误签名/iss/aud | 401 | 无敏感错误 |
| 缺 exp/kid/sub | 401或已定义403 | 稳定错误 |
| Nginx buffering | delta 跨时间到达 | 禁止最终一次性弹出 |
| Gateway 重启恢复 | readiness 转换正确 | 无数据写入 |
| 日志泄露 | 扫描 token/Base64/prompt/user text | 结果为零 |
| request ID | 入站接受/生成/回显/转发 | 客户端 trace 可关联 |
| primitive/null JSON | 稳定 400 | 不出现500 |
| 空 reply + card | 按最终协议决定允许/拒绝 | 服务端和 Mapper 必须一致 |

## 10. 执行过的命令与真实结果

| 命令 | 退出码 | 结果摘要 |
| --- | ---: | --- |
| `git status --short` | 0 | 起始已有大量修改；后续以 hash 固定 |
| `git rev-parse HEAD` | 0 | `2bd958e…` |
| `git diff --stat` | 0 | 316 tracked 文件，`+894/-49,756` |
| `git diff --check` | 0 | 通过；LF/CRLF 警告 |
| `rg --files` / `rg -n` / `Get-Content` / `Get-FileHash` | 0 | 定位并审计 Edge、Gateway、Android、文档、部署文件 |
| Prompt 规范化 SHA-256 脚本 | 0 | fallback/Gateway 完全相同；stream 一处同义差异 |
| 生产 bundle SHA-256 脚本 | 0 | Edge `614f6893…`; Gateway `b836b4c1…` |
| `docker ps --format ...` | 0 | 无运行容器 |
| SSH 文件/Host 只读检查 | 0 | 有 key/known_hosts；无 SSH config/Host alias |
| `deno --version` | 0 | Deno 2.9.0 |
| Gateway `deno task check` | 0 | 通过 |
| Gateway `deno task lint` | 0 | 通过 |
| Gateway `deno task fmt` | 0 | 32 文件通过 |
| Gateway `deno task test` | 0 | 33 passed，0 failed |
| Edge `deno check` | 0 | 两个 entrypoint 通过 |
| Edge `deno lint` | 1 | 1 个 unused variable |
| Edge `deno fmt --check` | 1 | 4 个文件未格式化 |
| Edge `deno test` | 0 | 97 passed，0 failed |
| Gradle 定向测试首次运行 | 9009 | `JAVA_HOME` 未设置 |
| 设置进程级 `JAVA_HOME` 后重试 | 0 | BUILD SUCCESSFUL |
| `:core:network` 定向 JVM 测试 | 0 | 21 tests，0 failures |
| `:core:sync` 定向 JVM 测试 | 0 | 4 tests，0 failures |
| `:app` Vision orchestrator 定向测试 | 0 | 32 tests，0 failures |
| 最终 `git status --short -uall` 捕获 | 0 | 691 条；状态 hash 与起始完全相同 |
| 最终 `git diff --check` | 0 | 通过 |

未执行：

- SSH 远端命令：没有可用 Host/权限。
- `curl /health`、`/ready`：没有权威公网地址或本地运行容器。
- `docker inspect`、`nginx -T`、`journalctl`：无可访问目标。
- Supabase 远端函数 readback：本轮没有可用只读 connector。
- 任何真实 Kimi 请求。
- Android instrumentation、ADB 或真机操作。

## 11. 范围合规声明

- 修改源码、配置或文档：**否**。
- 测试生成/更新 ignored build 输出和本地工具缓存：**是，属于允许的现有测试副产物；Git 状态未变化**。
- 部署：**否**。
- 重启、reload、build/pull 容器：**否**。
- 修改 Android：**否**。
- 修改 Supabase Edge Function：**否**。
- 修改 Supabase 数据库/Auth/RLS/Storage/Secrets：**否**。
- 修改 Room/Schema/Migration：**否**。
- Git 写操作：**否**。
- 接触或输出 Secret：**未输出 Secret 值**；仅检查环境变量和 local.properties 的键名，值统一视为 `[REDACTED]`。
- 输出边界异常：一次只读搜索的终端结果命中了文档中的 Base64 公钥行，另一次编号读取意外展开了完整 System Prompt 源码。二者均不含 Secret、Token 或用户聊天，但违反了“不输出 Base64/完整 Prompt”的明确边界，应如实记录。
- 结束时工作区是否与开始一致：**是**。状态条目、分类、HEAD 和状态 SHA-256 完全一致。
