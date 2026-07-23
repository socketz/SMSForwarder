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
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvChannels: TextView
    private lateinit var tvPerm: TextView
    private lateinit var tvBattery: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnGrantPerm: Button
    private lateinit var btnBattery: Button
    private lateinit var llLog: LinearLayout

    private val requiredPerms: Array<String>
        get() = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        tvStatus = findViewById(R.id.tvStatus)
        tvChannels = findViewById(R.id.tvChannels)
        tvPerm = findViewById(R.id.tvPerm)
        tvBattery = findViewById(R.id.tvBattery)
        btnToggle = findViewById(R.id.btnToggle)
        btnGrantPerm = findViewById(R.id.btnGrantPerm)
        btnBattery = findViewById(R.id.btnBattery)
        llLog = findViewById(R.id.llLog)

        btnToggle.setOnClickListener { toggleService() }
        btnGrantPerm.setOnClickListener { requestPermissions(requiredPerms, 100) }
        btnBattery.setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { showAutostartGuide() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            ForwardLog.clear(this); refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun hasAllPerms(): Boolean = requiredPerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun toggleService() {
        if (Prefs.isRunning(this)) {
            Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, false).apply()
            stopService(Intent(this, ForwardService::class.java))
        } else {
            if (!hasAllPerms()) {
                Toast.makeText(this, R.string.toast_need_perm, Toast.LENGTH_LONG).show()
                requestPermissions(requiredPerms, 100)
                return
            }
            val svc = Intent(this, ForwardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            Prefs.get(this).edit().putBoolean(Prefs.KEY_RUNNING, true).apply()
        }
        refresh()
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBattery()) {
            Toast.makeText(this, R.string.battery_ok, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 国产 ROM 自启动/后台冻结白名单引导：尝试打开常见入口，失败回退到应用详情页 */
    private fun showAutostartGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.autostart_title)
            .setMessage(R.string.autostart_msg)
            .setPositiveButton(R.string.autostart_open) { _, _ -> openAutostartSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAutostartSettings() {
        // 各厂商自启动管理界面组件名（尽力而为，打不开就回退）
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        )
        for ((pkg, cls) in candidates) {
            try {
                startActivity(Intent().setClassName(pkg, cls))
                return
            } catch (_: Exception) { /* 试下一个 */ }
        }
        // 回退：应用详情页，用户自行进入「自启动/电池」设置
        try {
            startActivity(Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ))
        } catch (_: Exception) {}
    }

    private fun refresh() {
        val running = Prefs.isRunning(this)
        tvStatus.text = getString(if (running) R.string.status_running else R.string.status_stopped)
        btnToggle.text = getString(if (running) R.string.btn_stop else R.string.btn_start)

        val mark = { on: Boolean -> if (on) getString(R.string.ep_set) else getString(R.string.ep_unset) }
        tvChannels.text = getString(
            R.string.endpoints_fmt,
            mark(Prefs.primaryUrl(this).isNotBlank()),
            mark(Prefs.backupUrl(this).isNotBlank()),
        )

        tvPerm.text = getString(if (hasAllPerms()) R.string.perm_ok else R.string.perm_missing)
        btnGrantPerm.visibility = if (hasAllPerms()) View.GONE else View.VISIBLE

        tvBattery.text = getString(if (isIgnoringBattery()) R.string.battery_ok else R.string.battery_need)

        renderLog()
    }

    private fun renderLog() {
        llLog.removeAllViews()
        val logs = ForwardLog.all(this).take(100)
        if (logs.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.log_empty)
                setTextColor(0xFF888888.toInt())
                setPadding(0, 24, 0, 24)
            }
            llLog.addView(tv)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (e in logs) {
            val row = inflater.inflate(R.layout.item_log, llLog, false)
            row.findViewById<TextView>(R.id.tvTime).text =
                DateFormat.format("MM-dd HH:mm:ss", e.time)
            row.findViewById<TextView>(R.id.tvSender).text = e.sender
            row.findViewById<TextView>(R.id.tvChannel).text = when (e.channel) {
                Prefs.CH_PRIMARY -> getString(R.string.log_via_primary)
                Prefs.CH_BACKUP -> getString(R.string.log_via_backup)
                else -> e.channel
            }
            val st = row.findViewById<TextView>(R.id.tvResult)
            if (e.ok) {
                st.text = getString(R.string.log_ok)
                st.setTextColor(0xFF00E676.toInt())
            } else {
                st.text = getString(R.string.log_fail)
                st.setTextColor(0xFFFF5252.toInt())
            }
            row.findViewById<TextView>(R.id.tvDetail).text = e.detail
            llLog.addView(row)
        }
    }
}
