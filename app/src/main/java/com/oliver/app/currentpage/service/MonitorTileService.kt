package com.oliver.app.currentpage.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.oliver.app.currentpage.R
import com.oliver.app.currentpage.data.AppInfoUtils
import com.oliver.app.currentpage.data.MonitorState
import com.oliver.app.currentpage.data.Prefs
import com.oliver.app.currentpage.domain.MonitoringController
import com.oliver.app.currentpage.ui.MainActivity
import com.oliver.app.currentpage.util.AccessibilityUtils

/**
 * 快捷设置磁贴：点击开关监测。
 * 未连接辅助功能时，直接打开主界面引导用户处理。
 */
class MonitorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (!AccessibilityUtils.isConnected()) {
            openMainActivity()
            return
        }

        MonitoringController.toggle(this)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val armed = AccessibilityUtils.isConnected() && Prefs.isMonitoring(this)
        val target = Prefs.targetPackage(this)

        tile.state = if (armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !armed -> getString(R.string.tile_subtitle_idle)
                target == null -> getString(R.string.tile_subtitle_all)
                MonitorState.targetVisible ->
                    getString(R.string.tile_subtitle_target, AppInfoUtils.label(this, target))
                else ->
                    getString(R.string.tile_subtitle_waiting, AppInfoUtils.label(this, target))
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            // API < 34 只能使用已废弃的 Intent 重载；SDK 判断保证新系统不会走到这里。
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** 让系统重新回调 onStartListening，从而刷新磁贴副标题。 */
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context.applicationContext,
                    ComponentName(context, MonitorTileService::class.java),
                )
            }
        }
    }
}
