package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGrid2Binding
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.views.onLongClick

class BooksAdapterGrid(context: Context, private val callBack: CallBack) :
    BaseBooksAdapter<ViewBinding>(context) {

    // ★ 写死为 2：永远走 Grid2（叠加布局），无视用户设置
    private val showBookname = 2

    override fun getViewBinding(parent: ViewGroup): ViewBinding {
        return when (showBookname) {
            2 -> ItemBookshelfGrid2Binding.inflate(inflater, parent, false)
            else -> ItemBookshelfGridBinding.inflate(inflater, parent, false)
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        when (binding) {
            is ItemBookshelfGridBinding -> binding.run {
                if (payloads.isEmpty()) {
                    ivCover.load(item, false)
                    upRefresh(binding, item)
                    if (showBookname == 0) {
                        tvName.visible()
                        tvName.text = item.name
                        progressBar.gone()
                    } else {
                        tvName.gone()
                        upProgress(binding, item)
                    }
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bundle.keySet().forEach {
                            when (it) {
                                "name" -> {
                                    tvName.text = item.name
                                    if (showBookname == 0) {
                                        tvName.visible()
                                        progressBar.gone()
                                    } else {
                                        tvName.gone()
                                        upProgress(binding, item)
                                    }
                                }
                                "cover" -> ivCover.load(item, false)
                                "refresh" -> upRefresh(binding, item)
                                "progress" -> {
                                    if (showBookname != 0) {
                                        upProgress(binding, item)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is ItemBookshelfGrid2Binding -> binding.run {
                if (payloads.isEmpty()) {
                    tvName.text = item.name
                    ivCover.load(item, false)
                    upRefresh(binding, item)
                    upProgress(binding, item)
                    forceOverlayName()
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bundle.keySet().forEach {
                            when (it) {
                                "name" -> {
                                    tvName.text = item.name
                                    forceOverlayName()
                                }
                                "cover" -> ivCover.load(item, false)
                                "refresh" -> upRefresh(binding, item)
                                "progress" -> upProgress(binding, item)
                            }
                        }
                    }
                }
            }
        }
    }

    /** ★★★ 强制书名叠加——覆盖 XML/databinding 结果 ★★★ */
    private fun ItemBookshelfGrid2Binding.forceOverlayName() {
        tvName.visibility = View.VISIBLE
        tvName.background = ColorDrawable(0xB3000000.toInt())   // 70% 半透明黑底
        tvName.setTextColor(Color.WHITE)
        tvName.maxLines = 2
        tvName.ellipsize = TextUtils.TruncateAt.END
        val pad = (4 * root.resources.displayMetrics.density).toInt()
        tvName.setPadding(pad * 2, pad, pad * 2, pad)

        (tvName.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
            topToTop = ConstraintLayout.LayoutParams.UNSET
            topToBottom = ConstraintLayout.LayoutParams.UNSET
            bottomToBottom = ivCover.id
            startToStart = ivCover.id
            endToEnd = ivCover.id
            tvName.layoutParams = this
        }
    }
    // ★★★ 强制叠加结束 ★★★

    private fun upProgress(binding: ViewBinding, item: Book) {
        val total = item.totalChapterNum
        val current = item.durChapterIndex + 1
        val progress = if (total > 0) (current * 100 / total).coerceIn(0, 100) else 0
        when (binding) {
            is ItemBookshelfGridBinding -> {
                binding.progressBar.visible()
                binding.progressBar.progress = progress
            }
            is ItemBookshelfGrid2Binding -> {
                binding.progressBar.visible()
                binding.progressBar.progress = progress
            }
        }
    }

    private fun upRefresh(binding: ViewBinding, item: Book) {
        when (binding) {
            is ItemBookshelfGridBinding -> binding.run {
                if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                    bvUnread.invisible()
                    rlLoading.visible()
                } else {
                    rlLoading.gone()
                    if (AppConfig.showUnread) {
                        bvUnread.setBadgeCount(item.getUnreadChapterNum())
                        bvUnread.setHighlight(item.lastCheckCount > 0)
                    } else {
                        bvUnread.invisible()
                    }
                }
            }
            is ItemBookshelfGrid2Binding -> binding.run {
                if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                    bvUnread.invisible()
                    rlLoading.visible()
                } else {
                    rlLoading.gone()
                    if (AppConfig.showUnread) {
                        bvUnread.setBadgeCount(item.getUnreadChapterNum())
                        bvUnread.setHighlight(item.lastCheckCount > 0)
                    } else {
                        bvUnread.invisible()
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }
    }
}