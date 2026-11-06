package com.oliver.app.currentpage.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.oliver.app.currentpage.service.MonitorAccessibilityService

/** 一个带 id 的视图在屏幕上的位置标注。 */
data class IdTag(
    val fullId: String,
    val bounds: Rect,
) {
    /** 短名（如 sheet_root），用于画在视图角落的小标签。 */
    val label: String
        get() = fullId.substringAfterLast(":id/").ifBlank { fullId }
}

/**
 * 布局 id 位置标注窗口：全屏、完全不可触摸（点击穿透），
 * 在每个带 android:id 的视图边界画一个细框 + id 小标签。
 *
 * 由辅助功能服务添加（TYPE_ACCESSIBILITY_OVERLAY），只在悬浮窗按钮触发手动快照时显示。
 */
@SuppressLint("StaticFieldLeak")
object IdTagOverlay {
    private const val TAG = "CurrentPageIdTags"

    private var windowManager: WindowManager? = null
    private var tagView: TagView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    /** 更新标注；tags 为空或悬浮窗隐藏时隐藏。 */
    fun update(context: Context, tags: List<IdTag>) {
        val service = MonitorAccessibilityService.instance ?: return
        if (tags.isEmpty() || !OverlayController.isShowing()) {
            hide()
            return
        }

        ensureWindow(service)
        val view = tagView ?: return
        view.submitTags(tags)
        if (!attached) {
            try {
                windowManager?.addView(view, params)
                attached = true
                Log.d(TAG, "id tags shown: ${tags.size}")
            } catch (e: Exception) {
                Log.w(TAG, "add id tag overlay failed", e)
            }
        }
    }

    fun hide() {
        val view = tagView ?: return
        if (!attached) return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "remove id tag overlay failed", e)
        } finally {
            attached = false
            view.submitTags(emptyList())
        }
    }

    fun reset() {
        hide()
        tagView = null
        params = null
        windowManager = null
        attached = false
    }

    private fun ensureWindow(service: Context) {
        if (tagView != null) return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val view = TagView(service)
        tagView = view
        windowManager = wm
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 与 boundsInScreen 同一坐标系（含挖孔区域），标注位置才对得上
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // ALWAYS 是 API 30 才加入的，28/29 用 SHORT_EDGES 近似
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private class TagView(context: Context) : View(context) {

        private class Item(val tag: IdTag, val box: TagRect)

        private class PlacedLabel(val tag: IdTag, val rect: TagRect)

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = 0xFF00E676.toInt() // 绿色框线
        }

        private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xCCE53935.toInt() // 红色标签底，约 80% 不透明
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(8f)
            color = Color.WHITE
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        private var tags: List<IdTag> = emptyList()

        /** 每次提交标签或尺寸变化时预计算，onDraw 只负责画。 */
        private var items: List<Item> = emptyList()
        private var labels: List<PlacedLabel> = emptyList()

        private val corner = dp(2f)
        private val padH = dp(2f)
        private val padV = dp(1f)
        private val gap = dp(1f)

        fun submitTags(newTags: List<IdTag>) {
            tags = newTags
            visibility = if (tags.isEmpty()) GONE else VISIBLE
            rebuild()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuild()
        }

        private fun rebuild() {
            val screenW = width.toFloat()
            val screenH = height.toFloat()
            if (screenW <= 0f || screenH <= 0f) {
                items = emptyList()
                labels = emptyList()
                return
            }

            // 先收集可见框，统一先画框、再画标签，保证标签压在最上层
            val visible = ArrayList<Item>(tags.size)
            for (tag in tags) {
                val b = tag.bounds
                if (b.width() <= 0 || b.height() <= 0) continue
                // 完全在屏幕外的视图（如滚动页面里折叠在底下的部分）不画，
                // 否则一堆钳到屏幕边缘的空框会叠在一起
                if (b.top >= screenH || b.bottom <= 0f || b.left >= screenW || b.right <= 0f) {
                    continue
                }
                visible.add(
                    Item(
                        tag,
                        TagRect(
                            b.left.toFloat().coerceIn(0f, screenW),
                            b.top.toFloat().coerceIn(0f, screenH),
                            b.right.toFloat().coerceIn(0f, screenW),
                            b.bottom.toFloat().coerceIn(0f, screenH),
                        ),
                    ),
                )
            }
            items = visible

            // 标签避让：在框外上方/下方、框内角落依次尝试，都冲突就跳过该标签
            val placed = ArrayList<TagRect>(visible.size)
            val result = ArrayList<PlacedLabel>(visible.size)
            for (item in visible) {
                val textWidth = textPaint.measureText(item.tag.label)
                val tagW = textWidth + padH * 2
                val tagH = textPaint.fontSpacing + padV * 2
                val rect = IdTagLayout.findFreeRect(
                    box = item.box,
                    width = tagW,
                    height = tagH,
                    placed = placed,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    gap = gap,
                ) ?: continue
                placed.add(rect)
                result.add(PlacedLabel(item.tag, rect))
            }
            labels = result
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (item in items) {
                val box = item.box
                canvas.drawRect(box.left, box.top, box.right, box.bottom, boxPaint)
            }
            for (placed in labels) {
                val rect = placed.rect
                canvas.drawRoundRect(
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    corner,
                    corner,
                    tagBgPaint,
                )
                // 文字水平、垂直都居中
                val baseline = rect.centerY - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(placed.tag.label, rect.centerX, baseline, textPaint)
            }
        }

        private fun dp(value: Float): Float =
            value * resources.displayMetrics.density
    }
}
