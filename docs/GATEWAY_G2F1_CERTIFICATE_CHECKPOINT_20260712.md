# DayZero AI Gateway G2-F1 证书续签/重签检查点报告

> 检查时间：2026-07-12 UTC
> 检查人：Kimi Code CLI
> 目标：从 `BLOCKED_CERTIFICATE_ISSUANCE` 阶段继续，重新验证公网 HTTP/ACME 可达性并签发生产证书
> 目标域名：`api.dayzero.cn`
> ECS 公网 IP：`39.106.156.166`
> 注册邮箱：`1184783870@qq.com`

---

## 1. 当前生产状态

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| ECS SSH 可达 | ✅ | `root@39.106.156.166` 登录正常 |
| Gateway 容器 | ✅ healthy | `dayzero-ai-gateway` 运行中，8080 不暴露宿主机 |
| Nginx 容器 | ✅ healthy | `dayzero-ai-nginx` 运行中，80/443 映射到宿主机 |
| 现有证书有效期 | ✅ | Let's Encrypt YR2，有效期至 **2026-10-10** |
| 服务器本地 HTTPS | ✅ | `https://localhost/health` 与 `/ready` 均返回 200 |
| 公网 HTTPS（经真实 IP + SNI） | ✅ | `openssl s_client -connect 39.106.156.166:443 -servername api.dayzero.cn` 验证通过，返回码 0 |

---

## 2. 外部 HTTP / ACME 验证

### 2.1 `api.dayzero.cn:80` 外部可达性

- **结论**：端口 80 可建立 TCP 连接，但所有 HTTP 请求均被阿里云 `Beaver` 网关以 **403 Forbidden** 拦截。
- **响应头**：`Server: Beaver`
- **响应体**：ICP 未备案拦截页面，标题 `Non-compliance ICP Filing`，iframe 指向 `http://www.aliyun.com/beian/beian-block?id=00000000005515609117`。

### 2.2 ACME challenge 公网可达性

测试请求：

```bash
curl -sI http://api.dayzero.cn/.well-known/acme-challenge/nonexistent-test
```

结果：

```text
HTTP/1.1 403 Forbidden
Server: Beaver
Cache-Control: no-cache
Content-Type: text/html
Content-Length: 640
Connection: close
```

同样返回 ICP 未备案拦截页面。

- **结论**：`/.well-known/acme-challenge/` 路径无法通过公网访问，ACME HTTP-01 验证必然失败。

### 2.3 直接访问 ECS IP 对比

```bash
# 带 Host 头访问 ECS IP:80
curl -sI -H 'Host: api.dayzero.cn' http://39.106.156.166/.well-known/acme-challenge/test
# 结果：403 Forbidden, Server: Beaver

# 不带 Host 头访问 ECS IP:80
curl -sI http://39.106.156.166/.well-known/acme-challenge/test
# 结果：404 Not Found, Server: nginx（符合预期，文件不存在）
```

说明 Nginx 容器本身对 ACME challenge 路径配置正确；**403 来自阿里云 Beaver/ICP 层，而非 Nginx**。

---

## 3. 阻塞根因

阿里云 `Beaver` 对 `api.dayzero.cn` 的 HTTP（80）流量执行 **ICP 备案拦截**，导致：

1. 公网所有 HTTP 请求返回 403 ICP block 页面。
2. Let's Encrypt HTTP-01 challenge 无法被验证服务器读取。
3. 重新执行 `certbot` HTTP-01 签发文娱证书会失败，并可能消耗 Let's Encrypt 请求配额。

> ICP 备案属于行政/法律流程，无法通过服务器端配置或代码部署绕过。

---

## 4. 决策：停止并维持现状

根据原部署计划的停止条件：

> “任何关键检查失败立即停止并保持 v1。”

由于 **ACME challenge 公网可达性检查失败**，本次未执行 `certbot` 重新签发，避免：

- 浪费 Let's Encrypt 失败尝试配额；
- 覆盖或破坏当前仍有效的生产证书；
- 触发不必要的网关/Nginx 重启风险。

当前生产证书仍有效，HTTPS 服务在真实 IP 上验证通过，因此 **保持 v1 部署状态不变**。

---

## 5. 可行后续选项

| 方案 | 可行性 | 说明 |
| --- | --- | --- |
| 完成 ICP 备案 | ✅ 根本解决 | 备案通过后阿里云会解除 HTTP 403，HTTP-01 可正常进行 |
| 改用 DNS-01 挑战 | ⚠️ 需额外配置 | 需要阿里云 DNS / 其他 DNS 提供商的 API Token，并安装 certbot DNS 插件；本轮未纳入计划 |
| 等待证书临近过期再处理 | ⚠️ 被动 | 当前证书 2026-10-10 到期，需在到期前解决 |
| 切换至其他 CDN / 入口 | ⚠️ 架构变更 | 超出本轮范围 |

---

## 6. 范围声明

| 项目 | 是否执行 |
| --- | --- |
| 修改 Android | 否 |
| 修改 Supabase Edge Functions | 否 |
| 修改 Supabase Auth / 数据库 / RLS / Storage | 否 |
| 修改 Room | 否 |
| 重新签发证书 | 否（因关键前置检查失败） |
| 覆盖/删除现有证书 | 否 |
| 重启 Gateway / Nginx | 否 |
| 输出 Secret（Kimi key、JWT、Token、.env 内容等） | 否 |

---

## 7. 结论

**状态**：`BLOCKED_CERTIFICATE_ISSUANCE_ICP_HTTP_BLOCKED`

- 生产证书当前有效，HTTPS 服务正常。
- 公网 HTTP 80 被阿里云 ICP 备案拦截，ACME HTTP-01 无法完成。
- 已按停止条件保持 v1，未执行 certbot 重新签发。
- 建议优先完成 ICP 备案，或切换至 DNS-01 挑战后再重新签发。
