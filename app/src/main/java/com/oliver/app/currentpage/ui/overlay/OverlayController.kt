package com.oliver.app.currentpage.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.oliver.app.currentpage.BuildConfig
import com.oliver.app.currentpage.R
import com.oliver.app.currentpage.data.ActivityRecord
import com.oliver.app.currentpage.data.Prefs
import com.oliver.app.currentpage.databinding.ViewControlsBinding
import com.oliver.app.currentpage.databinding.ViewOverlayBinding
import com.oliver.app.currentpage.databinding.ViewOverlayDragtouchBinding
import com.oliver.app.currentpage.service.MonitorAccessibilityService
import kotlin.math.abs

/**
 * 悬浮窗控制器。
 *
 * 三个 ACCESSIBILITY_OVERLAY 窗口：
 * 1. card    ：只负责显示，FLAG_NOT_TOUCHABLE，点击直接穿透到下层应用；
 * 2. controls：id 快照按钮（ID 文本）+ 折叠箭头（竖排），贴在卡片右缘（展开）/
 *              屏幕左缘（收起）；快照按钮 GONE 时容器自动回流；
 * 3. dragTouch：透明的拖拽触摸区（64×20dp），只接收上下拖动；可见的指示线
 *              在卡片布局内部，随卡片一起平移/收起，不会单独运动。
 *
 * 卡片固定贴在屏幕左边缘（窗口 x=0），只允许上下移动。
 * “收起”时卡片内容用 translationX 平移出窗口表面（HyperOS 的 WMS 会把负的
 * 窗口 x 钳到 0，负坐标窗口藏不住），controls 窗口滑回屏幕左缘；点击箭头带动画弹出。
 */
@SuppressLint("StaticFieldLeak")
object OverlayController {
    private const val TAG = "CurrentPageOverlay"
    private const val DRAG_TOUCH_W_DP = 64
    private const val DRAG_TOUCH_H_DP = 20
    private const val CONTROLS_GAP_DP = 4
    private const val DOCK_ANIM_MS = 220L

    private var windowManager: WindowManager? = null

    private var cardView: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var packageView: TextView? = null
    private var classView: TextView? = null

    private var dragTouchView: View? = null
    private var dragTouchParams: WindowManager.LayoutParams? = null

    private var controlsBinding: ViewControlsBinding? = null
    private var controlsView: View? = null
    private var controlsParams: WindowManager.LayoutParams? = null

    /** 「显示 id 快照按钮」开关：关闭时快照按钮 GONE，容器自动回流。 */
    private var tagBtnEnabled = false

    private var attached = false

    /** true = 卡片已收起（内容平移出窗口），controls 贴回屏幕左缘。 */
    private var collapsed = false
    private var cardY = 0
    private var dragTouchWpx = 0
    private var dragTouchHpx = 0
    private var touchSlopPx = 0
    private var dockAnimator: ValueAnimator? = null
    private var currentRecord: ActivityRecord? = null

    fun show(context: Context, record: ActivityRecord?) {
        // 只有辅助功能服务本身才有资格添加 TYPE_ACCESSIBILITY_OVERLAY 窗口
        val service = MonitorAccessibilityService.instance
        if (service == null) {
            Log.d(TAG, "show skipped: service instance null")
            return
        }
        // 自己的界面已经展示同样信息，不叠加悬浮窗
        if (record != null && record.packageName == context.packageName) {
            Log.d(TAG, "show skipped: self package")
            hide()
            return
        }

        if (!attached) {
            // 只恢复纵向位置；卡片始终贴在屏幕左边缘
            cardY = Prefs.overlayPosition(service)?.second ?: dp(service, 12)
        }

        ensureViews(service)
        currentRecord = record
        applyState(context)

        if (attached) {
            cardView?.requestLayout()
            cardView?.post { syncControlWindows() }
            return
        }

        val wm = windowManager
        val card = cardView
        val dragTouch = dragTouchView
        val controls = controlsView
        val cardLayout = cardParams
        val dragTouchLayout = dragTouchParams
        val controlsLayout = controlsParams
        if (wm == null || card == null || dragTouch == null || controls == null ||
            cardLayout == null || dragTouchLayout == null || controlsLayout == null
        ) {
            Log.w(TAG, "overlay views not ready")
            return
        }

        // hide() 可能中断过动画，视图/LayoutParams 里残留着中间帧的状态；
        // 重新添加前必须按当前收起状态重置，否则会带着脏位置出现
        cardLayout.x = 0
        cardLayout.y = cardY
        card.translationX = if (collapsed) {
            -service.resources.displayMetrics.widthPixels.toFloat()
        } else {
            0f
        }
        dragTouchLayout.x = 0
        dragTouchLayout.y = cardY
        dragTouch.translationY = 0f
        dragTouch.visibility = if (collapsed) View.GONE else View.VISIBLE
        // 收起时贴左缘；展开时先放到屏幕右缘，等首次布局后由 sync 挪到卡片右缘
        controlsLayout.x = if (collapsed) 0 else service.resources.displayMetrics.widthPixels
        controlsLayout.y = cardY
        controlsBinding?.overlayTagbtn?.visibility =
            if (tagBtnEnabled) View.VISIBLE else View.GONE

        try {
            wm.addView(card, cardLayout)
            wm.addView(dragTouch, dragTouchLayout)
            wm.addView(controls, controlsLayout)
            attached = true
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "overlay added: ${record?.packageName}/${record?.className}")
            }
            card.post {
                syncControlWindows()
            }
        } catch (e: Exception) {
            attached = false
            Log.w(TAG, "add overlay failed", e)
            hide()
        }
    }

    fun hide() {
        dockAnimator?.cancel()
        Log.d(TAG, "hide (wasShowing=$attached)")
        // id 位置标注跟随悬浮窗的可见性
        IdTagOverlay.hide()
        removeWindow(controlsView)
        removeWindow(dragTouchView)
        removeWindow(cardView)
        attached = false
    }

    fun isShowing(): Boolean = attached

    /**
     * 辅助功能服务重新连接后，旧 Service 上下文创建的窗口可能已经失效，
     * 彻底丢弃并重建。
     */
    fun reset() {
        dockAnimator?.cancel()
        hide()
        cardView = null
        cardParams = null
        packageView = null
        classView = null
        dragTouchView = null
        dragTouchParams = null
        controlsBinding = null
        controlsView = null
        controlsParams = null
        windowManager = null
        attached = false
        currentRecord = null
    }

    /** 屏幕旋转、分屏等配置变化后重新贴合左边缘并限制在屏幕内。 */
    fun clampToScreen() {
        syncControlWindows()
    }

    private fun ensureViews(context: Context) {
        if (cardView != null) return

        val services = context.getSystemService(Context.WINDOW_SERVICE)
        windowManager = services as? WindowManager ?: return

        dragTouchWpx = dp(context, DRAG_TOUCH_W_DP)
        dragTouchHpx = dp(context, DRAG_TOUCH_H_DP)
        touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
        collapsed = Prefs.isOverlayCollapsed(context)
        tagBtnEnabled = Prefs.showTagButton(context)

        val inflater = LayoutInflater.from(context)

        val card = ViewOverlayBinding.inflate(inflater).also { cardView = it.root }
        packageView = card.overlayPackage
        classView = card.overlayClass
        card.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 收起/弹出动画期间由动画帧自己更新位置，监听器不插手，
            // 否则“sync→relayout→sync”会互相打架并形成空转循环
            if (attached && dockAnimator?.isRunning != true) syncControlWindows()
        }

        val dragTouch = ViewOverlayDragtouchBinding.inflate(inflater).also { dragTouchView = it.root }
        setupDrag(dragTouch.root)

        val controls = ViewControlsBinding.inflate(inflater).also {
            controlsBinding = it
            controlsView = it.root
        }
        controls.overlayTagbtn.setOnClickListener {
            val active = MonitorAccessibilityService.instance?.toggleManualTags() ?: false
            setTagButtonActive(active)
        }
        setupArrow(controls.overlayArrow, context)

        cardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = cardY
            applyCutoutMode()
        }

        dragTouchParams = WindowManager.LayoutParams(
            dragTouchWpx,
            dragTouchHpx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 等首次布局后由 sync 挪到卡片底部中央
            x = 0
            y = cardY
            applyCutoutMode()
        }

        controlsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = cardY
            applyCutoutMode()
        }
    }

    private fun WindowManager.LayoutParams.applyCutoutMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
    }

    private fun applyState(context: Context) {
        val hasTarget = Prefs.targetPackage(context) != null
        val record = currentRecord

        packageView?.visibility = if (hasTarget) View.GONE else View.VISIBLE

        classView?.text = record?.displayClassText()
            ?: context.getString(R.string.overlay_waiting)

        val arrowIcon = controlsBinding?.overlayArrow
        // 图标基准朝左（靠左收起）；收起态旋转 180° 表示“向右展开”
        arrowIcon?.rotation = if (collapsed) 180f else 0f
        val description = context.getString(
            if (collapsed) R.string.overlay_expand else R.string.overlay_collapse
        )
        controlsBinding?.overlayArrow?.contentDescription = description
        arrowIcon?.contentDescription = description
    }

    private fun cardWidth(): Int {
        val card = cardView ?: return 0
        return card.width.takeIf { it > 0 } ?: card.measuredWidth
    }

    private fun cardHeight(): Int {
        val card = cardView ?: return 0
        return card.height.takeIf { it > 0 } ?: card.measuredHeight
    }

    /** controls 窗口目标 x：收起时贴屏幕左缘，展开时贴在卡片右缘外侧（留 4dp 间隙）。 */
    private fun controlsTargetX(): Int {
        if (collapsed) return 0
        val gap = cardView?.let { dp(it.context, CONTROLS_GAP_DP) } ?: 0
        return cardWidth() + gap
    }

    private fun controlsTargetY(): Int {
        val controls = controlsView ?: return cardY
        return cardY + (cardHeight() - controls.height) / 2
    }

    /** 触摸区目标位置：卡片底部水平居中，正好罩住卡片内部的指示线。 */
    private fun dragTouchTargetX(): Int = ((cardWidth() - dragTouchWpx) / 2).coerceAtLeast(0)

    /** 窗口叠在卡片底部 20dp 内，可见指示线（marginBottom 8dp）落在触摸区中间。 */
    private fun dragTouchTargetY(): Int = cardY + cardHeight() - dragTouchHpx

    /** 卡片内容平移量：收起时整体推出窗口左边界（窗口表面之外不可见）。 */
    private fun cardTargetTranslation(): Float = if (collapsed) -cardWidth().toFloat() else 0f

    /** 根据卡片实际宽高，同步拖拽触摸区和按钮组的位置到目标状态。 */
    private fun syncControlWindows() {
        if (!attached) return

        val wm = windowManager ?: return
        val card = cardView ?: return
        val dragTouch = dragTouchView ?: return
        val controls = controlsView ?: return
        val cardLayout = cardParams ?: return
        val dragTouchLayout = dragTouchParams ?: return
        val controlsLayout = controlsParams ?: return

        if (cardWidth() <= 0 || cardHeight() <= 0) return

        val dm = card.resources.displayMetrics
        val maxY = (dm.heightPixels - cardHeight()).coerceAtLeast(0)
        cardY = cardY.coerceIn(0, maxY)

        val newCardT = cardTargetTranslation()
        val newDragTouchVisible = if (collapsed) View.GONE else View.VISIBLE
        val newControlsX = controlsTargetX()
        val newControlsY = controlsTargetY()
        val newTagBtnVisible = if (tagBtnEnabled) View.VISIBLE else View.GONE

        if (cardLayout.y == cardY &&
            dragTouchLayout.x == dragTouchTargetX() && dragTouchLayout.y == dragTouchTargetY() &&
            controlsLayout.x == newControlsX && controlsLayout.y == newControlsY &&
            card.translationX == newCardT && dragTouch.visibility == newDragTouchVisible &&
            controlsBinding?.overlayTagbtn?.visibility == newTagBtnVisible
        ) {
            // 位置没有变化就不再 updateViewLayout：
            // 每次更新都会触发一次 relayout，进而再次进入本方法，形成空转循环
            return
        }

        cardLayout.y = cardY
        dragTouchLayout.x = dragTouchTargetX()
        dragTouchLayout.y = dragTouchTargetY()
        controlsLayout.x = newControlsX
        controlsLayout.y = newControlsY
        card.translationX = newCardT
        dragTouch.visibility = newDragTouchVisible
        controlsBinding?.overlayTagbtn?.visibility = newTagBtnVisible

        try {
            wm.updateViewLayout(card, cardLayout)
            wm.updateViewLayout(dragTouch, dragTouchLayout)
            wm.updateViewLayout(controls, controlsLayout)
        } catch (e: Exception) {
            Log.w(TAG, "sync updateViewLayout failed", e)
        }
    }

    /** 收起/弹出切换，卡片内容滑入滑出带动画，controls 沿卡片边缘滑动。 */
    private fun animateDock() {
        dockAnimator?.cancel()
        val wm = windowManager ?: return
        val card = cardView ?: return
        val cardLayout = cardParams ?: return
        val dragTouch = dragTouchView ?: return
        val dragTouchLayout = dragTouchParams ?: return
        val controls = controlsView ?: return
        val controlsLayout = controlsParams ?: return
        if (cardWidth() <= 0) {
            // 还没完成布局（例如刚添加就切换）：跳过动画直接到位
            Log.d(TAG, "animateDock skipped: width=0")
            syncControlWindows()
            return
        }

        val fromCardT = card.translationX
        val fromControlsX = controlsLayout.x
        // 目标在动画开始时一次性捕获：动画途中文本变化可能让宽度瞬时读成 0
        val toCardT = cardTargetTranslation()
        val toControlsX = controlsTargetX()
        if (!collapsed) dragTouch.visibility = View.VISIBLE
        Log.d(
            TAG,
            "animateDock to collapsed=$collapsed fromCardT=$fromCardT toCardT=$toCardT " +
                "toControlsX=$toControlsX",
        )

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOCK_ANIM_MS
            interpolator = if (collapsed) AccelerateInterpolator() else DecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                card.translationX = lerp(fromCardT, toCardT, t)
                controlsLayout.x = lerp(fromControlsX, toControlsX, t).toInt()
                runCatching {
                    wm.updateViewLayout(controls, controlsLayout)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (collapsed) dragTouch.visibility = View.GONE
                    if (attached) syncControlWindows()
                }
            })
        }
        dockAnimator = anim
        anim.start()
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).toInt()

    private fun toggleDocked(context: Context) {
        collapsed = !collapsed
        Prefs.setOverlayCollapsed(context, collapsed)
        applyState(context)
        Log.d(TAG, "toggleDocked -> collapsed=$collapsed")
        animateDock()
    }

    /**
     * 箭头按钮：点击切换收起/弹出；按住上下拖动（超过触摸判定距离才算拖动）。
     * 收起时它贴在屏幕左缘，拖动同样生效。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupArrow(view: View, context: Context) {
        var startY = 0
        var downRawY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = cardY
                    downRawY = event.rawY
                    dragging = false
                    dockAnimator?.cancel()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(event.rawY - downRawY) > touchSlopPx) {
                        dragging = true
                    }
                    if (dragging) {
                        val maxY = (view.resources.displayMetrics.heightPixels - cardHeight())
                            .coerceAtLeast(0)
                        cardY = (startY + (event.rawY - downRawY).toInt()).coerceIn(0, maxY)
                        syncControlWindows()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        Prefs.setOverlayPosition(view.context, 0, cardY)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        toggleDocked(context)
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 底部拖拽条：按住上下拖动。
     * - x 永远居中，不响应水平位移，卡片始终贴紧左边缘；
     * - 上下拖动即移动整张卡片。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(view: View) {
        var startY = 0
        var downRawY = 0f

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = cardY
                    downRawY = event.rawY
                    dockAnimator?.cancel()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val maxY = (view.resources.displayMetrics.heightPixels - cardHeight())
                        .coerceAtLeast(0)
                    cardY = (startY + (event.rawY - downRawY).toInt()).coerceIn(0, maxY)
                    syncControlWindows()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Prefs.setOverlayPosition(view.context, 0, cardY)
                    true
                }

                else -> false
            }
        }
    }

    private fun removeWindow(view: View?) {
        if (view == null) return
        try {
            if (view.isAttachedToWindow) {
                windowManager?.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            Log.w(TAG, "remove overlay window failed", e)
        }
    }

    /** id 快照按钮的激活态视觉：激活时换品牌色圆底。 */
    fun setTagButtonActive(active: Boolean) {
        controlsBinding?.overlayTagbtn?.setBackgroundResource(
            if (active) R.drawable.bg_overlay_tag_on else R.drawable.bg_overlay_arrow
        )
    }

    /** 「显示 id 快照按钮」开关变化：同步按钮显隐，关闭时一并隐藏激活态。 */
    fun setTagButtonEnabled(enabled: Boolean) {
        tagBtnEnabled = enabled
        if (!enabled) setTagButtonActive(false)
        syncControlWindows()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
