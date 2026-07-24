package com.smsforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 设置：权限与保活（权限 / 电池优化 / 自启动白名单）+ 兜底对账 + 日志保留 +
 * 后端地址覆盖（调试）+ 关于。这些都从主界面挪进来，让主页保持三步引导的干净。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tvPerm: TextView
    private lateinit var btnGrantPerm: Button
    private lateinit var tvBattery: TextView

    private lateinit var swKeepalive: SwitchMaterial
    private lateinit var swReconcile: SwitchMaterial
    private lateinit var etReconcileMin: EditText
    private lateinit var etLogKeep: EditText
    private lateinit var etBaseUrl: EditText

    private val requiredPerms: Array<String>
        get() = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        tvPerm = findViewById(R.id.tvPerm)
        btnGrantPerm = findViewById(R.id.btnGrantPerm)
        tvBattery = findViewById(R.id.tvBattery)
        swKeepalive = findViewById(R.id.swKeepalive)
        swReconcile = findViewById(R.id.swReconcile)
        etReconcileMin = findViewById(R.id.etReconcileMin)
        etLogKeep = findViewById(R.id.etLogKeep)
        etBaseUrl = findViewById(R.id.etBaseUrl)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(); finish() }
        btnGrantPerm.setOnClickListener { requestPermissions(requiredPerms, 100) }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { showAutostartGuide() }
        findViewById<Button>(R.id.btnDiagSms).setOnClickListener { diagnoseSms() }
        findViewById<Button>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<Button>(R.id.btnLanguage).setOnClickListener { showLanguageDialog() }

        load()
    }

    /** 语言切换：跟随系统 / 中文 / English，用 AppCompat 的 per-app locale */
    private fun showLanguageDialog() {
        val tags = listOf("", "zh", "en")
        val labels = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_zh),
            getString(R.string.lang_en),
        )
        val cur = AppCompatDelegate.getApplicationLocales()
        val curTag = if (cur.isEmpty) "" else (cur[0]?.language ?: "")
        val checked = tags.indexOf(if (curTag.startsWith("zh")) "zh" else if (curTag.startsWith("en")) "en" else "")
        AlertDialog.Builder(this)
            .setTitle(R.string.lang_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                val locales = if (tags[which].isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tags[which])
                AppCompatDelegate.setApplicationLocales(locales)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun hasAllPerms(): Boolean = requiredPerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshStatus() {
        tvPerm.text = getString(if (hasAllPerms()) R.string.perm_ok else R.string.perm_missing)
        btnGrantPerm.visibility = if (hasAllPerms()) Button.GONE else Button.VISIBLE
        tvBattery.text = getString(if (isIgnoringBattery()) R.string.battery_ok else R.string.battery_need)
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBattery()) {
            Toast.makeText(this, R.string.battery_ok, Toast.LENGTH_SHORT).show(); return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showAutostartGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.autostart_title)
            .setMessage(R.string.autostart_msg)
            .setPositiveButton(R.string.autostart_open) { _, _ -> openAutostartSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAutostartSettings() {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        )
        for ((pkg, cls) in candidates) {
            try {
                startActivity(Intent().setClassName(pkg, cls)); return
            } catch (_: Exception) { /* 试下一个 */ }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    /** 诊断：直接读一次短信库，报告可读条数/最新一条/游标，并可"重置游标+立即拉取"当场验证 */
    private fun diagnoseSms() {
        Thread {
            val hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            val cursor = Prefs.get(this).getLong(Prefs.KEY_LAST_SEEN_TS, 0L)
            val sb = StringBuilder()
            sb.append("READ_SMS: ").append(if (hasRead) "已授予" else "未授予 ✗").append('\n')
            sb.append("游标: ").append(if (cursor == 0L) "未初始化" else DateFormat.format("MM-dd HH:mm:ss", cursor)).append('\n')
            var newest = 0L
            var count = 0
            if (hasRead) {
                try {
                    var newestAddr = ""
                    contentResolver.query(
                        Uri.parse("content://sms/inbox"), arrayOf("address", "date"), null, null, "date DESC"
                    )?.use { c ->
                        val ia = c.getColumnIndex("address"); val idt = c.getColumnIndex("date")
                        while (c.moveToNext()) {
                            count++
                            if (count == 1) {
                                newest = if (idt >= 0) c.getLong(idt) else 0L
                                newestAddr = if (ia >= 0) c.getString(ia) ?: "" else ""
                            }
                        }
                    }
                    sb.append("短信库读到: ").append(count).append(" 条\n")
                    if (count > 0) {
                        sb.append("最新: ").append(newestAddr).append(" @ ")
                            .append(DateFormat.format("MM-dd HH:mm:ss", newest)).append('\n')
                        sb.append(if (cursor in 1 until newest) "→ 最新在游标之后，应能抓到" else "→ 最新<=游标，会被跳过(点重置)")
                    } else {
                        sb.append("→ 读不到内容：HarmonyOS 可能没把短信写入标准库/拦截了读取 → 需改用「默认短信应用」")
                    }
                } catch (e: Exception) {
                    sb.append("读取抛异常: ").append(e.message).append("\n→ 库被拦，需改用「默认短信应用」")
                }
            }
            val newestForReset = newest
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.btn_diag_sms)
                    .setMessage(sb.toString())
                    .setPositiveButton("重置游标并拉取") { _, _ ->
                        val back = if (newestForReset > 0) newestForReset - 60_000 else System.currentTimeMillis() - 3_600_000
                        Prefs.get(this).edit().putLong(Prefs.KEY_LAST_SEEN_TS, back).apply()
                        Thread { SmsScanner.scan(this) }.start()
                        Toast.makeText(this, "已重置游标并拉取，回主界面看最近上传", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }.start()
    }

    private fun load() {
        val p = Prefs.get(this)
        swKeepalive.isChecked = p.getBoolean(Prefs.KEY_KEEPALIVE_AUDIO, false)
        swReconcile.isChecked = p.getBoolean(Prefs.KEY_RECONCILE_ENABLED, true)
        etReconcileMin.setText(p.getInt(Prefs.KEY_RECONCILE_MIN, 15).toString())
        etLogKeep.setText(p.getInt(Prefs.KEY_LOG_KEEP, 200).toString())
        etBaseUrl.setText(p.getString(Prefs.KEY_BASE_URL, ""))
        etBaseUrl.hint = Prefs.DEFAULT_BASE_URL
    }

    private fun save() {
        val minutes = etReconcileMin.text.toString().toIntOrNull()?.coerceAtLeast(15) ?: 15
        val keep = etLogKeep.text.toString().toIntOrNull()?.coerceIn(20, 2000) ?: 200
        Prefs.get(this).edit()
            .putBoolean(Prefs.KEY_KEEPALIVE_AUDIO, swKeepalive.isChecked)
            .putBoolean(Prefs.KEY_RECONCILE_ENABLED, swReconcile.isChecked)
            .putInt(Prefs.KEY_RECONCILE_MIN, minutes)
            .putInt(Prefs.KEY_LOG_KEEP, keep)
            .putString(Prefs.KEY_BASE_URL, etBaseUrl.text.toString().trim())
            .apply()
        WorkScheduler.scheduleReconcile(this)
        // 服务在运行时重新下发 onStartCommand，让保活方式（媒体类型/静音音频）即时切换
        if (Prefs.isRunning(this)) {
            val svc = Intent(this, ForwardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        }
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }
}
