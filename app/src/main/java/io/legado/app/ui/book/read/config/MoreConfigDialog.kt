package io.legado.app.ui.book.read.config

import io.legado.app.ui.book.read.page.delegate.IReaderPageH
import io.legado.app.ui.book.read.page.delegate.InkFastConfig
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import io.legado.app.utils.putPrefString
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor

class MoreConfigDialog : BasePrefDialogFragment() {
    private val readPreferTag = "readPreferenceFragment"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 360.dpToPx())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as ReadBookActivity).bottomDialog++
        val view = LinearLayout(context)
        view.setBackgroundColor(requireContext().bottomBackground)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readPreferTag)
            .commit()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    class ReadPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val slopSquare by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }

        /** 检测Root权限 */
private fun hasRoot(): Boolean {
    return runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        process.waitFor()
        process.exitValue() == 0
    }.getOrDefault(false)
}

/** 获取芯片平台 ro.board.platform */
private fun getBoardPlatform(): String {
    return runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("getprop", "ro.board.platform"))
        val output = proc.inputStream.bufferedReader().readLine() ?: ""
        proc.waitFor()
        output.trim().lowercase()
    }.getOrDefault("")
}

/** 判断是否瑞芯微RK平台 */
private fun isRkEinkBoard(platform: String): Boolean {
    return platform.startsWith("rk35") || platform.startsWith("rk33")
}

@SuppressLint("RestrictedApi")
override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    addPreferencesFromResource(R.xml.pref_config_read)
    val screen: PreferenceScreen = preferenceScreen

    // 掌阅水波纹选项（不改动 arrays.xml，代码内设置）
    findPreference<ListPreference>("ink_ripple")?.apply {
        entries = arrayOf("关闭", "慢速", "中速", "快速")
        entryValues = arrayOf("off", "slow", "medium", "fast")
    }
    // 仅掌阅固件（EPDCDevice 可反射加载）时显示，否则隐藏
    if (!IReaderPageH.probe()) {
        screen.removePreferenceRecursively("ink_ripple")
    }

    // 双重条件：必须Root + RK瑞芯微平台才显示墨水屏设置
    val rootOk = hasRoot()
    val platformStr = getBoardPlatform()
    val showEinkItem = rootOk && isRkEinkBoard(platformStr)
    // 不满足条件则隐藏两项墨水屏配置
    if (!showEinkItem) {
        screen.removePreferenceRecursively("ink_fast_turn")
        screen.removePreferenceRecursively("eink_mode")
    }

    upPreferenceSummary(PreferKey.pageTouchSlop, slopSquare.toString())
    if (!CanvasRecorderFactory.isSupport) {
        removePref(PreferKey.optimizeRender)
        preferenceScreen.removePreferenceRecursively(PreferKey.optimizeRender)
    }
}

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager
                .sharedPreferences
                ?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager
                .sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readBodyToLh -> activity?.recreate()
                PreferKey.hideStatusBar -> {
                    ReadBookConfig.hideStatusBar = getPrefBoolean(PreferKey.hideStatusBar)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }
                PreferKey.hideNavigationBar -> {
                    ReadBookConfig.hideNavigationBar = getPrefBoolean(PreferKey.hideNavigationBar)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }
                PreferKey.keepLight -> postEvent(key, true)
                PreferKey.textSelectAble -> postEvent(key, getPrefBoolean(key))
                PreferKey.screenOrientation -> {
                    (activity as? ReadBookActivity)?.setOrientation()
                }
                PreferKey.textFullJustify,
                PreferKey.textBottomJustify,
                PreferKey.useZhLayout,
                PreferKey.adaptSpecialStyle-> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                }
                PreferKey.showBrightnessView -> {
                    postEvent(PreferKey.showBrightnessView, "")
                }
                PreferKey.expandTextMenu -> {
                    (activity as? ReadBookActivity)?.textActionMenu?.upMenu()
                }
                PreferKey.doublePageHorizontal -> {
                    ChapterProvider.upLayout()
                    ReadBook.loadContent(false)
                }
                PreferKey.showReadTitleAddition,
                PreferKey.readBarStyleFollowPage -> {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
                PreferKey.progressBarBehavior -> {
                    postEvent(EventBus.UP_SEEK_BAR, true)
                }
                PreferKey.noAnimScrollPage -> {
                    ReadBook.callBack?.upPageAnim()
                }
                PreferKey.optimizeRender -> {
                    ChapterProvider.upStyle()
                    ReadBook.callBack?.upPageAnim(true)
                    ReadBook.loadContent(false)
                }
                PreferKey.paddingDisplayCutouts -> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }
                "shake_turn_page" -> {
                    val enable = getPrefBoolean("shake_turn_page", false)
                    if (enable) {
                        putPrefString(PreferKey.keepLight, "-1")
                    } else {
                        removePref(PreferKey.keepLight)
                    }
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0))
                }
                "ink_fast_turn" -> {
                    val value = getPrefBoolean("ink_fast_turn",true)
                    InkFastConfig.fastTurnEnable = value
                    postEvent("ink_fast_turn", value)
                }
                "ink_ripple" -> {
                    // NoAnimPageDelegate 每次 onAnimStart 时读取，无需额外处理
                }
                "eink_mode" -> {
                    val mode = getPrefString("eink_mode", "0")
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "resetprop sys.eink.mode $mode;resetprop persist.eink.mode $mode"))
                    }
                }
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                "customPageKey" -> PageKeyDialog(requireContext()).show()
                "clickRegionalConfig" -> {
                    (activity as? ReadBookActivity)?.showClickRegionalConfig()
                }
                PreferKey.pageTouchSlop -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_touch_slop_dialog_title))
                        .setMaxValue(9999)
                        .setMinValue(0)
                        .setValue(AppConfig.pageTouchSlop)
                        .show {
                            AppConfig.pageTouchSlop = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(4))
                        }
                }
                PreferKey.pageTouchClick -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_touch_click_dialog_title))
                        .setMaxValue(399)
                        .setMinValue(0)
                        .setValue(AppConfig.pageTouchClick)
                        .show {
                            AppConfig.pageTouchClick = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(12))
                        }
                }
            }
            return super.onPreferenceTreeClick(preference)
        }

        @Suppress("SameParameterValue")
        private fun upPreferenceSummary(preferenceKey: String, value: String?) {
            val preference = findPreference<Preference>(preferenceKey) ?: return
            when (preferenceKey) {
                PreferKey.pageTouchSlop -> preference.summary =
                    getString(R.string.page_touch_slop_summary, value)
            }
        }

    }
}