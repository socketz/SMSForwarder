<div align="center">
  <h1>SMS Forwarder</h1>
  <p><strong>把收到的短信实时转发到 HTTP 接口和 Telegram 机器人的 Android App</strong></p>
  <p>Reads incoming SMS and forwards them to your HTTP webhook and Telegram bot — in real time, reliably, long-running in the background.</p>
</div>

## 这是什么

一个**自用**的短信转发工具（不上架应用商店）：手机放家里插电常开，收到的短信实时推送到你在外面能收到的渠道。

- **实时捕获**：短信广播接收器。即使 App 进程被系统杀掉，来短信时系统也会把它拉起来送广播——所以"接住短信"这一环比服务存活更稳。
- **兜底对账**：可选的周期性任务，扫描短信收件箱，补发被实时广播漏掉的短信（需读取短信权限）。
- **转发与捕获解耦**：每条短信入队到 WorkManager，独立发往每个启用渠道，失败按**指数退避重试**，并且**能跨进程被杀与重启自动恢复**，不丢消息。
- **多渠道**：HTTP webhook + Telegram Bot，每条短信同时发往所有已启用渠道，各渠道独立重试。
- **长期存活**：常驻前台服务 + 开机自启 + 电池优化豁免 + 厂商自启动白名单引导。

## 转发格式

**HTTP webhook** — 向你配置的 URL `POST` 一段 JSON（可自定义请求头）：

```json
{
  "sender": "10086",
  "content": "您的验证码是 123456",
  "timestamp": 1690000000000,
  "receivedAt": "2026-07-23T12:00:00+0800"
}
```

**Telegram** — 通过 `sendMessage` 发送。在国内直连 `api.telegram.org` 会被墙，设置里可填**自定义 API 地址**走反向代理。

## 用法

1. 安装 APK（见下），首次打开授予**短信 + 通知**权限。
2. 进"设置"配置 HTTP / Telegram 渠道，用"发送测试"验证配置。
3. 回主界面点"启动服务"。
4. **重要**：点"忽略电池优化"和"自启动 / 后台白名单"，按引导把本 App 加白——这是国产 ROM 上能否长期存活的关键。

## 构建（靠 GitHub Actions，无需本地环境）

本项目参照 NetPulse，完全用 GitHub Actions 打包：

- **Debug APK**：推送到 `main`/`master` 分支后，在 Actions → "Build APK" 的 artifact 里下载 `SMSForwarder-debug`。
- **签名 Release APK**：打 `v*` tag 触发，构建**签名** APK 并发布到 GitHub Releases。

```bash
git tag v1.0.0
git push origin v1.0.0
```

### Release 签名密钥（一次性设置）

在 GitHub 仓库 Settings → Secrets and variables → Actions 添加：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 base64（`base64 -i release.jks`） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

生成 keystore：

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias smsforwarder
```

无密钥时，本地 / CI 的 release 构建会自动回退到 debug 签名，不会报错。

## 技术栈

- Kotlin · minSdk 26 / targetSdk 36 · Material Components（View 体系）
- Kotlin Coroutines · WorkManager（持久化转发队列 + 重试）
- CI/CD：GitHub Actions

供个人使用。
