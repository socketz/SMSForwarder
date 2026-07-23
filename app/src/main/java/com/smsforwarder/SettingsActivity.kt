package com.smsforwarder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var etPrimaryUrl: EditText
    private lateinit var etBackupUrl: EditText
    private lateinit var etHttpHeaders: EditText

    private lateinit var swReconcile: SwitchMaterial
    private lateinit var etReconcileMin: EditText
    private lateinit var etLogKeep: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        etPrimaryUrl = findViewById(R.id.etPrimaryUrl)
        etBackupUrl = findViewById(R.id.etBackupUrl)
        etHttpHeaders = findViewById(R.id.etHttpHeaders)
        swReconcile = findViewById(R.id.swReconcile)
        etReconcileMin = findViewById(R.id.etReconcileMin)
        etLogKeep = findViewById(R.id.etLogKeep)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(); finish() }
        findViewById<Button>(R.id.btnTestPrimary).setOnClickListener { test(primary = true) }
        findViewById<Button>(R.id.btnTestBackup).setOnClickListener { test(primary = false) }

        load()
    }

    private fun load() {
        val p = Prefs.get(this)
        etPrimaryUrl.setText(p.getString(Prefs.KEY_PRIMARY_URL, ""))
        etBackupUrl.setText(p.getString(Prefs.KEY_BACKUP_URL, ""))
        etHttpHeaders.setText(p.getString(Prefs.KEY_HTTP_HEADERS, ""))
        swReconcile.isChecked = p.getBoolean(Prefs.KEY_RECONCILE_ENABLED, true)
        etReconcileMin.setText(p.getInt(Prefs.KEY_RECONCILE_MIN, 15).toString())
        etLogKeep.setText(p.getInt(Prefs.KEY_LOG_KEEP, 200).toString())
    }

    private fun save() {
        val minutes = etReconcileMin.text.toString().toIntOrNull()?.coerceAtLeast(15) ?: 15
        val keep = etLogKeep.text.toString().toIntOrNull()?.coerceIn(20, 2000) ?: 200
        Prefs.get(this).edit()
            .putString(Prefs.KEY_PRIMARY_URL, etPrimaryUrl.text.toString().trim())
            .putString(Prefs.KEY_BACKUP_URL, etBackupUrl.text.toString().trim())
            .putString(Prefs.KEY_HTTP_HEADERS, etHttpHeaders.text.toString())
            .putBoolean(Prefs.KEY_RECONCILE_ENABLED, swReconcile.isChecked)
            .putInt(Prefs.KEY_RECONCILE_MIN, minutes)
            .putInt(Prefs.KEY_LOG_KEEP, keep)
            .apply()
        WorkScheduler.scheduleReconcile(this)
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    /** 保存当前配置后，向指定接口发一条测试短信，方便验证接口是否正确 */
    private fun test(primary: Boolean) {
        save()
        val url = if (primary) etPrimaryUrl.text.toString().trim()
        else etBackupUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, R.string.toast_url_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val headers = etHttpHeaders.text.toString()
        val sms = Sms("TEST", getString(R.string.test_body), System.currentTimeMillis())
        Toast.makeText(this, R.string.toast_testing, Toast.LENGTH_SHORT).show()
        Thread {
            val r = HttpForwarder.post(url, headers, sms)
            runOnUiThread {
                val msg = if (r.ok) getString(R.string.toast_test_ok)
                else getString(R.string.toast_test_fail, r.detail)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
