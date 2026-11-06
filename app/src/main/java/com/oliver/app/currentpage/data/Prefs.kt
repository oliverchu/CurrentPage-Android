package com.oliver.app.currentpage.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 所有组件（Activity / 辅助功能服务 / 磁贴 / 快捷方式）共享的状态。
 * 用 SharedPreferences 而不是静态变量，保证进程被系统回收后状态仍然存在。
 */
object Prefs {
    private const val FILE = "current_page_prefs"
    private const val LEGACY_FILE = "top_activity_prefs"
    private const val KEY_MONITORING = "monitoring_enabled"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_LAST_PACKAGE = "last_package_name"
    private const val KEY_LAST_CLASS = "last_class_name"
    private const val KEY_LAST_VIEW_CLASS = "last_view_class"
    private const val KEY_LAST_VIEW_IDS = "last_view_ids"
    private const val KEY_LAST_TIME = "last_timestamp"
    private const val KEY_HISTORY = "history"
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_OVERLAY_COLLAPSED = "overlay_collapsed"
    private const val KEY_SHOW_TAG_BUTTON = "show_tag_button"
    private const val MAX_HISTORY = 12

    @Volatile
    private var migrationDone = false

    private fun sp(context: Context): SharedPreferences {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!migrationDone) {
            synchronized(this) {
                if (!migrationDone) {
                    migrateLegacy(app, prefs)
                    migrationDone = true
                }
            }
        }
        return prefs
    }

    /** 旧版本用的是 top_activity_prefs，首次启动时把设置和历史迁到新文件名。 */
    private fun migrateLegacy(app: Context, prefs: SharedPreferences) {
        if (prefs.all.isNotEmpty()) return
        val legacy = app.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val values = legacy.all
        if (values.isEmpty()) return
        prefs.edit {
            for ((key, value) in values) {
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(
                        key,
                        value.filterIsInstance<String>().toSet(),
                    )
                }
            }
        }
    }

    fun isMonitoring(context: Context): Boolean =
        sp(context).getBoolean(KEY_MONITORING, false)

    fun setMonitoring(context: Context, enabled: Boolean) {
        sp(context).edit { putBoolean(KEY_MONITORING, enabled) }
    }

    /** 选中的“调试应用”；为 null 时表示监测所有应用。 */
    fun targetPackage(context: Context): String? =
        sp(context).getString(KEY_TARGET_PACKAGE, null)

    fun setTargetPackage(context: Context, packageName: String?) {
        sp(context).edit {
            if (packageName.isNullOrBlank()) remove(KEY_TARGET_PACKAGE)
            else putString(KEY_TARGET_PACKAGE, packageName)
        }
    }

    fun lastRecord(context: Context): ActivityRecord? {
        val prefs = sp(context)
        val packageName = prefs.getString(KEY_LAST_PACKAGE, null) ?: return null
        return ActivityRecord(
            packageName = packageName,
            className = prefs.getString(KEY_LAST_CLASS, "").orEmpty(),
            timestamp = prefs.getLong(KEY_LAST_TIME, 0L),
            viewClass = prefs.getString(KEY_LAST_VIEW_CLASS, null),
            viewIds = prefs.getString(KEY_LAST_VIEW_IDS, null)
                ?.split(";")
                ?.filter { it.isNotBlank() }
                .orEmpty(),
        )
    }

    fun saveRecord(context: Context, record: ActivityRecord) {
        sp(context).edit {
            putString(KEY_LAST_PACKAGE, record.packageName)
            putString(KEY_LAST_CLASS, record.className)
            putLong(KEY_LAST_TIME, record.timestamp)
            if (record.viewClass.isNullOrBlank()) {
                remove(KEY_LAST_VIEW_CLASS)
            } else {
                putString(KEY_LAST_VIEW_CLASS, record.viewClass)
            }
            if (record.viewIds.isEmpty()) {
                remove(KEY_LAST_VIEW_IDS)
            } else {
                putString(KEY_LAST_VIEW_IDS, record.viewIds.joinToString(";"))
            }
        }
        appendHistory(context, record)
    }

    /** 悬浮窗上是否显示“布局 id 快照”按钮（默认不显示）。 */
    fun showTagButton(context: Context): Boolean =
        sp(context).getBoolean(KEY_SHOW_TAG_BUTTON, false)

    fun setShowTagButton(context: Context, enabled: Boolean) {
        sp(context).edit { putBoolean(KEY_SHOW_TAG_BUTTON, enabled) }
    }

    fun history(context: Context): List<String> =
        sp(context).getString(KEY_HISTORY, "").orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

    fun clearHistory(context: Context) {
        sp(context).edit { remove(KEY_HISTORY) }
    }

    /** 悬浮窗拖拽后的位置（屏幕像素）；null 表示使用默认位置。 */
    fun overlayPosition(context: Context): Pair<Int, Int>? {
        val prefs = sp(context)
        val x = prefs.getInt(KEY_OVERLAY_X, -1)
        val y = prefs.getInt(KEY_OVERLAY_Y, -1)
        return if (x >= 0 && y >= 0) x to y else null
    }

    fun setOverlayPosition(context: Context, x: Int, y: Int) {
        sp(context).edit {
            putInt(KEY_OVERLAY_X, x)
            putInt(KEY_OVERLAY_Y, y)
        }
    }

    fun isOverlayCollapsed(context: Context): Boolean =
        sp(context).getBoolean(KEY_OVERLAY_COLLAPSED, false)

    fun setOverlayCollapsed(context: Context, collapsed: Boolean) {
        sp(context).edit { putBoolean(KEY_OVERLAY_COLLAPSED, collapsed) }
    }

    private fun appendHistory(context: Context, record: ActivityRecord) {
        val line = "${ActivityRecord.formatTime(record.timestamp)}  " +
            "${record.packageName}/${record.displayClass()}"
        val entries = (listOf(line) + history(context)).take(MAX_HISTORY)
        sp(context).edit { putString(KEY_HISTORY, entries.joinToString("\n")) }
    }
}
