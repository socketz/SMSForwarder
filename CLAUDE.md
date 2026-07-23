# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Android app (Kotlin, View-based UI) that **reads incoming SMS and forwards
them to an HTTP webhook and a Telegram bot**. For personal use, not published to any store.
The design goal is a phone left plugged in at home that reliably forwards SMS to the owner
while they are away.

Comments and user-facing strings are bilingual (English + 中文); code comments are mostly
Chinese. Match that convention when editing. Build scaffolding and conventions are modeled
on the sibling NetPulse project.

## Build & run

Built entirely via GitHub Actions — there is **no local Android SDK/JDK expected**.

```bash
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # release APK (falls back to debug signing if no keystore env)
```

Requires JDK 17. No unit/instrumented tests.

CI: `.github/workflows/build.yml` builds a debug APK on push to main/master (artifact
`SMSForwarder-debug`). `.github/workflows/release.yml` triggers on a `v*` tag, decodes a
base64 keystore from secrets, builds a **signed** release APK, and publishes it to a GitHub
Release. Signing is driven by env vars (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`) in `app/build.gradle`; when `KEYSTORE_FILE` is unset, release builds fall
back to debug signing so `assembleRelease` never fails.

## Architecture

Everything lives in `app/src/main/java/com/smsforwarder/`. No DI, no database, no Room —
**`SharedPreferences("smsforwarder")` (see `Prefs`) is the single source of truth** for
config, the dedup cursor, and the forward log. The persistent forward queue is
**WorkManager**, deliberately chosen over Room to avoid annotation processors that raise
CI-build risk (the project can only be built on CI).

The three concerns are intentionally decoupled:

- **Capture** — `SmsReceiver`, a manifest-registered `SMS_RECEIVED` receiver (permission
  `BROADCAST_SMS`, priority 999). The system delivers this broadcast **even when the app
  process is dead** (it relaunches the app), so capture does not depend on the service being
  alive. The receiver only does fast work — reassemble multipart PDUs per sender, dedup, and
  enqueue — never network I/O (broadcasts have a ~10s limit).
- **Forward** — `WorkScheduler` enqueues **one `ForwardWorker` per (message × channel)** as
  unique work, so each channel retries independently. `ForwardWorker` (`CoroutineWorker`)
  sends via a `Sender` and returns `Result.retry()` with exponential backoff up to
  `MAX_ATTEMPTS` (5), then `Result.failure()` + a log entry. WorkManager persists the queue
  and resumes it across process death and reboot.
- **Survival** — `ForwardService`, a `START_STICKY` foreground service (type `specialUse`
  with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification). It does **not** capture or forward;
  it keeps the process warm, shows the persistent notification, and ensures the periodic
  reconcile work is scheduled. `BootReceiver` restarts it on boot / app-update **only if**
  prefs `running` was true.

Supporting pieces:

- **`ReconcileWorker`** — optional periodic backstop (min 15 min). Scans `content://sms/inbox`
  for messages newer than the `last_seen_ts` cursor and enqueues any the broadcast missed.
  **First run only initializes the cursor to "now"** (no historical backfill — avoids
  forwarding pre-install messages). No-op without `READ_SMS`.
- **`Prefs`** — config accessor + the cross-path **dedup** set. Both capture and reconcile
  compute the same key `sender|bodyHash|minuteBucket`; `seenContains` tolerates ±1 minute
  bucket drift (broadcast uses receipt time, reconcile uses the provider `date`, which differ
  by seconds). The set is bounded to 500 entries.
- **`Senders`** — `Sender` interface + `HttpSender` (POST JSON, optional custom headers) and
  `TelegramSender` (`sendMessage`, optional custom API base for reverse-proxy since
  `api.telegram.org` is blocked in mainland China). Raw `HttpURLConnection`, no networking lib.
  `senderFor(channel)` maps a channel id to a sender. Must run off the main thread.
- **`ForwardLog`** — the forward log as a JSON-array string in prefs, newest-first, trimmed to
  a configurable keep count (20–2000). Mirrors NetPulse's `LogStore`.
- **`MainActivity`** — status, start/stop, runtime permission requests, battery-optimization
  exemption, and an OEM auto-start-whitelist guide (tries known Xiaomi/Huawei/OPPO/vivo
  intents, falls back to app-details). Renders the recent forward log by inflating `item_log`
  rows into a `LinearLayout` (no RecyclerView adapter).
- **`SettingsActivity`** — edits channel config + reconcile interval + log-keep; "Send test"
  saves then sends a test message through a channel on a worker thread.
- **`WindowInsetsExt`** — `applyEdgeToEdgeInsets(root)`; call after `setContentView` in every
  activity (targetSdk 35+ forces edge-to-edge). Copied from NetPulse.

### Key prefs keys (the de-facto schema)
`running`, `http_enabled`, `http_url`, `http_headers`, `tg_enabled`, `tg_token`,
`tg_chat_id`, `tg_api_base`, `reconcile_enabled`, `reconcile_min`, `last_seen_ts`,
`forward_log`, `log_keep`, `seen_keys`.

## Conventions

- `minSdk 26`, `compileSdk 36`, `targetSdk 36`. Guard newer APIs with `Build.VERSION.SDK_INT`
  (already done for FGS types and runtime permissions). Because minSdk is 26, the adaptive
  launcher icon in `mipmap-anydpi-v26` always applies — no legacy PNG mipmaps needed.
- View binding is enabled in Gradle but the code uses `findViewById` throughout — stay consistent.
- No networking library; raw `HttpURLConnection` with explicit timeouts and `disconnect()`.
  Background work uses `CoroutineWorker` / coroutines, or a bare `Thread` (settings test send).
- Never do network I/O in `SmsReceiver` — enqueue to WorkManager instead.
