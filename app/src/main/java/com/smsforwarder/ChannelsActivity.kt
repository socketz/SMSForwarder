package com.smsforwarder

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject

/**
 * 渠道管理。全部通过后端 API：列出/新增/启用停用/测试/删除，以及 Telegram 一键绑定。
 * v1 只有 Telegram 渠道（Telegram 点绑定，什么都不填）。
 */
class ChannelsActivity : AppCompatActivity() {

    private lateinit var llChannels: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)
        applyEdgeToEdgeInsets(findViewById(R.id.root))

        llChannels = findViewById(R.id.llChannels)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddTelegram).setOnClickListener { addTelegram() }
    }

    override fun onResume() {
        super.onResume()
        loadChannels()
    }

    private fun call(work: () -> ApiResp, onDone: (ApiResp) -> Unit) {
        Thread {
            val r = work()
            runOnUiThread { onDone(r) }
        }.start()
    }

    private fun loadChannels() {
        call({ Api.listChannels(this) }) { r ->
            when (r) {
                is ApiResp.Ok -> render(Api.channelsArray(r.body))
                is ApiResp.Err -> {
                    tvEmpty.text = getString(R.string.ch_load_fail_fmt, r.message)
                    tvEmpty.visibility = TextView.VISIBLE
                    llChannels.removeAllViews()
                }
            }
        }
    }

    private fun render(arr: org.json.JSONArray) {
        llChannels.removeAllViews()
        if (arr.length() == 0) {
            tvEmpty.text = getString(R.string.ch_empty)
            tvEmpty.visibility = TextView.VISIBLE
            return
        }
        tvEmpty.visibility = TextView.GONE
        val inflater = LayoutInflater.from(this)
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            llChannels.addView(buildRow(inflater, c))
        }
    }

    private fun buildRow(inflater: LayoutInflater, c: JSONObject): android.view.View {
        val id = c.optLong("id")
        val type = c.optString("type")
        val enabled = c.optBoolean("enabled")
        val ready = c.optBoolean("ready")
        val isTelegram = type == "telegram"

        val row = inflater.inflate(R.layout.item_channel, llChannels, false)
        val tvType = row.findViewById<TextView>(R.id.tvChannelType)
        val tvState = row.findViewById<TextView>(R.id.tvChannelState)
        val sw = row.findViewById<SwitchMaterial>(R.id.swChannelEnabled)
        val btnBind = row.findViewById<Button>(R.id.btnBind)
        val btnTest = row.findViewById<Button>(R.id.btnTest)
        val btnDelete = row.findViewById<Button>(R.id.btnDelete)

        tvType.text = if (isTelegram) getString(R.string.ch_telegram) else type
        tvState.text = if (ready) getString(R.string.ch_ready) else getString(R.string.ch_tg_unbound)
        tvState.setTextColor(if (ready) 0xFF00E676.toInt() else 0xFFFFA726.toInt())

        sw.isChecked = enabled
        sw.setOnCheckedChangeListener { _, checked ->
            call({ Api.updateChannel(this, id, checked, null) }) { r ->
                if (r is ApiResp.Err) {
                    Toast.makeText(this, getString(R.string.ch_op_fail_fmt, r.message), Toast.LENGTH_SHORT).show()
                    sw.isChecked = !checked
                }
            }
        }

        // 只有未绑定的 Telegram 才显示「绑定」
        btnBind.visibility = if (isTelegram && !ready) android.view.View.VISIBLE else android.view.View.GONE
        btnBind.setOnClickListener { bindTelegram(id) }

        btnTest.setOnClickListener { testChannel(id) }
        btnDelete.setOnClickListener { confirmDelete(id) }
        return row
    }

    private fun addTelegram() {
        call({ Api.createChannel(this, "telegram", JSONObject(), true) }) { r ->
            when (r) {
                is ApiResp.Ok -> {
                    Toast.makeText(this, R.string.ch_added_tg, Toast.LENGTH_LONG).show()
                    loadChannels()
                }
                is ApiResp.Err -> Toast.makeText(this, getString(R.string.ch_op_fail_fmt, r.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindTelegram(id: Long) {
        call({ Api.bindCode(this, id) }) { r ->
            when (r) {
                is ApiResp.Ok -> {
                    val deeplink = r.body.optString("deeplink")
                    if (deeplink.isBlank()) {
                        Toast.makeText(this, R.string.ch_bind_fail, Toast.LENGTH_LONG).show()
                        return@call
                    }
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deeplink)))
                        Toast.makeText(this, R.string.ch_bind_open_tg, Toast.LENGTH_LONG).show()
                    } catch (_: ActivityNotFoundException) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.ch_bind_no_tg)
                            .setMessage(deeplink)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                is ApiResp.Err -> Toast.makeText(this, getString(R.string.ch_op_fail_fmt, r.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testChannel(id: Long) {
        Toast.makeText(this, R.string.ch_testing, Toast.LENGTH_SHORT).show()
        call({ Api.testChannel(this, id) }) { r ->
            when (r) {
                is ApiResp.Ok -> {
                    if (r.body.optBoolean("ok", false)) {
                        Toast.makeText(this, R.string.ch_test_ok, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, getString(R.string.ch_test_fail_fmt, r.body.optString("detail")), Toast.LENGTH_LONG).show()
                    }
                }
                is ApiResp.Err -> Toast.makeText(this, getString(R.string.ch_test_fail_fmt, r.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ch_delete_title)
            .setMessage(R.string.ch_delete_msg)
            .setPositiveButton(R.string.ch_delete) { _, _ ->
                call({ Api.deleteChannel(this, id) }) { r ->
                    when (r) {
                        is ApiResp.Ok -> loadChannels()
                        is ApiResp.Err -> Toast.makeText(this, getString(R.string.ch_op_fail_fmt, r.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
