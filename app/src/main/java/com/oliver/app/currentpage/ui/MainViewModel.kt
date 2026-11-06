package com.oliver.app.currentpage.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.oliver.app.currentpage.data.ActivityRecord
import com.oliver.app.currentpage.data.AppInfoUtils
import com.oliver.app.currentpage.data.MonitorBus
import com.oliver.app.currentpage.data.MonitorState
import com.oliver.app.currentpage.data.Prefs
import com.oliver.app.currentpage.domain.MonitoringController
import com.oliver.app.currentpage.service.MonitorAccessibilityService
import com.oliver.app.currentpage.ui.overlay.OverlayController
import com.oliver.app.currentpage.util.AccessibilityUtils

/** 主界面渲染所需的全部状态，由 [MainViewModel] 统一产出。 */
data class MainUiState(
    val record: ActivityRecord?,
    val monitoring: Boolean,
    val accessibilityState: AccessibilityUtils.State,
    val targetPackage: String?,
    val targetLabel: String,
    val targetVisible: Boolean,
    val batteryUnrestricted: Boolean,
    val showTagButton: Boolean,
    val history: List<String>,
)

/**
 * 主界面的 MVVM ViewModel：
 * - 订阅进程内事件总线（辅助功能服务/磁贴/快捷方式产生的变化）；
 * - 从 Prefs（数据源）读取并合成 [MainUiState]；
 * - 对外暴露动作方法，界面只负责渲染与转发交互。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(
        MainUiState(
            record = null,
            monitoring = false,
            accessibilityState = AccessibilityUtils.State.DISABLED,
            targetPackage = null,
            targetLabel = "",
            targetVisible = false,
            batteryUnrestricted = false,
            showTagButton = false,
            history = emptyList(),
        )
    )
    val uiState: LiveData<MainUiState> = _uiState

    private val busListener = object : MonitorBus.Listener {
        override fun onActivityChanged(record: ActivityRecord) = refresh()
        override fun onMonitoringStateChanged() = refresh()
        override fun onServiceStateChanged() = refresh()
        override fun onTargetVisibilityChanged() = refresh()
    }

    init {
        MonitorBus.register(busListener)
        refresh()
    }

    /** 从数据源重新合成状态并通知界面。 */
    fun refresh() {
        val app = getApplication<Application>()
        val target = Prefs.targetPackage(app)
        _uiState.value = MainUiState(
            record = Prefs.lastRecord(app),
            monitoring = Prefs.isMonitoring(app),
            accessibilityState = AccessibilityUtils.state(app),
            targetPackage = target,
            targetLabel = target?.let { AppInfoUtils.label(app, it) }.orEmpty(),
            targetVisible = MonitorState.targetVisible,
            batteryUnrestricted = isBatteryUnrestricted(app),
            showTagButton = Prefs.showTagButton(app),
            history = Prefs.history(app),
        )
    }

    /**
     * 省电策略是否为「无限制」：
     * 标准电池优化白名单 + MIUI MILLET 无限制名单，任一命中即视为已设置。
     */
    private fun isBatteryUnrestricted(app: Application): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return false
        if (pm.isIgnoringBatteryOptimizations(app.packageName)) return true
        val millet = runCatching {
            android.provider.Settings.System.getString(
                app.contentResolver,
                "MILLET_NO_RESTRICT_APP"
            )
        }.getOrNull()
        return millet?.split(',')?.map { it.trim() }?.contains(app.packageName) == true
    }

    /** 开关监测。关闭时同步收起悬浮窗并刷新磁贴。 */
    fun setMonitoring(enabled: Boolean) {
        MonitoringController.setMonitoring(getApplication(), enabled)
        refresh()
    }

    /** 设置/清除调试应用。清除或更换时同步收起悬浮窗。 */
    fun setTargetPackage(packageName: String?) {
        MonitoringController.setTargetPackage(getApplication(), packageName)
        refresh()
    }

    fun setMonitoringFlag(enabled: Boolean) {
        Prefs.setMonitoring(getApplication(), enabled)
        refresh()
    }

    /** 「显示 id 快照按钮」开关：控制悬浮窗上快照按钮的显隐。 */
    fun setShowTagButton(enabled: Boolean) {
        val app = getApplication<Application>()
        Prefs.setShowTagButton(app, enabled)
        OverlayController.setTagButtonEnabled(enabled)
        if (!enabled) {
            // 关按钮的同时收掉正在显示的手动快照
            MonitorAccessibilityService.instance?.clearManualTags()
        }
        refresh()
    }

    fun clearHistory() {
        Prefs.clearHistory(getApplication())
        refresh()
    }

    override fun onCleared() {
        MonitorBus.unregister(busListener)
    }
}
