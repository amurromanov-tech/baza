package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.model.GalleryItem
import com.family.base.databinding.ActivityGalleryBinding
import com.family.base.ui.adapter.GalleryAdapter
import com.family.base.ui.viewmodel.GalleryViewModel
import com.family.base.util.DiskPaths
import com.family.base.util.Logger

/**
 * 🆕 v20.4.9: экран галереи.
 *
 * Открывается:
 *  - из MainActivity при клике на системную папку «🖼️ Галерея» → путь /Романовы;
 *  - из этой же GalleryActivity при клике на подпапку → новый экземпляр
 *    с путём этой подпапки.
 *
 * «Назад» (системная кнопка и up в toolbar) — finish(). Так как на каждую
 * подпапку создаётся отдельная Activity, системный стек корректно
 * возвращает на уровень выше.
 *
 * Пагинация:
 *  - GridLayoutManager 3 колонки;
 *  - при прокрутке до позиции (itemCount - 6) — loadNextPage();
 *  - isLoading защищает от повторных вызовов (в ViewModel);
 *  - hasMore = false — больше не грузим.
 *
 * Pull-to-refresh: SwipeRefreshLayout → viewModel.refresh().
 *
 * Клики (через GalleryAdapter):
 *  - папка → onFolderClick → новый экземпляр GalleryActivity с item.path;
 *  - фото  → onPhotoClick  → FullscreenImageActivity с EXTRA_IMAGE_URL = previewUrl;
 *  - видео → onVideoClick  → Toast «Видео будет в следующей версии».
 */
class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GalleryActivity"
        private const val GRID_COLUMNS = 3
        private const val PREFETCH_THRESHOLD = 6

        /**
         * 🆕 v20.4.9: ключ Intent — абсолютный путь папки на Диске,
         * которую нужно открыть. Если не передан — используется
         * DiskPaths.GALLERY ("/Романовы").
         */
        const val EXTRA_PATH = "gallery_path"
    }

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var viewModel: GalleryViewModel
    private lateinit var adapter: GalleryAdapter

    /** Путь, с которого открыт экран. Если null — берём корень галереи. */
    private var startPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== GalleryActivity onCreate ===")

        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startPath = intent.getStringExtra(EXTRA_PATH) ?: DiskPaths.GALLERY
        Logger.log(TAG, "onCreate: startPath=$startPath")

        viewModel = ViewModelProvider(this)[GalleryViewModel::class.java]

        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupEmptyStateButtons()
        observeState()

        viewModel.openFolder(startPath)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.gallery_title)

        binding.toolbar.setNavigationOnClickListener {
            Logger.log(TAG, "toolbar up: finishing")
            finish()
        }

        binding.btnRefresh.setOnClickListener {
            Logger.log(TAG, "refresh button clicked")
            viewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        adapter = GalleryAdapter(
            onFolderClick = { item -> openFolder(item) },
            onPhotoClick = { item -> openPhotoFullscreen(item) },
            onVideoClick = { item ->
                Toast.makeText(
                    this,
                    getString(R.string.gallery_video_soon),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        val lm = GridLayoutManager(this, GRID_COLUMNS)
        binding.rvGallery.layoutManager = lm
        binding.rvGallery.adapter = adapter

        binding.rvGallery.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return

                val totalItems = lm.itemCount
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= totalItems - PREFETCH_THRESHOLD) {
                    if (viewModel.hasMore.value == true && viewModel.isLoading.value != true) {
                        viewModel.loadNextPage()
                    }
                }
            }
        })
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            Logger.log(TAG, "swipe refresh")
            viewModel.refresh()
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.colorPrimary)
    }

    private fun setupEmptyStateButtons() {
        binding.btnRetry.setOnClickListener { viewModel.retry() }
        binding.btnErrorRetry.setOnClickListener { viewModel.retry() }
    }

    private fun observeState() {
        viewModel.items.observe(this) { items ->
            adapter.submitList(items)
            updateEmptyOrGrid(items)
        }

        viewModel.isLoading.observe(this) { loading ->
            val showSpinner = loading && viewModel.items.value.isNullOrEmpty()
            binding.progressBar.visibility = if (showSpinner) View.VISIBLE else View.GONE

            if (!loading) {
                binding.swipeRefresh.isRefreshing = false
            }
        }

        viewModel.error.observe(this) { err ->
            if (err.isNullOrEmpty()) {
                binding.errorState.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                return@observe
            }

            val items = viewModel.items.value
            if (items.isNullOrEmpty()) {
                binding.tvErrorText.text = getString(R.string.gallery_load_error, err)
                binding.errorState.visibility = View.VISIBLE
                binding.emptyState.visibility = View.GONE
                binding.rvGallery.visibility = View.GONE
            } else {
                Toast.makeText(this, getString(R.string.gallery_load_error, err), Toast.LENGTH_SHORT).show()
            }
            binding.swipeRefresh.isRefreshing = false
        }

        viewModel.folderPath.observe(this) { path ->
            if (path.isNullOrEmpty()) {
                supportActionBar?.title = getString(R.string.gallery_title)
            } else {
                val last = path.trimEnd('/').substringAfterLast('/')
                supportActionBar?.title = last.ifEmpty { getString(R.string.gallery_title) }
            }
        }
    }

    private fun updateEmptyOrGrid(items: List<GalleryItem>) {
        if (items.isNotEmpty()) {
            binding.rvGallery.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
            binding.errorState.visibility = View.GONE
            return
        }

        val loading = viewModel.isLoading.value == true
        val hasError = !viewModel.error.value.isNullOrEmpty()

        if (!loading && !hasError) {
            binding.rvGallery.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.errorState.visibility = View.GONE
        }
    }

    private fun openFolder(item: GalleryItem) {
        Logger.log(TAG, "openFolder: ${item.path}")
        val intent = Intent(this, GalleryActivity::class.java).apply {
            putExtra(EXTRA_PATH, item.path)
        }
        startActivity(intent)
    }

    private fun openPhotoFullscreen(item: GalleryItem) {
        val url = item.previewUrl
        if (url.isNullOrEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.gallery_load_error, "нет ссылки на превью"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Logger.log(TAG, "openPhotoFullscreen: ${item.name}")
        val intent = Intent(this, FullscreenImageActivity::class.java).apply {
            putExtra(FullscreenImageActivity.EXTRA_IMAGE_URL, url)
            putExtra(FullscreenImageActivity.EXTRA_TITLE, item.name)
        }
        startActivity(intent)
    }
}
