package com.smsforwarder

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.about_version, version)
    }
}
