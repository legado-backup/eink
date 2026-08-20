package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
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
    private val showBookname = AppConfig.showBookname
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
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bundle.keySet().forEach {
                            when (it) {
                                "name" -> tvName.text = item.name
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
                    rlLoading.inVisible()
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
                    rlLoading.inVisible()
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
