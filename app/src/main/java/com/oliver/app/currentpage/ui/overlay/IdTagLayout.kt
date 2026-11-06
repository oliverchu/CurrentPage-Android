package com.oliver.app.currentpage.ui.overlay

/**
 * 纯 Kotlin 的矩形模型，不依赖 android.graphics，便于 JVM 单元测试。
 */
internal data class TagRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersects(other: TagRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun offset(dx: Float, dy: Float): TagRect =
        TagRect(left + dx, top + dy, right + dx, bottom + dy)

    fun inset(dx: Float, dy: Float): TagRect =
        TagRect(left - dx, top - dy, right + dx, bottom + dy)
}

/**
 * id 标签的放置算法：在视图框周围依次尝试候选位置，避开已经放下的标签。
 *
 * 候选顺序：框外上 → 框外下 → 框外上（左对齐）→ 框内右上 → 框内右下 → 框内左上。
 * 所有候选都与已放置标签冲突时返回 null，调用方跳过这个标签（框仍然绘制）。
 */
internal object IdTagLayout {

    fun findFreeRect(
        box: TagRect,
        width: Float,
        height: Float,
        placed: List<TagRect>,
        screenWidth: Float,
        screenHeight: Float,
        gap: Float,
    ): TagRect? {
        val candidates = arrayOf(
            TagRect(box.right - width, box.top - height, box.right, box.top),
            TagRect(box.right - width, box.bottom, box.right, box.bottom + height),
            TagRect(box.left, box.top - height, box.left + width, box.top),
            TagRect(box.right - width, box.top, box.right, box.top + height),
            TagRect(box.right - width, box.bottom - height, box.right, box.bottom),
            TagRect(box.left, box.top, box.left + width, box.top + height),
        )
        for (candidate in candidates) {
            val rect = clampToScreen(candidate, screenWidth, screenHeight)
            // 判断时外扩 gap，避免两个标签边缘贴得太近
            val probe = rect.inset(-gap, -gap)
            if (placed.none { it.intersects(probe) }) return rect
        }
        return null
    }

    private fun clampToScreen(rect: TagRect, screenWidth: Float, screenHeight: Float): TagRect {
        var result = rect
        if (result.left < 0f) result = result.offset(-result.left, 0f)
        if (result.right > screenWidth) result = result.offset(screenWidth - result.right, 0f)
        if (result.top < 0f) result = result.offset(0f, -result.top)
        if (result.bottom > screenHeight) result = result.offset(0f, screenHeight - result.bottom)
        return result
    }
}
