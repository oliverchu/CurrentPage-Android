package com.oliver.app.currentpage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.oliver.app.currentpage.R
import com.oliver.app.currentpage.data.ActivityRecord
import com.oliver.app.currentpage.data.MonitorState
import com.oliver.app.currentpage.databinding.ActivityMainBinding
import com.oliver.app.currentpage.databinding.DialogAppPickerBinding
import com.oliver.app.currentpage.databinding.ItemAppPickerBinding
import com.oliver.app.currentpage.util.AccessibilityUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 主界面：只负责渲染 [MainViewModel] 产出的 [MainUiState] 和转发交互，
 * 状态合成与数据读取都在 ViewModel 里（MVVM）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private var suppressSwitchCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        with(binding) {
            switchMonitor.setOnCheckedChangeListener { _, checked ->
                if (!suppressSwitchCallbacks) onMonitorToggled(checked)
            }
            switchTagButton.isChecked = viewModel.uiState.value?.showTagButton ?: false
            switchTagButton.setOnCheckedChangeListener { _, checked ->
                viewModel.setShowTagButton(checked)
            }
            buttonAccessibilitySettings.setOnClickListener { handleAccessibilityButton() }
            buttonPickTarget.setOnClickListener { showAppPicker() }
            buttonClearTarget.setOnClickListener { clearTarget() }
            buttonCopyActivity.setOnClickListener { copyCurrentActivity() }
            buttonBatteryPolicy.setOnClickListener { openBatteryPolicy() }
            buttonAutostart.setOnClickListener { openAutostart() }
            buttonClearHistory.setOnClickListener { viewModel.clearHistory() }
        }

        viewModel.uiState.observe(this) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        MonitorState.setMainVisible(true)
        viewModel.refresh()
    }

    override fun onPause() {
        MonitorState.setMainVisible(false)
        super.onPause()
    }

    private fun render(state: MainUiState) {
        with(binding) {
            val record = state.record
            textCurrentPackage.text = record?.packageName ?: getString(R.string.current_none)
            textCurrentClass.text = record?.displayClassText().orEmpty()
            textLastSeen.text = if (record != null) {
                getString(R.string.last_seen, ActivityRecord.formatTime(record.timestamp))
            } else {
                getString(R.string.current_hint)
            }

            textAccessibilityState.text = getString(
                when (state.accessibilityState) {
                    AccessibilityUtils.State.CONNECTED -> R.string.accessibility_state_connected
                    AccessibilityUtils.State.ENABLED_NOT_CONNECTED ->
                        R.string.accessibility_state_enabled_not_connected
                    AccessibilityUtils.State.DISABLED -> R.string.accessibility_state_disabled
                }
            )
            buttonAccessibilitySettings.text = getString(
                when (state.accessibilityState) {
                    AccessibilityUtils.State.CONNECTED -> R.string.open_settings
                    AccessibilityUtils.State.ENABLED_NOT_CONNECTED -> R.string.button_reconnect
                    AccessibilityUtils.State.DISABLED -> R.string.enable_now
                }
            )

            val target = state.targetPackage
            if (target == null) {
                imageTargetIcon.visibility = View.GONE
                textTargetApp.text = getString(R.string.target_none)
                textTargetPackage.text = ""
            } else {
                imageTargetIcon.visibility = View.VISIBLE
                imageTargetIcon.setImageDrawable(
                    runCatching { packageManager.getApplicationIcon(target) }.getOrNull()
                        ?: ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.ic_shortcut_monitor
                        )
                )
                textTargetApp.text = state.targetLabel
                textTargetPackage.text = target
            }
            buttonClearTarget.isEnabled = target != null

            textMonitorState.text = when {
                !state.monitoring -> getString(R.string.monitor_state_off)
                state.accessibilityState != AccessibilityUtils.State.CONNECTED ->
                    getString(R.string.monitor_state_no_connection)
                target == null -> getString(R.string.monitor_state_all)
                state.targetVisible -> getString(R.string.monitor_state_target_active)
                else -> getString(R.string.monitor_state_waiting_target)
            }

            syncSwitch(switchMonitor, state.monitoring)
            syncSwitch(switchTagButton, state.showTagButton)
            buttonCopyActivity.isEnabled = record != null

            // HyperOS 保活状态：省电策略可检测；自启动无法读取，仅提示
            textBatteryStatus.text = getString(
                if (state.batteryUnrestricted) {
                    R.string.keepalive_battery_ok
                } else {
                    R.string.keepalive_battery_bad
                }
            )
            textBatteryStatus.setTextColor(
                if (state.batteryUnrestricted) {
                    ContextCompat.getColor(this@MainActivity, R.color.brand_secondary)
                } else {
                    ContextCompat.getColor(this@MainActivity, R.color.brand_primary)
                }
            )

            textHistory.text = if (state.history.isEmpty()) {
                getString(R.string.history_empty)
            } else {
                state.history.joinToString("\n")
            }
        }
    }

    private fun onMonitorToggled(checked: Boolean) {
        if (!checked) {
            viewModel.setMonitoring(false)
            return
        }

        when (viewModel.uiState.value?.accessibilityState) {
            AccessibilityUtils.State.CONNECTED -> viewModel.setMonitoring(true)
            AccessibilityUtils.State.ENABLED_NOT_CONNECTED -> {
                syncSwitch(binding.switchMonitor, false)
                showReconnectDialog()
            }
            AccessibilityUtils.State.DISABLED -> {
                syncSwitch(binding.switchMonitor, false)
                showAccessibilityDialog()
            }
            null -> Unit
        }
    }

    private fun handleAccessibilityButton() {
        if (viewModel.uiState.value?.accessibilityState ==
            AccessibilityUtils.State.ENABLED_NOT_CONNECTED
        ) {
            showReconnectDialog()
        } else {
            AccessibilityUtils.openSettings(this)
        }
    }

    private fun showAccessibilityDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_accessibility_title)
            .setMessage(R.string.dialog_accessibility_message)
            .setNegativeButton(R.string.dialog_accessibility_negative, null)
            .setPositiveButton(R.string.dialog_accessibility_positive) { _, _ ->
                viewModel.setMonitoringFlag(true)
                AccessibilityUtils.openSettings(this)
            }
            .show()
    }

    private fun showReconnectDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_reconnect_title)
            .setMessage(R.string.dialog_reconnect_message)
            .setNegativeButton(R.string.dialog_accessibility_negative, null)
            .setPositiveButton(R.string.dialog_accessibility_positive) { _, _ ->
                viewModel.setMonitoringFlag(true)
                AccessibilityUtils.openSettings(this)
            }
            .show()
    }

    private fun showAppPicker() {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launchIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launchIntent, 0)
        }

        val collator = Collator.getInstance(Locale.getDefault())
        val allApps = resolved.asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    info = info,
                )
            }
            .sortedWith(Comparator<AppEntry> { a, b -> collator.compare(a.label, b.label) })
            .toList()

        if (allApps.isEmpty()) {
            Toast.makeText(this, R.string.target_pick_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val pickerBinding = DialogAppPickerBinding.inflate(layoutInflater)
        val adapter = AppPickerAdapter(this, pm, allApps)

        pickerBinding.listApps.adapter = adapter
        pickerBinding.listApps.emptyView = pickerBinding.textEmpty
        pickerBinding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(pickerBinding.root)
            .setNegativeButton(R.string.dialog_accessibility_negative, null)
            .create()

        pickerBinding.listApps.setOnItemClickListener { _, _, position, _ ->
            selectTarget(adapter.getItem(position).packageName)
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            pickerBinding.inputSearch.requestFocus()
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
        }
        dialog.setOnDismissListener { adapter.shutdown() }
        dialog.show()
    }

    private fun selectTarget(target: String) {
        viewModel.setTargetPackage(target)
        Toast.makeText(
            this,
            getString(
                R.string.target_selected_toast,
                viewModel.uiState.value?.targetLabel ?: target
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun clearTarget() {
        viewModel.setTargetPackage(null)
    }

    private fun copyCurrentActivity() {
        val record = viewModel.uiState.value?.record ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = buildString {
            append(record.packageName).append('\n').append(record.className)
            record.viewClass?.takeIf { it.isNotBlank() && it != record.className }?.let {
                append('\n').append(it)
            }
        }
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.app_name), text)
        )
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * HyperOS 3 没有公开的“省电策略”独立入口，
     * 应用详情页里就有「省电策略」这一项，跳过去让用户选「无限制」。
     */
    private fun openBatteryPolicy() {
        Toast.makeText(this, R.string.keepalive_battery_hint, Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** MIUI/HyperOS 的自启动管理页；非 MIUI ROM 上退回应用详情页。 */
    private fun openAutostart() {
        val autoStart = Intent()
            .setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(autoStart)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.keepalive_autostart_failed, Toast.LENGTH_SHORT).show()
            openBatteryPolicy()
        }
    }

    private fun syncSwitch(button: MaterialSwitch, checked: Boolean) {
        if (button.isChecked == checked) return
        suppressSwitchCallbacks = true
        button.isChecked = checked
        suppressSwitchCallbacks = false
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private class AppEntry(
        val packageName: String,
        val label: String,
        val info: android.content.pm.ApplicationInfo,
    ) {
        var icon: Drawable? = null
    }

    private class AppPickerAdapter(
        private val context: Context,
        private val pm: PackageManager,
        private val allApps: List<AppEntry>,
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(context)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val iconExecutor = Executors.newFixedThreadPool(2)
        private var items: List<AppEntry> = allApps

        @Volatile
        private var closed = false

        fun shutdown() {
            closed = true
            iconExecutor.shutdownNow()
        }

        fun filter(query: String) {
            val q = query.trim()
            items = if (q.isEmpty()) {
                allApps
            } else {
                allApps.filter {
                    it.label.contains(q, ignoreCase = true) ||
                        it.packageName.contains(q, ignoreCase = true)
                }
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): AppEntry = items[position]

        override fun getItemId(position: Int): Long =
            items[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val binding = (convertView?.tag as? ItemAppPickerBinding)
                ?: ItemAppPickerBinding.inflate(inflater, parent, false)
                .also { it.root.tag = it }
            val app = items[position]
            binding.itemLabel.text = app.label
            binding.itemPackage.text = app.packageName

            val cached = app.icon
            if (cached != null) {
                binding.itemIcon.setImageDrawable(cached)
            } else {
                // 主线程不加载图标：先占位，后台加载完成后再按包名校验回填，
                // 避免 convertView 复用到别的行时贴错图标
                binding.itemIcon.setImageDrawable(pm.defaultActivityIcon)
                binding.itemIcon.tag = app.packageName
                iconExecutor.execute {
                    val icon = runCatching { app.info.loadIcon(pm) }.getOrNull()
                    if (icon == null) return@execute
                    mainHandler.post {
                        app.icon = icon
                        if (!closed && binding.itemIcon.tag == app.packageName) {
                            binding.itemIcon.setImageDrawable(icon)
                        }
                    }
                }
            }
            return binding.root
        }
    }

}
