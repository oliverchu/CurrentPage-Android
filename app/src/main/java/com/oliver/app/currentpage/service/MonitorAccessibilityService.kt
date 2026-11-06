package com.oliver.app.currentpage.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import com.oliver.app.currentpage.BuildConfig
import com.oliver.app.currentpage.R
import com.oliver.app.currentpage.data.ActivityRecord
import com.oliver.app.currentpage.data.MonitorBus
import com.oliver.app.currentpage.data.MonitorState
import com.oliver.app.currentpage.data.Prefs
import com.oliver.app.currentpage.service.MonitorTileService
import com.oliver.app.currentpage.ui.overlay.IdTag
import com.oliver.app.currentpage.ui.overlay.IdTagOverlay
import com.oliver.app.currentpage.ui.overlay.OverlayController

/**
 * 核心：通过辅助功能监听窗口状态变化。
 *
 * 两种工作模式：
 * 1. 未选择调试应用：监测所有应用，悬浮窗跟随前台 Activity。
 * 2. 已选择调试应用：只有目标应用在前台时才显示悬浮窗并记录；切到其他应用立即隐藏并停止记录。
 *
 * Android 没有公开 API 能直接读取其他应用的前台 Activity，
 * 监听 TYPE_WINDOW_STATE_CHANGED 是目前最可靠的方式。
 */
class MonitorAccessibilityService : AccessibilityService() {

    private var imePackages: Set<String> = emptySet()
    private var screenReceiverRegistered = false

    /**
     * 最近一次"看起来像真正的窗口切换"事件的包名。
     * WINDOW_STATE_CHANGED 不只在前台切换时触发：Activity 销毁、对话框弹出/关闭
     * 都会产生事件，且事件里的 className 可能是根布局（FrameLayout 等）。
     * 只有 Activity 级、非自身、非对话框的事件才更新它，作为前台包名的备用判断。
     */
    private var trackedForegroundPackage: String? = null

    /**
     * 当前弹层记录对应的窗口 id。
     * BottomSheet/Dialog 打开后，底层 Activity 的窗口状态会持续抖动（变暗、可见性
     * 变化），产生同包名的 Activity 事件；只要弹层窗口还在，这些事件一律忽略，
     * 否则弹层记录被覆盖，表现为“卡片文案来回跳”。
     */
    private var sheetWindowId: Int? = null

    /** 当前悬浮窗正在显示的记录，用于判断弹层是否仍在前台。 */
    private var shownRecord: ActivityRecord? = null

    /** 同一窗口的遍历节流：短时间内重复事件直接复用上次结果。 */
    private var lastTraverseWindowId = -1
    private var lastTraverseAt = 0L
    private var lastTraverseInfo: ViewTreeInfo? = null

    /**
     * 手动快照模式：悬浮窗上的 id 快照按钮触发，对当前页面拍一次 id 标注并冻结
     * （不跟随滚动、不被事件刷新），再点一次清除。
     */
    private var manualTags: List<IdTag>? = null
    private var manualTagsFor: ActivityRecord? = null

    /** 弹层滑入动画结束后再遍历视图树（事件到达时视图还在屏幕外的动画起点）。 */
    private var pendingTraverseWindowId = -1
    private var pendingTraversePackage: String? = null
    private var pendingTraverseClassName: String? = null
    private var pendingTraverseGeneric = false
    private val traverseRunnable = Runnable {
        val windowId = pendingTraverseWindowId
        val pkg = pendingTraversePackage
        val cls = pendingTraverseClassName
        val generic = pendingTraverseGeneric
        pendingTraverseWindowId = -1
        pendingTraversePackage = null
        pendingTraverseClassName = null
        pendingTraverseGeneric = false
        if (windowId == -1 || pkg == null || cls == null) return@Runnable

        refineWindow(windowId, pkg, cls, generic)
        if (!generic) {
            // Activity 页面底部的面板入场更慢，第一轮可能在屏幕外：
            // 稍后再补一轮，拿到面板就位后的真实位置
            mainHandler.postDelayed({
                refineWindow(windowId, pkg, cls, generic = false)
            }, REFINE_SECOND_PASS_MS)
        }
    }

    private fun refineWindow(windowId: Int, pkg: String, cls: String, generic: Boolean) {
        if (!Prefs.isMonitoring(this)) return
        val info = findSpecificViewInfo(windowId, pkg, allowFallback = false) ?: return

        if (generic) {
            // 泛型根布局窗口：遍历出具体类或 id 才有展示价值，否则维持噪音过滤
            if (info.viewClass == null && info.idTags.isEmpty()) return
            val record = ActivityRecord(
                packageName = pkg,
                className = cls,
                timestamp = System.currentTimeMillis(),
                viewClass = info.viewClass,
                viewIds = info.viewIds,
            )
            Prefs.saveRecord(this, record)
            MonitorBus.notifyActivityChanged(record)
            showOverlayWithTags(record)
            return
        }

        // 弹层 / Activity：类名不变，补充 viewClass（仅弹层）
        val shown = shownRecord
        if (shown == null || shown.packageName != pkg || shown.className != cls) return
        val refined = if (isDialogLikeClass(cls)) {
            shown.copy(viewClass = info.viewClass, viewIds = info.viewIds)
        } else {
            shown.copy(viewIds = info.viewIds)
        }
        showOverlayWithTags(refined)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideScheduled = false

    /**
     * 非目标应用出现时不要立刻隐藏：有些应用会短暂弹出一个其他包的窗口
     * （例如分享面板、第三方登录页），立刻隐藏会造成悬浮窗闪烁。
     * 延迟一小段时间，如果目标应用在这期间回到前台就取消隐藏。
     */
    private val hideOverlayRunnable = Runnable {
        hideScheduled = false

        // 隐藏前再确认一次：如果目标应用其实已经回到前台，就继续显示
        val target = Prefs.targetPackage(this)
        val active = currentActivePackage()
        if (target != null && active == target) {
            dbg("graceEnd keep-show: active=$active")
            MonitorState.setTargetVisible(true)
            Prefs.lastRecord(this)?.let { showOverlayWithTags(it) }
            return@Runnable
        }

        dbg("graceEnd hide: active=$active")
        OverlayController.hide()
        updateTargetVisible(false)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 记录熄屏前是否正在显示，解锁后只在原本显示的情况下恢复
                    MonitorState.setOverlayWasVisibleBeforeScreenOff(OverlayController.isShowing())
                    OverlayController.hide()
                }

                Intent.ACTION_USER_PRESENT -> {
                    if (MonitorState.overlayWasVisibleBeforeScreenOff) {
                        maybeRestoreOverlay()
                    }
                    MonitorState.setOverlayWasVisibleBeforeScreenOff(false)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected: $this")
        instance = this

        imePackages = runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.map { it.packageName }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

        registerScreenReceiver()
        cancelNonTargetHide()

        // 服务重绑后必须重建悬浮窗，旧 Service 上下文创建的窗口可能已经失效
        OverlayController.reset()
        OverlayController.setTagButtonEnabled(Prefs.showTagButton(this))

        if (Prefs.isMonitoring(this)) {
            // 服务被 MIUI 等系统重新绑定后，如果当前前台仍是上次记录的应用，立即恢复悬浮窗
            maybeRestoreOverlay()
        } else {
            OverlayController.hide()
            updateTargetVisible(false)
        }

        MonitorBus.notifyServiceStateChanged()
        MonitorTileService.requestUpdate(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() }
        val className = event.className?.toString().orEmpty()

        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            maybeClearClosedSheetTags()
            maybeHealTargetOverlay()
            return
        }
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        if (packageName == null) return
        if (className.isBlank()) return

        // 系统 UI、权限弹窗、输入法：不改变目标应用的可见性，也不污染记录
        if (packageName in IGNORED_PACKAGES) return
        val target = Prefs.targetPackage(this)
        if (packageName in imePackages && packageName != target) return
        if (isWindowSwitchLike(className)) {
            trackedForegroundPackage = packageName
        }

        if (!Prefs.isMonitoring(this)) {
            cancelNonTargetHide()
            OverlayController.hide()
            updateTargetVisible(false)
            return
        }

        // 弹层还开着时，忽略同应用的底层 Activity 窗口抖动事件
        val shown = shownRecord
        if (shown != null && isDialogLikeClass(shown.className)) {
            val remembered = sheetWindowId
            val windows = runCatching { getWindows() }.getOrNull().orEmpty()
            val winIds = windows.map { it.id }
            releaseWindowInfos(windows)
            val sheetStillOpen = remembered != null && remembered in winIds
            if (sheetStillOpen && packageName == shown.packageName) {
                dbg("sheet still open (win=$remembered) -> ignore $className")
                return
            }
            if (remembered != null && !sheetStillOpen) {
                dbg("sheet window $remembered gone (wins=$winIds)")
            }
            sheetWindowId = null
        }

        if (target != null && packageName != target) {
            // 目标应用暂时不在前台：延迟隐藏，避免分享面板等短暂窗口导致闪烁。
            // 但只有“Activity 级”的窗口切换才可信：自己窗口的销毁事件、
            // 对话框、根布局事件都不代表前台应用变了，否则悬浮窗会被误杀闪烁。
            if (isWindowSwitchLike(className)) {
                dbg("non-target: $packageName -> scheduleHide(showing=${OverlayController.isShowing()})")
                scheduleNonTargetHide()
            }
            return
        }

        cancelNonTargetHide()

        val dialogLike = isDialogLikeClass(className)
        val genericView = isGenericViewClass(className)

        if (dialogLike) sheetWindowId = event.windowId else sheetWindowId = null

        // BottomSheet 滑入、Activity 转场期间视图边界都在动画位置上：
        // 统一延迟到动画结束后再遍历视图树，采集全部布局 id 和更具体的类名
        pendingTraverseWindowId = event.windowId
        pendingTraversePackage = packageName
        pendingTraverseClassName = className
        pendingTraverseGeneric = genericView
        mainHandler.removeCallbacks(traverseRunnable)
        mainHandler.postDelayed(traverseRunnable, TRAVERSE_DELAY_MS)

        if (genericView) {
            // 泛型根布局窗口大概率是噪音：等遍历结果，有内容才展示
            return
        }

        // 自己的界面不作为“当前页面”记录，避免污染最近捕获与历史
        if (packageName == this.packageName) {
            dbg("self-package -> hide")
            OverlayController.hide()
            return
        }

        val record = ActivityRecord(
            packageName = packageName,
            className = className,
            timestamp = System.currentTimeMillis(),
        )
        val last = Prefs.lastRecord(this)
        if (last != null && last.key == record.key) {
            if (target != null) {
                updateTargetVisible(true)
                showOverlayWithTags(record)
            }
            return
        }

        Prefs.saveRecord(this, record)
        MonitorBus.notifyActivityChanged(record)

        if (target != null) {
            updateTargetVisible(true)
        }

        dbg("target event -> show ($record)")
        showOverlayWithTags(record)
        MonitorTileService.requestUpdate(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/分屏后把悬浮窗拉回可见区域
        OverlayController.clampToScreen()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        cancelNonTargetHide()
        mainHandler.removeCallbacks(traverseRunnable)
        pendingTraverseWindowId = -1
        pendingTraversePackage = null
        pendingTraverseClassName = null
        pendingTraverseGeneric = false
        OverlayController.reset()
        IdTagOverlay.reset()
        sheetWindowId = null
        shownRecord = null
        manualTags = null
        manualTagsFor = null
        OverlayController.setTagButtonActive(false)
        updateTargetVisible(false)
        MonitorState.setOverlayWasVisibleBeforeScreenOff(false)
        unregisterScreenReceiver()
        if (instance === this) {
            instance = null
        }
        MonitorBus.notifyServiceStateChanged()
        MonitorTileService.requestUpdate(this)
    }

    /**
     * 只在“当前真正激活的窗口”确实是上次记录的应用时恢复悬浮窗，
     * 避免服务重绑/解锁后把旧信息错误地显示在桌面或其他应用上。
     */
    private fun maybeRestoreOverlay() {
        if (!Prefs.isMonitoring(this) || MonitorState.mainVisible) return
        cancelNonTargetHide()

        val last = Prefs.lastRecord(this) ?: return
        val target = Prefs.targetPackage(this)
        if (target != null && last.packageName != target) return
        if (currentActivePackage() != last.packageName) return

        if (target != null) {
            MonitorState.setTargetVisible(true)
        }
        showOverlayWithTags(last)
    }

    private fun scheduleNonTargetHide() {
        if (!OverlayController.isShowing() && !MonitorState.targetVisible) return
        if (hideScheduled) return
        hideScheduled = true
        mainHandler.postDelayed(hideOverlayRunnable, NON_TARGET_GRACE_MS)
    }

    private fun cancelNonTargetHide() {
        if (!hideScheduled) return
        mainHandler.removeCallbacks(hideOverlayRunnable)
        hideScheduled = false
    }

    private fun currentActivePackage(): String? {
        // 以系统当前激活窗口为准；查询失败时退回事件流维护的前台包名
        runCatching {
            rootInActiveWindow?.packageName?.toString()
        }.getOrNull()?.let { return it }
        return trackedForegroundPackage
    }

    /**
     * 该事件是否“像一次真正的窗口切换”：
     * - 自己应用的窗口事件永远不算（前台监控语义里自己永远是观察者）；
     * - 对话框 / Popup / 根布局类名不算——它们是窗口增删事件，
     *   真正的前台切换事件携带的是目标 Activity 的类名。
     */
    private fun isWindowSwitchLike(className: String): Boolean {
        if (className.isBlank()) return false
        if (isDialogLikeClass(className)) return false
        if (isGenericViewClass(className)) return false
        return true
    }

    /**
     * 目标应用模式下，如果因为某些窗口切换导致悬浮窗被隐藏，
     * 但当前激活窗口其实仍是目标应用，就在窗口变化事件里自动恢复。
     */
    private fun maybeHealTargetOverlay() {
        if (!Prefs.isMonitoring(this)) return
        if (OverlayController.isShowing()) return
        if (MonitorState.mainVisible) return

        val target = Prefs.targetPackage(this) ?: return
        val active = currentActivePackage() ?: return
        if (active != target) return

        val last = Prefs.lastRecord(this) ?: return
        if (last.packageName != target) return

        dbg("heal: active=$active -> show last=${last.displayClass()}")
        cancelNonTargetHide()
        MonitorState.setTargetVisible(true)
        showOverlayWithTags(last)
    }

    private fun updateTargetVisible(visible: Boolean) {
        if (MonitorState.targetVisible == visible) return
        MonitorState.setTargetVisible(visible)
        MonitorTileService.requestUpdate(this)
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // 已经注销
        } finally {
            screenReceiverRegistered = false
        }
    }

    @Suppress("DEPRECATION") // 同 traverseTreeForIds：低版本需要回收窗口信息
    private fun releaseWindowInfos(windows: List<AccessibilityWindowInfo>?) {
        if (windows == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        windows.forEach { runCatching { it.recycle() } }
    }

    private fun isDialogLikeClass(className: String): Boolean {
        val simpleName = className.substringAfterLast('.')
        return simpleName.endsWith("Dialog") ||
            simpleName.contains("BottomSheet", ignoreCase = true) ||
            simpleName.endsWith("PopupWindow") ||
            className == "android.app.Presentation"
    }

    private fun isGenericViewClass(className: String): Boolean {
        if (!className.contains('.')) return true
        return FRAMEWORK_WINDOW_PREFIXES.any { className.startsWith(it) }
    }

    private fun isSurfaceClass(className: String): Boolean =
        className.endsWith("SurfaceView")

    /**
     * 从当前激活窗口的视图树里找最具体的类，并收集带 android:id 的节点资源名：
     * 1. 应用自己的类里，优先带 Dialog/Sheet/Popup/Window 关键字的；
     * 2. 其次任意应用自己的 View/Layout 类；
     * 3. 再次 Material BottomSheet 的内部类；
     * 4. 最后是 ComposeView（Compose 弹层没有可读的组件类名）。
     *
     * viewId 只收“目标应用自己的 id”（如 changlong:id/sheet_root），
     * 用于在没有自定义类名时间接定位是哪个布局/弹层。
     * 需要 flagReportViewIds（见 accessibility_service_config.xml），否则
     * node.viewIdResourceName 恒为 null。
     */
    private fun findSpecificViewInfo(
        windowId: Int,
        ownerPackage: String,
        allowFallback: Boolean,

    ): ViewTreeInfo? {
        // 同一窗口短时间内重复事件（底层 Activity 抖动）直接复用上次遍历结果；
        val now = SystemClock.elapsedRealtime()
        if (windowId == lastTraverseWindowId &&
            now - lastTraverseAt < TRAVERSE_THROTTLE_MS
        ) {
            return lastTraverseInfo
        }
        // 优先按事件 windowId 精确取窗口；getWindows() 需要
        // flagRetrieveInteractiveWindows + canRetrieveWindowContent。
        // allowFallback=false 时窗口不存在（弹层已关闭）就放弃，避免错遍历别的窗口
        val windows = runCatching { getWindows() }.getOrNull()
        val root = runCatching {
            windows?.firstOrNull { it.id == windowId }?.root
        }.getOrNull()
        releaseWindowInfos(windows)
        val activeRoot = root
            ?: (if (allowFallback) runCatching { rootInActiveWindow }.getOrNull() else null)
            ?: return null
        val info = traverseTreeForIds(activeRoot, ownerPackage)
        lastTraverseWindowId = windowId
        lastTraverseAt = now
        lastTraverseInfo = info
        return info
    }

    /** 遍历视图树：收集窗口里所有带应用自身 android:id 的节点位置和更具体的类名。 */
    @Suppress("DEPRECATION") // API 33 起 recycle() 已废弃，低版本仍需主动回收节点
    private fun traverseTreeForIds(
        root: AccessibilityNodeInfo,
        ownerPackage: String,
    ): ViewTreeInfo {

        val idPrefix = "$ownerPackage:id/"
        val idTags = ArrayList<IdTag>()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var visited = 0
        var preferredAppClass: String? = null
        var appClass: String? = null
        var bottomSheetClass: String? = null
        var composeClass: String? = null

        while (queue.isNotEmpty() && visited < MAX_VIEW_NODES) {
            val node = queue.removeFirst()
            visited++

            val viewId = runCatching { node.viewIdResourceName }.getOrNull()
            if (viewId != null && viewId.startsWith(idPrefix)) {
                val bounds = Rect()
                if (runCatching { node.getBoundsInScreen(bounds) }.isSuccess) {
                    idTags.add(IdTag(viewId, bounds))
                }
            }

            val cls = runCatching { node.className?.toString() }.getOrNull().orEmpty()
            if (cls.isNotBlank()) {
                if (isAppSpecificClass(cls)) {
                    if (appClass == null) appClass = cls
                    if (preferredAppClass == null) {
                        val simple = cls.substringAfterLast('.')
                        if (simple.contains("Dialog", true) ||
                            simple.contains("Sheet", true) ||
                            simple.contains("Popup", true) ||
                            simple.contains("Window", true)
                        ) {
                            preferredAppClass = cls
                        }
                    }
                } else if (bottomSheetClass == null &&
                    cls.contains("BottomSheet", ignoreCase = true)
                ) {
                    bottomSheetClass = cls
                } else if (composeClass == null &&
                    cls.startsWith("androidx.compose.ui.platform.")
                ) {
                    composeClass = cls
                }
            }

            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let { queue.add(it) }
            }
            // API 33 起 recycle 已废弃（无操作）；老系统上不回收会耗尽节点池
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                runCatching { node.recycle() }
            }
        }

        val info = ViewTreeInfo(
            viewClass = preferredAppClass ?: appClass ?: bottomSheetClass ?: composeClass,
            idTags = idTags,
            visited = visited,
        )
        return info
    }

    private fun isAppSpecificClass(className: String): Boolean {
        if (className.startsWith("android.") || className.startsWith("androidx.")) return false
        if (className.startsWith("com.android.")) return false
        if (className.startsWith("com.google.android.material.")) return false
        if (className.startsWith("com.miui.") || className.startsWith("miui.")) return false
        if (className.startsWith("com.qualcomm.") || className.startsWith("vendor.")) return false
        return true
    }

    private class ViewTreeInfo(
        val viewClass: String?,
        val idTags: List<IdTag>,
        val visited: Int,
    ) {
        val viewIds: List<String>
            get() = idTags.map { it.fullId }
    }

    /** 悬浮窗 id 标注的统一入口：只显示手动快照（如果有的话）。 */
    private fun updateTags() {
        IdTagOverlay.update(this, manualTags ?: emptyList())
    }

    /** 悬浮窗上的 id 快照按钮：点一下拍当前页面，再点一下清除。返回新的激活态。 */
    fun toggleManualTags(): Boolean {
        if (manualTags != null) {
            manualTags = null
            manualTagsFor = null
            updateTags()
            OverlayController.setTagButtonActive(false)
            return false
        }
        // 点击时浮在最上面的就是用户正在看的页面，直接拍当前激活窗口
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null) return false
        val info = traverseTreeForIds(root, pkg)
        dbg("manual snapshot: visited=${info.visited} ids=${info.idTags.size}")
        if (info.idTags.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_ids, Toast.LENGTH_SHORT).show()
            return false
        }
        manualTags = info.idTags
        manualTagsFor = shownRecord ?: ActivityRecord(pkg, "", 0L)
        updateTags()
        OverlayController.setTagButtonActive(true)
        return true
    }

    /** 关闭「显示 id 快照按钮」时同步清掉手动快照。 */
    fun clearManualTags() {
        if (manualTags == null) return
        manualTags = null
        manualTagsFor = null
        updateTags()
        OverlayController.setTagButtonActive(false)
    }

    /** 显示悬浮窗并同步 id 位置标注（位置标注只跟随最近一次遍历结果）。 */
    private fun showOverlayWithTags(record: ActivityRecord) {
        shownRecord = record
        // 换了页面/弹层，之前的手动快照位置就失效了，同时解除挂起
        val captured = manualTagsFor
        if (captured != null &&
            (captured.packageName != record.packageName || captured.className != record.className)
        ) {
            manualTags = null
            manualTagsFor = null
            OverlayController.setTagButtonActive(false)
        }
        OverlayController.show(this, record)
        updateTags()
    }

    /**
     * 弹窗关闭时往往只发 WINDOWS_CHANGED 事件（没有 Activity 事件来清标注）：
     * 发现弹层窗口已从窗口列表消失，就清掉残留在页面上的 id 标注。
     */
    private fun maybeClearClosedSheetTags() {
        val remembered = sheetWindowId ?: return
        val windows = runCatching { getWindows() }.getOrNull() ?: return
        val stillOpen = windows.any { it.id == remembered }
        releaseWindowInfos(windows)
        if (stillOpen) return
        dbg("sheet window $remembered closed -> clear id tags")
        sheetWindowId = null
        if (manualTags != null) {
            manualTags = null
            manualTagsFor = null
            OverlayController.setTagButtonActive(false)
        }
        updateTags()
    }

    companion object {
        private const val TAG = "CurrentPageService"
        private const val MAX_VIEW_NODES = 800
        private const val NON_TARGET_GRACE_MS = 800L
        private const val TRAVERSE_DELAY_MS = 500L
        private const val TRAVERSE_THROTTLE_MS = 400L
        private const val REFINE_SECOND_PASS_MS = 1500L

        private fun dbg(message: String) {
            if (BuildConfig.DEBUG) Log.d("CurrentPageDebug", message)
        }

        @Volatile
        var instance: MonitorAccessibilityService? = null
            private set

        /** 这些窗口出现时不改变“目标应用是否在前台”的判断。 */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.intentresolver",
            "com.google.android.intentresolver",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            // MIUI / HyperOS 的权限、分享、系统弹窗
            "com.lbe.security.miui",
            "com.miui.securitycenter",
            "com.miui.securitymanager",
            "com.miui.permcenter",
            "com.miui.thirdappassistant",
            "com.miui.contentcatcher",
        )

        private val FRAMEWORK_WINDOW_PREFIXES = listOf(
            "android.widget.",
            "android.view.",
            "android.webkit.",
            "android.app.",
            "com.android.internal.",
        )
    }
}
