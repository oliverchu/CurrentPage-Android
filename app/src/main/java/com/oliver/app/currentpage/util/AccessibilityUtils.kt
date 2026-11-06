package com.oliver.app.currentpage.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.oliver.app.currentpage.service.MonitorAccessibilityService

object AccessibilityUtils {
    enum class State {
        /** 辅助功能总开关或本服务未开启。 */
        DISABLED,

        /** 系统设置里列着本服务，但服务并没有真正连接（常见于 MIUI/HyperOS 回收进程后）。 */
        ENABLED_NOT_CONNECTED,

        /** 服务已连接，可以正常工作。 */
        CONNECTED,
    }

    fun state(context: Context): State = when {
        MonitorAccessibilityService.instance != null -> State.CONNECTED
        isEnabledInSettings(context) -> State.ENABLED_NOT_CONNECTED
        else -> State.DISABLED
    }

    /** 服务进程是否真的连上了——只有这个为 true 才能读到窗口事件。 */
    fun isConnected(): Boolean = MonitorAccessibilityService.instance != null

    fun isEnabledInSettings(context: Context): Boolean {
        val resolver = context.contentResolver
        val accessibilityEnabled = Settings.Secure.getInt(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!accessibilityEnabled) return false

        val expected = ComponentName(context, MonitorAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabledServices
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == expected }
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
