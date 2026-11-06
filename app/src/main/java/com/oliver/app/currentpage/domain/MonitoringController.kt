package com.oliver.app.currentpage.domain

import android.content.Context
import com.oliver.app.currentpage.data.MonitorBus
import com.oliver.app.currentpage.data.MonitorState
import com.oliver.app.currentpage.data.Prefs
import com.oliver.app.currentpage.service.MonitorTileService
import com.oliver.app.currentpage.ui.overlay.OverlayController

/**
 * 监测开关与调试目标的唯一写入口。
 *
 * MainViewModel、快捷设置磁贴、桌面快捷方式都走这里，
 * 避免同一套“写 Prefs + 收悬浮窗 + 通知 + 刷磁贴”逻辑散落三处。
 */
object MonitoringController {

    fun setMonitoring(context: Context, enabled: Boolean) {
        Prefs.setMonitoring(context, enabled)
        if (!enabled) {
            OverlayController.hide()
            MonitorState.setTargetVisible(false)
        }
        MonitorBus.notifyMonitoringStateChanged()
        MonitorTileService.requestUpdate(context)
    }

    /** 返回切换后的状态，方便调用方更新磁贴/提示。 */
    fun toggle(context: Context): Boolean {
        val enable = !Prefs.isMonitoring(context)
        setMonitoring(context, enable)
        return enable
    }

    fun setTargetPackage(context: Context, packageName: String?) {
        Prefs.setTargetPackage(context, packageName)
        MonitorState.setTargetVisible(false)
        OverlayController.hide()
        MonitorBus.notifyMonitoringStateChanged()
        MonitorTileService.requestUpdate(context)
    }
}
