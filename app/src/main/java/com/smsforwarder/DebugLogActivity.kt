package com.smsforwarder

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 运行日志界面：显示服务状态/上次轮询/游标 + 最近的内部动作（最多 300 条）。每 2s 自动刷新。 */
class DebugLogActivity : AppCompatActivity() {

    private lateinit var tvHeader: TextView
    private lateinit var llLog: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debuglog)
        applyEdgeToEdgeInsets(findViewById(R.id.root))
        tvHeader = findViewById(R.id.tvHeader)
        llLog = findViewById(R.id.llLog)
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { DebugLog.clear(this); render() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker) // 每 2s 自动刷新
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun fmt(t: Long) = if (t <= 0) "—" else DateFormat.format("MM-dd HH:mm:ss", t).toString()

    private fun render() {
        val running = Prefs.isRunning(this)
        val lastPoll = Prefs.get(this).getLong(Prefs.KEY_LAST_POLL_TS, 0L)
        val cursor = Prefs.get(this).getLong(Prefs.KEY_LAST_SEEN_TS, 0L)
        val interval = Prefs.pollSeconds(this)
        tvHeader.text = "服务：${if (running) "运行中" else "已停止"}\n" +
            "上次轮询：${fmt(lastPoll)}  ·  间隔：${interval}s\n" +
            "短信游标：${fmt(cursor)}"

        llLog.removeAllViews()
        val logs = DebugLog.all(this)
        if (logs.isEmpty()) {
            llLog.addView(TextView(this).apply {
                text = "暂无日志"; setTextColor(0xFF888888.toInt()); setPadding(0, 24, 0, 24)
            })
            return
        }
        for ((t, m) in logs) {
            llLog.addView(TextView(this).apply {
                text = "${fmt(t)}  $m"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 12f
                setPadding(0, 6, 0, 6)
            })
        }
    }
}
