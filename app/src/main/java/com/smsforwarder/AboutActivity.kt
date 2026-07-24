package com.smsforwarder

import android.content.Intent
import android.net.Uri
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

        findViewById<Button>(R.id.btnGithub).setOnClickListener { open(getString(R.string.link_github)) }
        findViewById<Button>(R.id.btnWebsite).setOnClickListener { open(getString(R.string.link_website)) }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }
}
