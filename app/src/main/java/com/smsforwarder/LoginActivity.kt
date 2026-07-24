package com.smsforwarder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.os.Build

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.btnRegister)
        tvHint = findViewById(R.id.tvHint)

        btnLogin.setOnClickListener { submit(register = false) }
        btnRegister.setOnClickListener { submit(register = true) }
    }

    private fun submit(register: Boolean) {
        val user = etUsername.text.toString().trim()
        val pass = etPassword.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.login_need_fields, Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        val label = "Android " + Build.MODEL
        Thread {
            // 注册后自动登录
            if (register) {
                val r = Api.register(this, user, pass)
                if (r is ApiResp.Err) {
                    finishBusy(getString(R.string.login_fail_fmt, r.message))
                    return@Thread
                }
            }
            when (val r = Api.login(this, user, pass, label)) {
                is ApiResp.Ok -> {
                    val token = r.body.optString("device_token")
                    val uname = r.body.optString("username", user)
                    if (token.isBlank()) {
                        finishBusy(getString(R.string.login_fail_fmt, "no token"))
                    } else {
                        Prefs.setSession(this, token, uname)
                        runOnUiThread {
                            setBusy(false)
                            Toast.makeText(this, R.string.login_ok, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
                is ApiResp.Err -> finishBusy(getString(R.string.login_fail_fmt, r.message))
            }
        }.start()
    }

    private fun finishBusy(msg: String) = runOnUiThread {
        setBusy(false)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun setBusy(busy: Boolean) {
        btnLogin.isEnabled = !busy
        btnRegister.isEnabled = !busy
        tvHint.text = if (busy) getString(R.string.login_working) else getString(R.string.login_hint)
    }
}
