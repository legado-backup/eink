package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.get
import androidx.preference.PreferenceManager
import com.github.liuyueyi.quick.transfer.constants.TransType
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadBookStyleBinding
import io.legado.app.databinding.ItemReadStyleBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.delegate.IReaderPageH // ★ 引入掌阅检测
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onLongClick

class ReadStyleDialog : BaseDialogFragment(R.layout.dialog_read_book_style),
    FontSelectDialog.CallBack {

    private val binding by viewBinding(DialogReadBookStyleBinding::bind)
    private val callBack get() = activity as? ReadBookActivity
    private lateinit var styleAdapter: StyleAdapter

    private val rippleBtns = mutableListOf<TextView>()

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
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initView()
        initData()
        initViewEvent()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    private fun initView() = binding.run {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        rootView.setBackgroundColor(bg)
        
        tvBgTs.setTextColor(textColor)
        tvShareLayout.setTextColor(textColor)
        
        dsbTextWeight.valueFormat = { it.toString() }
        dsbTextSize.valueFormat = { (it + 5).toString() }
        dsbTextLetterSpacing.valueFormat = { ((it - 50) / 100f).toString() }
        dsbLineSize.valueFormat = { ((it - 10) / 10f).toString() }
        dsbParagraphSpacing.valueFormat = { (it / 10f).toString() }

        // ★ 核心逻辑：判断是否为掌阅设备
        val isProbe = IReaderPageH.probe()
        val parent = rgPageAnim.parent as? ViewGroup
        
        if (parent != null) {
            val rgIndex = parent.indexOfChild(rgPageAnim)
            val tvIndex = parent.indexOfChild(tvPageAnim)

            if (isProbe) {
                // 【支持掌阅】：隐藏原文字，替换为波纹按钮
                tvPageAnim.visibility = View.GONE
                parent.removeView(rgPageAnim)
                initRippleSettings(parent, rgIndex)
            } else {
                // 【不支持掌阅】：隐藏原有的文字、翻页组，以及相邻的分割线
                tvPageAnim.visibility = View.GONE
                rgPageAnim.visibility = View.GONE
                
                // 隐藏它们上方最近的分割线（只认纯 View）
                val topIndex = Math.min(rgIndex, tvIndex) - 1
                if (topIndex >= 0 && parent.getChildAt(topIndex).javaClass == View::class.java) {
                    parent.getChildAt(topIndex).visibility = View.GONE
                }
                // 隐藏它们下方最近的分割线（只认纯 View）
                val bottomIndex = Math.max(rgIndex, tvIndex) + 1
                if (bottomIndex < parent.childCount && parent.getChildAt(bottomIndex).javaClass == View::class.java) {
                    parent.getChildAt(bottomIndex).visibility = View.GONE
                }
            }
        }

        styleAdapter = StyleAdapter()
        rvStyle.adapter = styleAdapter
        styleAdapter.addFooterView {
            ItemReadStyleBinding.inflate(layoutInflater, it, false).apply {
                ivStyle.setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
                ivStyle.setText(null)
                ivStyle.setColorFilter(textColor)
                ivStyle.borderColor = textColor
                ivStyle.setImageResource(R.drawable.ic_add)
                root.setOnClickListener {
                    ReadBookConfig.configList.add(ReadBookConfig.Config())
                    showBgTextConfig(ReadBookConfig.configList.lastIndex)
                }
            }
        }
    }

    /**
     * 初始化波纹控制按钮
     */
    private fun initRippleSettings(parent: ViewGroup, index: Int) {
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            // 继承父容器的左右边距，不贴边
            val defaultMargin = 16.dpToPx() 
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(defaultMargin, 8.dpToPx(), defaultMargin, 8.dpToPx())
            }
            layoutParams = lp
        }

        // 小字标题
        val title = TextView(requireContext()).apply {
            text = "掌阅波纹"
            textSize = 12f
            setTextColor(binding.tvTextFont.currentTextColor)
            alpha = 0.6f
            setPadding(0, 0, 0, 8.dpToPx())
        }
        wrapper.addView(title)

        // 横向容器
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val texts = listOf("关闭", "慢速", "中速", "快速", "瀑布", "反转")
        rippleBtns.clear()
        
        texts.forEachIndexed { i, text ->
            val btn = createRippleBtn(text)
            rippleBtns.add(btn)
            
            // 按钮本身使用 wrap_content，不拉伸
            val btnLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            container.addView(btn, btnLp)
            
            // 弹簧占位：把按钮推向两边
            if (i < texts.size - 1) {
                val space = View(requireContext())
                container.addView(space, LinearLayout.LayoutParams(0, 1, 1f))
            }
        }

        wrapper.addView(container)
        parent.addView(wrapper, index)

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        fun refreshUI() {
            val rippleMode = getPrefString("ink_ripple", "off")
            val vertical = getPrefBoolean("ink_ripple_vertical", false)
            val reverse = getPrefBoolean("ink_ripple_reverse", false)

            updateBtnState(rippleBtns[0], rippleMode == "off" || rippleMode.isNullOrBlank())
            updateBtnState(rippleBtns[1], rippleMode == "slow")
            updateBtnState(rippleBtns[2], rippleMode == "medium")
            updateBtnState(rippleBtns[3], rippleMode == "fast")
            updateBtnState(rippleBtns[4], vertical)
            updateBtnState(rippleBtns[5], reverse)
        }

        rippleBtns[0].setOnClickListener { putPrefString("ink_ripple", "off"); postEvent(EventBus.UP_CONFIG, arrayListOf(5)); refreshUI() }
        rippleBtns[1].setOnClickListener { putPrefString("ink_ripple", "slow"); postEvent(EventBus.UP_CONFIG, arrayListOf(5)); refreshUI() }
        rippleBtns[2].setOnClickListener { putPrefString("ink_ripple", "medium"); postEvent(EventBus.UP_CONFIG, arrayListOf(5)); refreshUI() }
        rippleBtns[3].setOnClickListener { putPrefString("ink_ripple", "fast"); postEvent(EventBus.UP_CONFIG, arrayListOf(5)); refreshUI() }
        
        rippleBtns[4].setOnClickListener {
            val newV = !getPrefBoolean("ink_ripple_vertical", false)
            prefs.edit().putBoolean("ink_ripple_vertical", newV).apply()
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
            refreshUI()
        }
        rippleBtns[5].setOnClickListener {
            val newR = !getPrefBoolean("ink_ripple_reverse", false)
            prefs.edit().putBoolean("ink_ripple_reverse", newR).apply()
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
            refreshUI()
        }

        refreshUI()
    }

    private fun createRippleBtn(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = binding.tvTextFont.textSize / resources.displayMetrics.scaledDensity
            gravity = Gravity.CENTER
            setPadding(14.dpToPx(), binding.tvTextFont.paddingTop, 14.dpToPx(), binding.tvTextFont.paddingBottom)
            isClickable = true
        }
    }

    private fun updateBtnState(btn: TextView, isActive: Boolean) {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)

        if (isActive) {
            // 选中：全黑白字
            btn.setTextColor(bg)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6.dpToPx().toFloat()
                setColor(textColor)
            }
        } else {
            // 未选中：白底黑字黑边
            btn.setTextColor(textColor)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6.dpToPx().toFloat()
                setStroke(1.dpToPx(), textColor)
                setColor(Color.TRANSPARENT)
            }
        }
    }

    private fun initData() {
        binding.cbShareLayout.isChecked = ReadBookConfig.shareLayout
        upView()
        styleAdapter.setItems(ReadBookConfig.configList)
    }

    private fun initViewEvent() = binding.run {
        chineseConverter.onChanged {
            ChineseUtils.unLoad(*TransType.entries.toTypedArray())
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        tvTextFont.setOnClickListener {
            showDialogFragment<FontSelectDialog>()
        }
        tvTextIndent.setOnClickListener {
            context?.selector(
                title = getString(R.string.text_indent),
                items = resources.getStringArray(R.array.indent).toList()
            ) { _, index ->
                ReadBookConfig.paragraphIndent = "　".repeat(index)
                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
            }
        }
        tvPadding.setOnClickListener {
            dismissAllowingStateLoss()
            callBack?.showPaddingConfig()
        }
        tvTip.setOnClickListener {
            TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
        }
        
        cbShareLayout.onCheckedChangeListener = { _, isChecked ->
            ReadBookConfig.shareLayout = isChecked
            upView()
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        }
        dsbTextWeight.onChanged = {
            ReadBookConfig.textBold = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTextSize.onChanged = {
            ReadBookConfig.textSize = it + 5
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTextLetterSpacing.onChanged = {
            ReadBookConfig.letterSpacing = (it - 50) / 100f
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbLineSize.onChanged = {
            ReadBookConfig.lineSpacingExtra = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbParagraphSpacing.onChanged = {
            ReadBookConfig.paragraphSpacing = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
    }

    private fun changeBgTextConfig(index: Int) {
        val oldIndex = ReadBookConfig.styleSelect
        if (index != oldIndex) {
            ReadBookConfig.styleSelect = index
            upView()
            styleAdapter.notifyItemChanged(oldIndex)
            styleAdapter.notifyItemChanged(index)
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    private fun showBgTextConfig(index: Int): Boolean {
        dismissAllowingStateLoss()
        changeBgTextConfig(index)
        callBack?.showBgTextConfig()
        return true
    }

    private fun upView() = binding.run {
        ReadBookConfig.let {
            dsbTextWeight.progress = it.textBold
            dsbTextSize.progress = it.textSize - 5
            dsbTextLetterSpacing.progress = (it.letterSpacing * 100).toInt() + 50
            dsbLineSize.progress = it.lineSpacingExtra
            dsbParagraphSpacing.progress = it.paragraphSpacing
        }
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5))
        }
    }

    inner class StyleAdapter :
        RecyclerAdapter<ReadBookConfig.Config, ItemReadStyleBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemReadStyleBinding {
            return ItemReadStyleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReadStyleBinding,
            item: ReadBookConfig.Config,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                ivStyle.setText(item.name.ifBlank { "文字" })
                ivStyle.setTextColor(item.curTextColor())
                ivStyle.setImageDrawable(item.curBgDrawable(100, 150))
                if (ReadBookConfig.styleSelect == holder.layoutPosition) {
                    ivStyle.borderColor = accentColor
                    ivStyle.setTextBold(true)
                } else {
                    ivStyle.borderColor = item.curTextColor()
                    ivStyle.setTextBold(false)
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadStyleBinding) {
            binding.apply {
                ivStyle.setOnClickListener {
                    if (ivStyle.isInView) {
                        changeBgTextConfig(holder.layoutPosition)
                    }
                }
                ivStyle.onLongClick(ivStyle.isInView) {
                    if (ivStyle.isInView) {
                        showBgTextConfig(holder.layoutPosition)
                    }
                }
            }
        }
    }
}