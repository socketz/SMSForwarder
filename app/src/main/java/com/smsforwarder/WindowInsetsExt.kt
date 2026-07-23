package com.smsforwarder

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 处理 targetSdk 35+ 的强制 edge-to-edge：给根视图叠加系统栏 inset 内边距（保留原 padding），
 * 并把状态栏图标设为浅色以适配深色背景。每个 Activity 在 setContentView 之后调用一次。
 */
fun Activity.applyEdgeToEdgeInsets(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false

    val base = intArrayOf(
        root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom,
    )
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(
            base[0] + bars.left,
            base[1] + bars.top,
            base[2] + bars.right,
            base[3] + bars.bottom,
        )
        insets
    }
}
