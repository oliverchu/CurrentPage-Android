package com.oliver.app.currentpage.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次“前台窗口变化”快照。
 *
 * className 是 AccessibilityEvent 报告的窗口类（Activity / Dialog / BottomSheet 等）；
 * viewClass 是在对话框、BottomSheet 等场景下，从可见视图树里找到的、更具体的子类；
 * viewIds 是视图树里带 android:id 的节点资源名（如 changlong:id/sheet_root），
 * 用于在没有自定义类名时定位具体是哪个布局/弹层。
 * Fragment / Composable 的类名无法通过无障碍 API 获取，只能尽力拿到视图类。
 */
data class ActivityRecord(
    val packageName: String,
    val className: String,
    val timestamp: Long,
    val viewClass: String? = null,
    val viewIds: List<String> = emptyList(),
) {
    /** 用于去重：窗口类或子类变化都算一条新记录。 */
    val key: String
        get() = if (viewClass.isNullOrBlank()) {
            "$packageName/$className"
        } else {
            "$packageName/$className/$viewClass"
        }

    /** 优先展示更具体的子类（对话框内容、BottomSheet 内容等）。 */
    fun primaryClass(): String = viewClass?.takeIf { it.isNotBlank() } ?: className

    /** 窗口本身的类；与主类不同时作为第二行显示。 */
    fun windowClass(): String? =
        className.takeIf { it.isNotBlank() && it != primaryClass() }

    /** 主类 + 可选的窗口类，用于悬浮窗和主界面。 */
    fun displayClassText(): String {
        val primary = primaryClass()
        val window = windowClass() ?: return primary
        return "$primary\n↳ $window"
    }

    /** 布局 id（开启“显示布局 ID”时展示），最多 3 个。 */
    fun displayViewIds(): String? =
        viewIds.takeIf { it.isNotEmpty() }?.joinToString("  ")

    /** 类名通常很长，历史记录里只保留最后一段。 */
    fun displayClass(): String {
        val primary = primaryClass()
        return primary.substringAfterLast('.').ifBlank { primary }
    }

    companion object {
        fun formatTime(timestamp: Long): String =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}

object AppInfoUtils {
    /** 查询应用的显示名称；查不到（例如包已被卸载）时退回包名。 */
    fun label(context: Context, packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName
    }
}
