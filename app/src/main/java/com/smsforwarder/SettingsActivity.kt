package com.smsforwarder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var swHttp: SwitchMaterial
    private lateinit var etHttpUrl: EditText
    private lateinit var etHttpHeaders: EditText

    private lateinit var swTg: SwitchMaterial
    private lateinit var etTgToken: EditText
    private lateinit var etTgChat: EditText
    private lateinit var etTgApi: EditText

    private lateinit var swReconcile: SwitchMaterial
    private lateinit var etReconcileMin: EditText
    private lateinit var etLogKeep: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        swHttp = findViewById(R.id.swHttp)
        etHttpUrl = findViewById(R.id.etHttpUrl)
        etHttpHeaders = findViewById(R.id.etHttpHeaders)
        swTg = findViewById(R.id.swTg)
        etTgToken = findViewById(R.id.etTgToken)
        etTgChat = findViewById(R.id.etTgChat)
        etTgApi = findViewById(R.id.etTgApi)
        swReconcile = findViewById(R.id.swReconcile)
        etReconcileMin = findViewById(R.id.etReconcileMin)
        etLogKeep = findViewById(R.id.etLogKeep)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(); finish() }
        findViewById<Button>(R.id.btnTestHttp).setOnClickListener { test(Prefs.CH_HTTP) }
        findViewById<Button>(R.id.btnTestTg).setOnClickListener { test(Prefs.CH_TG) }

        load()
    }

    private fun load() {
        val p = Prefs.get(this)
        swHttp.isChecked = p.getBoolean(Prefs.KEY_HTTP_ENABLED, false)
        etHttpUrl.setText(p.getString(Prefs.KEY_HTTP_URL, ""))
        etHttpHeaders.setText(p.getString(Prefs.KEY_HTTP_HEADERS, ""))
        swTg.isChecked = p.getBoolean(Prefs.KEY_TG_ENABLED, false)
        etTgToken.setText(p.getString(Prefs.KEY_TG_TOKEN, ""))
        etTgChat.setText(p.getString(Prefs.KEY_TG_CHAT, ""))
        etTgApi.setText(p.getString(Prefs.KEY_TG_API, Prefs.DEFAULT_TG_API))
        swReconcile.isChecked = p.getBoolean(Prefs.KEY_RECONCILE_ENABLED, true)
        etReconcileMin.setText(p.getInt(Prefs.KEY_RECONCILE_MIN, 15).toString())
        etLogKeep.setText(p.getInt(Prefs.KEY_LOG_KEEP, 200).toString())
    }

    private fun save() {
        val minutes = etReconcileMin.text.toString().toIntOrNull()?.coerceAtLeast(15) ?: 15
        val keep = etLogKeep.text.toString().toIntOrNull()?.coerceIn(20, 2000) ?: 200
        val api = etTgApi.text.toString().trim().ifBlank { Prefs.DEFAULT_TG_API }
        Prefs.get(this).edit()
            .putBoolean(Prefs.KEY_HTTP_ENABLED, swHttp.isChecked)
            .putString(Prefs.KEY_HTTP_URL, etHttpUrl.text.toString().trim())
            .putString(Prefs.KEY_HTTP_HEADERS, etHttpHeaders.text.toString())
            .putBoolean(Prefs.KEY_TG_ENABLED, swTg.isChecked)
            .putString(Prefs.KEY_TG_TOKEN, etTgToken.text.toString().trim())
            .putString(Prefs.KEY_TG_CHAT, etTgChat.text.toString().trim())
            .putString(Prefs.KEY_TG_API, api)
            .putBoolean(Prefs.KEY_RECONCILE_ENABLED, swReconcile.isChecked)
            .putInt(Prefs.KEY_RECONCILE_MIN, minutes)
            .putInt(Prefs.KEY_LOG_KEEP, keep)
            .apply()
        WorkScheduler.scheduleReconcile(this)
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    /** 保存当前配置后，向指定渠道发一条测试短信，方便验证配置是否正确 */
    private fun test(channel: String) {
        save()
        val sender = senderFor(channel) ?: return
        val sms = Sms("TEST", getString(R.string.test_body), System.currentTimeMillis())
        Toast.makeText(this, R.string.toast_testing, Toast.LENGTH_SHORT).show()
        Thread {
            val r = sender.send(this, sms)
            runOnUiThread {
                val msg = if (r.ok) getString(R.string.toast_test_ok)
                else getString(R.string.toast_test_fail, r.detail)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
