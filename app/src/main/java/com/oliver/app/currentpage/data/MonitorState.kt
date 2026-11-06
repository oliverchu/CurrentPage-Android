package com.oliver.app.currentpage.data

/**
 * 运行期状态：目标应用当前是否在前台。
 * 只存在于当前进程，进程被回收后自然回到 false，由下一次窗口事件重新判定。
 */
object MonitorState {
    @Volatile
    var targetVisible: Boolean = false
        private set

    /** 主界面是否在前台；用于避免在应用自己的界面上叠加悬浮窗。 */
    @Volatile
    var mainVisible: Boolean = false
        private set

    /** 屏幕熄灭前悬浮窗是否正在显示，用于解锁后恢复。 */
    @Volatile
    var overlayWasVisibleBeforeScreenOff: Boolean = false
        private set

    fun setTargetVisible(visible: Boolean) {
        if (targetVisible == visible) return
        targetVisible = visible
        MonitorBus.notifyTargetVisibilityChanged()
    }

    fun setMainVisible(visible: Boolean) {
        mainVisible = visible
    }

    fun setOverlayWasVisibleBeforeScreenOff(visible: Boolean) {
        overlayWasVisibleBeforeScreenOff = visible
    }
}
