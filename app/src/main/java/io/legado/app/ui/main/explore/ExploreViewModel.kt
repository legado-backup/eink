package io.legado.app.ui.main.explore

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ExploreViewModel(application: Application) : BaseViewModel(application) {

    data class BooksState(
        val books: List<SearchBook> = emptyList(),
        val isLoading: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null
    )

    val booksState = MutableLiveData(BooksState())
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<Unit>()

    private var loadJob: Job? = null
    private var source: BookSource? = null
    private var exploreUrl: String? = null
    private var page = 1
    private val books = linkedSetOf<SearchBook>()

    val hasCategorySelected: Boolean
        get() = source != null && exploreUrl != null

    init {
        execute {
            appDb.bookDao.flowAll().map { bookList ->
                buildList {
                    bookList.filterNot { it.isNotShelf }.forEach {
                        add("${it.name}-${it.author}")
                        add(it.name)
                        add(it.bookUrl)
                    }
                }
            }.catch {
                AppLog.put("发现页获取书架状态失败", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue(Unit)
            }
        }
    }

    fun selectCategory(bookSource: BookSource, url: String) {
        loadJob?.cancel()
        source = bookSource
        exploreUrl = url
        page = 1
        books.clear()
        booksState.value = BooksState(isLoading = true)
        loadPage(reset = true)
    }

    fun clearCategory() {
        loadJob?.cancel()
        loadJob = null
        source = null
        exploreUrl = null
        page = 1
        books.clear()
        booksState.value = BooksState()
    }

    fun deleteSource(source: BookSourcePart) {
        execute {
            SourceHelp.deleteBookSource(source.bookSourceUrl)
        }
    }

    fun topSource(source: BookSourcePart) {
        execute {
            source.customOrder = appDb.bookSourceDao.minOrder - 1
            appDb.bookSourceDao.upOrder(source)
        }
    }

    fun loadNextPage() {
        val state = booksState.value ?: return
        if (state.isLoading || !state.hasMore || source == null || exploreUrl == null) return
        booksState.value = state.copy(isLoading = true, error = null)
        loadPage(reset = false)
    }

    fun refreshCurrentCategory() {
        val currentSource = source ?: return
        val currentUrl = exploreUrl ?: return
        selectCategory(currentSource, currentUrl)
    }

    private fun loadPage(reset: Boolean) {
        val requestSource = source ?: return
        val requestUrl = exploreUrl ?: return
        val requestPage = page
        loadJob = viewModelScope.launch(IO) {
            runCatching {
                WebBook.exploreBookAwait(requestSource, requestUrl, requestPage)
            }.onSuccess { newBooks ->
                if (source?.bookSourceUrl != requestSource.bookSourceUrl || exploreUrl != requestUrl) {
                    return@onSuccess
                }
                if (reset) books.clear()
                books.addAll(newBooks)
                appDb.searchBookDao.insert(*newBooks.toTypedArray())
                if (newBooks.isNotEmpty()) page++
                booksState.postValue(
                    BooksState(
                        books = books.toList(),
                        isLoading = false,
                        hasMore = newBooks.isNotEmpty()
                    )
                )
            }.onFailure {
                if (source?.bookSourceUrl != requestSource.bookSourceUrl || exploreUrl != requestUrl) {
                    return@onFailure
                }
                AppLog.put("发现页加载书籍失败", it)
                booksState.postValue(
                    BooksState(
                        books = books.toList(),
                        isLoading = false,
                        hasMore = true,
                        error = it.localizedMessage ?: it.javaClass.simpleName
                    )
                )
            }
        }
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return bookshelf.contains(key) || bookshelf.contains(book.bookUrl)
    }
}
