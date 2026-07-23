# SMS Forwarder v2 — 多用户方案设计（最终版）

> 目标：手机 APK 抓短信 → 上传到自建后端 → 后端按用户分发到该用户配置的渠道
> （**Telegram / 微信(PushPlus)**）。使用尽量简单，手机端只填最少信息，复杂度全在服务端。
> 转发即焚，不长期保存短信正文。

## 0. 名词
- **APK**：装在手机上的安卓客户端（现有 `ShuttleLab/SMSForwarder` 仓库）。
- **后端**：新建的 Go 服务，仿 `secretary`，单二进制 + SQLite，与 secretary 同机部署。
- **渠道(channel)**：一个用户配置的一个通知目的地（一个 TG、一个微信）。
- **管理员/运营方**：你本人，能登录服务器与 admin 控制台排查问题。

## 定了的决策（本轮敲定）
- 渠道 v1 = **Telegram + 微信(PushPlus)**；**不做 Email**（避免 SMTP 中继滥用/配额/黑名单风险）。
- **开放注册** + **每用户限流**（因无 Email，无共享中继资源可被滥用，开放注册即安全）。
- 手机端凭证 = 登录后**下发的设备 token**（哈希存储、可单设备吊销），非明文密码。
- 渠道配置 UI **在 APK 内**，每种渠道做专门的极简界面（不做数据驱动表单）。
- 投递日志（含失败）只给**管理员在 admin 控制台**看；**不做用户侧日志页 / APK 不拉取投递日志**。
- 日志保留**发件人 + 结果**便于排查；短信**正文投递完成或彻底放弃后焚毁**。
- 运营配置（TG bot token 等）+ 用户管理 + 投递日志查看 = **admin Web 控制台**，复用 secretary。

---

## 1. 总体架构

```
┌─────────────┐   ①短信广播      ┌──────────────────────────────────────────┐
│  手机系统    │ ───────────────► │  APK (瘦客户端)                            │
└─────────────┘                  │  · SmsReceiver 抓短信 + 周期对账补漏        │
                                 │  · WorkManager 本地队列，带鉴权上传+近乎无限重试 │
                                 │  · 前台服务保活 / 电池/自启动引导            │
                                 │  · 登录注册 + 渠道管理界面(TG/微信)          │
                                 └───────────────┬──────────────────────────┘
                                   ②POST /api/sms │ Authorization: Bearer <device_token>
                                                 ▼
   固定域名 https://smsforwarder-api.shuttlelab.org  (Cloudflare DNS/TLS → 回源 VPS)
                                                 │
                                 ┌───────────────▼──────────────────────────┐
                                 │  后端 Go 服务（单二进制，仿 secretary）      │
                                 │  · HTTP API：注册/登录/渠道CRUD/测试/收短信  │
                                 │  · 鉴权：设备 token（登录下发，可吊销）       │
                                 │  · 转发引擎：按 user_id 查 enabled 渠道，     │
                                 │    逐渠道发送，失败退避重试，成功/放弃即焚正文 │
                                 │  · Telegram Bot（内嵌，长轮询）：处理 /bind  │
                                 │  · admin Web 控制台：bot token/用户/投递日志  │
                                 │  · SQLite(WAL)：users/devices/channels/...   │
                                 └────────────────┬───────────────┬───────────┘
                                              TG API          PushPlus API
                                                 ▼               ▼
                                            用户的 TG        用户的微信
```

**关键原则**
1. **按 `user_id` 物理隔离**：每条渠道、每次转发都绑定 user_id，绝不串号（同 secretary）。
2. **转发即焚**：短信正文只在"未投递完成"期间短暂驻留于 outbox，投递完/放弃即删；日志只留元数据。
3. **手机端字段极简**：微信只填一个 token；TG 点一下绑定，什么都不填。
4. **可靠性搬家不消失**：手机端本地队列+近乎无限重试扛后端短暂宕机；服务端逐渠道独立重试。
5. **无 Email = 无开放中继**：开放注册下无人能借你的服务器向陌生人发垃圾。

---

## 2. 后端（Go，仿 secretary）

### 2.1 技术栈（复用 secretary 选型）
- Go 1.25，`net/http`
- `modernc.org/sqlite`（纯 Go，免 CGO，WAL）
- `github.com/go-telegram/bot`（共享 TG Bot，长轮询）
- 微信：向 PushPlus HTTP 接口 POST（无需第三方库）
- `golang.org/x/crypto/bcrypt`（密码哈希）
- 复用 secretary 模式：`config`(env 引导) / `store`(按 user_id 作用域) / `im`(渠道适配器接口) / `ratelimit`(每用户限流) / `admin`(运营 Web 控制台)

### 2.2 数据库表

```sql
users(
  id INTEGER PK, username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',            -- active|banned
  created_at INTEGER NOT NULL
)

devices(                                            -- 设备长期凭证，可单独吊销
  id INTEGER PK, user_id INTEGER NOT NULL REFERENCES users(id),
  token_hash TEXT NOT NULL,                         -- 明文仅登录时返回一次
  label TEXT, created_at INTEGER NOT NULL, last_seen_at INTEGER,
  revoked INTEGER NOT NULL DEFAULT 0
)

channels(
  id INTEGER PK, user_id INTEGER NOT NULL REFERENCES users(id),
  type TEXT NOT NULL,                               -- telegram|wechat
  enabled INTEGER NOT NULL DEFAULT 1,
  config TEXT NOT NULL,                             -- JSON，随 type 不同
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
)
--  config：
--   telegram : {"chat_id": 987654}   绑定后写入；未绑定时空 → 该渠道视为未就绪
--   wechat   : {"token": "<PushPlus token>"}

tg_bind_codes(                                      -- Telegram 一次性绑定码
  code TEXT PK, user_id INTEGER NOT NULL, channel_id INTEGER NOT NULL,
  expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0
)

outbox(                                             -- 待投递；投完/放弃即删（正文随之焚毁）
  id INTEGER PK, user_id INTEGER NOT NULL, channel_id INTEGER NOT NULL,
  sender TEXT, content TEXT,                        -- content 仅驻留至投递完成
  client_msg_id TEXT,                               -- 幂等去重键
  attempts INTEGER NOT NULL DEFAULT 0,
  next_at INTEGER NOT NULL, created_at INTEGER NOT NULL
)

delivery_logs(                                      -- 只留元数据，管理员在控制台看
  id INTEGER PK, user_id INTEGER NOT NULL, channel_type TEXT NOT NULL,
  sender TEXT,                                      -- 保留发件人便于排查
  status TEXT NOT NULL,                             -- ok|fail
  detail TEXT, created_at INTEGER NOT NULL
)
```

运营方一次性配置（放 admin 控制台 / `.env` 引导）：
- 共享 TG Bot：`bot_token`、`bot_username`（拼深链用）
- 每用户限流阈值等全局设置
- （PushPlus 无需运营方配置：每用户填自己的 token，服务端只代发）

### 2.3 鉴权模型
- **注册** `POST /api/register {username, password}` → 建 user（bcrypt）。**开放注册**。
- **登录** `POST /api/login {username, password, device_label?}` → 校验 → 生成随机 device_token
  → 存 `devices.token_hash` → **明文 token 仅此一次返回**，APK 存入加密存储。
- 之后请求头 `Authorization: Bearer <device_token>`；服务端哈希比对、检查未吊销、更新 last_seen。
- **吊销**：admin 控制台删设备 / `DELETE /api/devices/{id}` → 该 token 立即失效，其它设备不受影响。
- 401 → APK 提示重新登录。
- **限流**：每用户维度（复用 secretary `ratelimit`），防个别账号刷爆。

### 2.4 API 清单（除注册/登录外都需 Bearer）

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | `/api/register` | 注册（开放） |
| POST | `/api/login` | 登录 → `{device_token}` |
| POST | `/api/logout` | 吊销当前设备 token |
| GET  | `/api/channels` | 列出当前用户所有渠道 |
| POST | `/api/channels` | 新增渠道 `{type, config, enabled}` |
| PATCH| `/api/channels/{id}` | 编辑 `{config?, enabled?}` |
| DELETE | `/api/channels/{id}` | 删除渠道 |
| GET  | `/api/channels/{id}` | 查单个渠道（APK 轮询看 TG 是否已绑定） |
| POST | `/api/channels/{id}/test` | 立即发一条测试消息，返回成功/失败 |
| POST | `/api/channels/telegram/bind-code` | 生成 TG 绑定码，返回 `{code, deeplink}` |
| POST | `/api/sms` | **上传短信** `{sender, content, timestamp, client_msg_id}` → 入 outbox → 立即 `200`(ACK) |

- **`/api/sms` 幂等**：同一 `client_msg_id` 只入库一次，防重试/对账重复转发。
- **ACK 语义**：短信**持久化进 outbox** 即回 200；APK 收到 200 就不再重试（投递由后端负责）。
- **无用户侧日志 API**：投递日志只在 admin 控制台看。

### 2.5 转发引擎
1. `/api/sms` → 按 user_id 查该用户 **enabled 且就绪** 的渠道 → 每渠道在 `outbox` 落一行。
2. 后台循环（仿 secretary scheduler 的事务认领，防并发重复发）扫 `outbox` 中 `next_at<=now`：
   - 成功 → 删 outbox 行（**正文即焚**）+ 写 `delivery_logs(ok, sender)`；
   - 失败 → `attempts++`、指数退避设 `next_at`；超上限（如 8 次）→ 删行（**放弃并焚毁**）+ 写 `delivery_logs(fail, sender, 原因)`。
3. **渠道相互独立**：一个渠道失败/重试不影响其他渠道。

**两个 sender**
- `telegramSender`：共享 bot token 调 `sendMessage`，`chat_id` 取自渠道 config。
- `wechatSender`：POST PushPlus `/send`，带用户 config.token + 标题/内容。

### 2.6 Telegram 绑定（内嵌 bot，长轮询）
1. APK 点「绑定」→ `POST /api/channels/telegram/bind-code` → 建 `tg_bind_codes`，返回 `deeplink=https://t.me/<bot>?start=<code>`。
2. APK 打开深链 → 用户点 Start → Telegram 发 `/start <code>` 给 bot。
3. 内嵌 bot（同进程）收到 → 查码命中 user_id/channel_id、未过期未用 → 把 `chat_id` 写进该 channel 的 config、标记码已用 → 回一条「绑定成功」。
4. APK 轮询 `GET /api/channels/{id}` 见 `config.chat_id` 已填 → 显示「已绑定」。

### 2.7 Admin Web 控制台（运营方，复用 secretary）
- 会话登录（bootstrap 账号来自 env，同 secretary）。
- **投递日志查看**：按用户/状态/时间筛选 `delivery_logs`，定位失败原因（这是 B 的落点：只给管理员看）。
- **用户管理**：封禁/删除用户、吊销设备。
- **全局设置**：TG bot token/username、限流阈值等（DB-first，env 兜底）。

---

## 3. Android APK 改造

### 3.1 从现状到目标
- **移除**：主/备接口 URL、自定义 headers 配置（用户不再填接口地址）。
- **硬编码**后端地址 `https://smsforwarder-api.shuttlelab.org`（保留隐藏调试覆盖，方便对本地/测试服）。
- **新增**：登录/注册界面、渠道管理界面（TG / 微信）。
- **保留强化**：短信捕获（广播 + 周期对账）、WorkManager 上传队列、前台服务保活、电池/自启动引导。

### 3.2 界面
1. **登录/注册页**：username + password。登录成功 → device_token 存 `EncryptedSharedPreferences`。
2. **主页**：登录用户名 + 服务开关；**本地「上传状态」**（仅显示"短信是否已成功上传到后端"，用于在家设置时确认工作正常——**不显示各渠道投递结果**，那是管理员的事）；权限/电池/自启动引导（沿用现有）。
3. **渠道管理页**：列表（类型 + 启用开关），`+` 新增，编辑，删除，每个「发送测试」。
   - **Telegram**：点「绑定 Telegram」→ 打开深链 → 轮询显示「已绑定」。无需填 token。
   - **微信**：填 **PushPlus token**，附一行引导（去 PushPlus 扫码关注公众号拿 token）。

### 3.3 上传流程
- `SmsReceiver`/`ReconcileWorker` 抓到短信 → 入队 `UploadWorker`。
- `UploadWorker`：`POST /api/sms` 带 Bearer token + `client_msg_id`。
  - 2xx → 成功；401 → 停止并通知「请重新登录」；网络/5xx → 指数退避，**上限近乎无限、封顶 30min**（唯一命脉，不能因重试耗尽丢消息）。

### 3.4 本地存储
- `device_token` 存加密存储；**渠道配置不再本地存**（服务端为准，APK 只展示/编辑）。

---

## 4. 可靠性与安全
- **抗后端宕机**：手机端本地队列 + 近乎无限重试，后端恢复后自动补发。
- **转发即焚**：正文仅驻留至投递完成/放弃；日志只留元数据（含发件人）。
- **传输与鉴权**：全程 TLS；设备 token（哈希、可吊销）；密码 bcrypt。
- **开放注册安全性**：无 Email = 无开放中继；配 per-user 限流防刷。
- **幂等**：`client_msg_id` 去重，杜绝重复转发。
- **单点**：后端单实例，个人/小规模可接受；未来可加多实例/备份。

---

## 5. 仓库与部署
- **APK**：继续 `ShuttleLab/SMSForwarder`，GitHub Actions 出包。
- **后端**：新建 Go 仓库（与 secretary 同级），单二进制 + SQLite，与 secretary 同机部署；Cloudflare 解析 `smsforwarder-api.shuttlelab.org` → 回源 VPS。

---

## 6. 分期实施
- **P1 后端骨架**：注册/登录/设备 token + 渠道 CRUD + `/api/sms` + outbox 转发引擎 + **微信(PushPlus) sender**（纯 HTTP，最易端到端验证）。
- **P2 Telegram**：内嵌共享 bot（长轮询）+ `/bind` 绑定流程 + telegramSender。
- **P3 APK 改造**：登录/注册 + 渠道管理（TG 绑定 / 微信 token）+ `UploadWorker` 替换现有主/备逻辑。
- **P4 控制台与联调**：admin 控制台（投递日志/用户/bot token）+ 真机（HarmonyOS 3.0）端到端测试 + 保活验证。
