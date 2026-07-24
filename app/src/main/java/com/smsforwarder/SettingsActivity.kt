package com.smsforwarder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 设置：兜底对账 + 日志保留 + （隐藏）后端地址覆盖（调试用）。
 * 渠道配置在 ChannelsActivity；这里不再有接口地址/渠道字段。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var swReconcile: SwitchMaterial
    private lateinit var etReconcileMin: EditText
    private lateinit var etLogKeep: EditText
    private lateinit var etBaseUrl: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        swReconcile = findViewById(R.id.swReconcile)
        etReconcileMin = findViewById(R.id.etReconcileMin)
        etLogKeep = findViewById(R.id.etLogKeep)
        etBaseUrl = findViewById(R.id.etBaseUrl)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(); finish() }

        load()
    }

    private fun load() {
        val p = Prefs.get(this)
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
            .putBoolean(Prefs.KEY_RECONCILE_ENABLED, swReconcile.isChecked)
            .putInt(Prefs.KEY_RECONCILE_MIN, minutes)
            .putInt(Prefs.KEY_LOG_KEEP, keep)
            .putString(Prefs.KEY_BASE_URL, etBaseUrl.text.toString().trim())
            .apply()
        WorkScheduler.scheduleReconcile(this)
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }
}
