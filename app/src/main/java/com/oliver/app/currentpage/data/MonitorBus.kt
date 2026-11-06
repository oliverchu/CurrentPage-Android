package com.oliver.app.currentpage.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 进程内事件总线。
 *
 * 生产者（辅助功能服务、磁贴、快捷方式）和消费者（MainActivity）处于同一进程，
 * 用内存回调替代全局广播，既简单又不受 Android 8+/14+ 广播限制影响。
 */
object MonitorBus {
    interface Listener {
        fun onActivityChanged(record: ActivityRecord) {}
        fun onMonitoringStateChanged() {}
        fun onServiceStateChanged() {}
        fun onTargetVisibilityChanged() {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyActivityChanged(record: ActivityRecord) {
        mainHandler.post { listeners.forEach { it.onActivityChanged(record) } }
    }

    fun notifyMonitoringStateChanged() {
        mainHandler.post { listeners.forEach { it.onMonitoringStateChanged() } }
    }

    fun notifyServiceStateChanged() {
        mainHandler.post { listeners.forEach { it.onServiceStateChanged() } }
    }

    fun notifyTargetVisibilityChanged() {
        mainHandler.post { listeners.forEach { it.onTargetVisibilityChanged() } }
    }
}
