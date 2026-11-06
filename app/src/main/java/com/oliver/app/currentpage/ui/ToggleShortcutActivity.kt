package com.oliver.app.currentpage.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.oliver.app.currentpage.domain.MonitoringController
import com.oliver.app.currentpage.util.AccessibilityUtils

/**
 * 长按桌面图标快捷方式的入口：透明 Activity，只负责开关监测然后立即结束。
 * 原版用 AppShortcutsActivity 做同样的事，这里保留这一交互但重新实现。
 */
class ToggleShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AccessibilityUtils.isConnected()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        MonitoringController.toggle(this)
        finish()
    }
}
