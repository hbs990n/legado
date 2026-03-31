package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.DoubaoDownloadManager
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterListFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_chapter_list),
    ChapterListAdapter.Callback,
    TocViewModel.ChapterListCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentChapterListBinding::bind)
    private val mLayoutManager by lazy { UpLinearLayoutManager(requireContext()) }
    private val adapter by lazy { ChapterListAdapter(requireContext(), this) }
    private var durChapterIndex = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        viewModel.chapterListCallBack = this@ChapterListFragment
        val bbg = bottomBackground
        val btc = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        llChapterBaseInfo.setBackgroundColor(bbg)
        tvCurrentChapterInfo.setTextColor(btc)
        ivChapterTop.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        ivChapterBottom.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        initRecyclerView()
        initView()
        viewModel.bookData.observe(this@ChapterListFragment) {
            initBook(it)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() = binding.run {
        ivChapterTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        ivChapterBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        tvCurrentChapterInfo.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(durChapterIndex, 0)
        }
        // 豆包TTS下载按钮
        ivDoubaoDownload.setOnClickListener {
            toggleDoubaoSelectMode()
        }
        tvDoubaoDownload.setOnClickListener {
            startDoubaoDownload()
        }
        binding.llChapterBaseInfo.applyNavigationBarPadding()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        lifecycleScope.launch {
            upChapterList(null)
            durChapterIndex = book.durChapterIndex
            binding.tvCurrentChapterInfo.text =
                "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
            initCacheFileNames(book)
            // 豆包TTS: 显示/隐藏下载按钮，加载已下载状态
            if (AppConfig.doubaoTtsEnabled) {
                binding.ivDoubaoDownload.visible()
                binding.tvDoubaoDownload.visible()
                loadDoubaoDownloadedState(book)
            } else {
                binding.ivDoubaoDownload.gone()
                binding.tvDoubaoDownload.gone()
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        lifecycleScope.launch(IO) {
            adapter.cacheFileNames.addAll(BookHelp.getChapterFiles(book))
            withContext(Main) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    adapter.cacheFileNames.add(chapter.getFileName())
                    if (viewModel.searchKey.isNullOrEmpty()) {
                        adapter.notifyItemChanged(chapter.index, true)
                    } else {
                        adapter.getItems().forEachIndexed { index, bookChapter ->
                            if (bookChapter.index == chapter.index) {
                                adapter.notifyItemChanged(index, true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun upChapterList(searchKey: String?) {
        lifecycleScope.launch {
            withContext(IO) {
                val end = (book?.simulatedTotalChapterNum() ?: Int.MAX_VALUE) - 1
                when {
                    searchKey.isNullOrBlank() ->
                        appDb.bookChapterDao.getChapterList(viewModel.bookUrl, 0, end)

                    else -> appDb.bookChapterDao.search(viewModel.bookUrl, searchKey, 0, end)
                }
            }.let {
                adapter.setItems(it)
            }
        }
    }

    override fun onListChanged() {
        lifecycleScope.launch {
            var scrollPos = 0
            withContext(Default) {
                adapter.getItems().forEachIndexed { index, bookChapter ->
                    if (bookChapter.index >= durChapterIndex) {
                        return@withContext
                    }
                    scrollPos = index
                }
            }
            mLayoutManager.scrollToPositionWithOffset(scrollPos, 0)
            adapter.upDisplayTitles(scrollPos)
        }
    }

    override fun clearDisplayTitle() {
        adapter.clearDisplayTitle()
        adapter.upDisplayTitles(mLayoutManager.findFirstVisibleItemPosition())
    }

    override fun upAdapter() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override val scope: CoroutineScope
        get() = lifecycleScope

    override val book: Book?
        get() = viewModel.bookData.value

    override val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

    override fun openChapter(bookChapter: BookChapter) {
        activity?.run {
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
            )
            finish()
        }
    }

    // ==================== 豆包TTS下载功能 ====================

    private fun loadDoubaoDownloadedState(book: Book) {
        lifecycleScope.launch(IO) {
            val downloaded = DoubaoDownloadManager.getDownloadedChapters(book)
            withContext(Main) {
                adapter.doubaoDownloadedIndices.clear()
                adapter.doubaoDownloadedIndices.addAll(downloaded)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    private fun toggleDoubaoSelectMode() {
        adapter.doubaoSelectMode = !adapter.doubaoSelectMode
        if (!adapter.doubaoSelectMode) {
            adapter.doubaoSelectedIndices.clear()
            binding.tvDoubaoDownload.text = "下载"
        }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override fun onDoubaoSelectionChanged(selectedCount: Int) {
        if (selectedCount > 0) {
            binding.tvDoubaoDownload.text = "下载($selectedCount)"
        } else {
            binding.tvDoubaoDownload.text = "下载"
        }
    }

    private fun startDoubaoDownload() {
        val book = book ?: return
        val selected = adapter.doubaoSelectedIndices.toList()
        if (selected.isEmpty()) {
            toastOnUi("请先勾选要下载的章节")
            return
        }
        // 过滤掉已下载的
        val toDownload = selected.filter { !adapter.doubaoDownloadedIndices.contains(it) }
        if (toDownload.isEmpty()) {
            toastOnUi("所选章节已全部下载")
            return
        }
        adapter.doubaoSelectMode = false
        adapter.doubaoSelectedIndices.clear()
        binding.tvDoubaoDownload.text = "下载"
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        // 启动下载
        DoubaoDownloadManager.enqueue(book, toDownload)
        toastOnUi("已加入下载队列，共${toDownload.size}章")
        // 监听下载进度
        observeDoubaoDownloadProgress(book)
    }

    private fun observeDoubaoDownloadProgress(book: Book) {
        lifecycleScope.launch {
            DoubaoDownloadManager.progressEvent.collect { progress ->
                when {
                    progress.percent == 100 -> {
                        // 下载完成，更新UI
                        adapter.doubaoDownloadedIndices.add(progress.chapterIndex)
                        withContext(Main) {
                            adapter.notifyItemRangeChanged(0, adapter.itemCount)
                        }
                    }
                    progress.percent == -1 -> {
                        // 失败，可以在这里提示
                        withContext(Main) {
                            toastOnUi(
                                "第${progress.chapterIndex + 1}章下载失败: ${progress.message}"
                            )
                        }
                    }
                }
            }
        }
    }

}