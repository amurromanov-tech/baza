package com.family.base.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.model.GalleryItem
import com.family.base.data.repository.GalleryRepository
import com.family.base.databinding.ActivityGalleryBinding
import com.family.base.ui.adapter.GalleryAdapter
import com.family.base.ui.viewmodel.GalleryViewModel
import com.family.base.util.DiskPaths
import com.family.base.util.Logger

/**
 * 🆕 v20.4.9: экран галереи «🖼️ Галерея».
 *
 * 🆕 v20.6.1: адаптеру передаётся GalleryRepository — он нужен
 * для скачивания превью через WebDAV (прямые preview-ссылки REST API
 * отдают 403 без куки браузера).
 *
 * openPhoto теперь передаёт EXTRA_GALLERY_PATH (путь на Диске),
 * а не EXTRA_IMAGE_URL — FullscreenImageActivity сама скачает превью
 * через WebDAV.
 *
 * 🆕 v20.5.12 (Шаг 2): openPhoto дополнительно передаёт
 * EXTRA_FILE_URL (для «Копировать ссылку», вариант B),
 * EXTRA_SIZE_BYTES и EXTRA_CREATED_MILLIS (для «Информация»).
 *
 * 🆕 v20.5.14: onVideoClick выбирает плеер:
 *  - MP4 / MKV / WebM / 3GP / MOV / M4V → VideoPlayerActivity (ExoPlayer);
 *  - всё остальное (AVI и экзотика) → внешний плеер через ACTION_VIEW.
 *  Причина: ExoPlayer не поддерживает контейнер AVI.
 */
class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GalleryActivity"

        const val EXTRA_PATH = "gallery_path"

        private const val PREFETCH_THRESHOLD = 5

        /**
         * 🆕 v20.5.14: расширения, которые ExoPlayer (Media3) точно играет.
         * Всё остальное (AVI, WMV, FLV и т.п.) — во внешний плеер.
         */
        private val EXO_SUPPORTED_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "3gp", "3gpp", "mov"
        )

        /**
         * MIME-типы, которые ExoPlayer точно играет.
         */
        private val EXO_SUPPORTED_MIMES = setOf(
            "video/mp4",
            "video/x-matroska",
            "video/webm",
            "video/3gpp",
            "video/quicktime",
            "video/x-m4v"
        )
    }

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var viewModel: GalleryViewModel
    private lateinit var adapter: GalleryAdapter

    /**
     * 🆕 v20.6.1: репозиторий для WebDAV-превью.
     * Создаётся здесь и передаётся адаптеру.
     */
    private lateinit var repository: GalleryRepository

    private var currentFolderPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== GalleryActivity onCreate START ===")

        try {
            binding = ActivityGalleryBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        // 🆕 v20.6.1: репозиторий для WebDAV.
        val db = AppDatabase.getInstance(this)
        repository = GalleryRepository(db)

        viewModel = ViewModelProvider(this)[GalleryViewModel::class.java]

        currentFolderPath = intent.getStringExtra(EXTRA_PATH)
            ?.takeIf { it.isNotEmpty() }
            ?: DiskPaths.GALLERY

        Logger.log(TAG, "onCreate: path=$currentFolderPath")

        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupListeners()
        observeViewModel()

        viewModel.openFolder(currentFolderPath)

        Logger.log(TAG, "=== GalleryActivity onCreate FINISHED ===")
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            Logger.log(TAG, "toolbar up clicked")
            finish()
        }

        binding.btnRefresh.setOnClickListener {
            Logger.log(TAG, "refresh clicked")
            viewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        adapter = GalleryAdapter(
            onFolderClick = { item -> openSubfolder(item) },
            onPhotoClick = { item -> openPhoto(item) },
            onVideoClick = { item -> openVideo(item) }
        )

        // 🆕 v20.6.1: передаём repository адаптеру для WebDAV-превью.
        adapter.galleryRepository = repository

        val layoutManager = GridLayoutManager(this, 3)
        binding.rvGallery.layoutManager = layoutManager
        binding.rvGallery.adapter = adapter

        binding.rvGallery.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val total = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (total > 0 && lastVisible >= total - PREFETCH_THRESHOLD) {
                    if (viewModel.hasMore.value == true && viewModel.isLoading.value != true) {
                        Logger.log(
                            TAG,
                            "scroll: lastVisible=$lastVisible/$total, loading next page"
                        )
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
    }

    private fun setupListeners() {
        binding.btnRetry.setOnClickListener {
            Logger.log(TAG, "retry (empty) clicked")
            viewModel.retry()
        }
        binding.btnErrorRetry.setOnClickListener {
            Logger.log(TAG, "retry (error) clicked")
            viewModel.retry()
        }
    }

    private fun observeViewModel() {
        viewModel.items.observe(this) { items ->
            Logger.log(TAG, "items observed: ${items.size}")
            adapter.submitList(items)
            updateUiState(items)
        }

        viewModel.isLoading.observe(this) { loading ->
            Logger.log(TAG, "isLoading=$loading")
            val itemsEmpty = viewModel.items.value.isNullOrEmpty()
            binding.progressBar.visibility = if (loading && itemsEmpty) View.VISIBLE else View.GONE

            if (!loading && binding.swipeRefresh.isRefreshing) {
                binding.swipeRefresh.isRefreshing = false
            }
        }

        viewModel.error.observe(this) { error ->
            if (!error.isNullOrEmpty()) {
                Logger.log(TAG, "error observed: $error")
                val itemsEmpty = viewModel.items.value.isNullOrEmpty()
                if (itemsEmpty) {
                    binding.errorState.visibility = View.VISIBLE
                    binding.tvErrorText.text = getString(R.string.gallery_load_error, error)
                    binding.emptyState.visibility = View.GONE
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.gallery_load_error, error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        viewModel.hasMore.observe(this) { hasMore ->
            Logger.log(TAG, "hasMore=$hasMore")
        }
    }

    private fun updateUiState(items: List<GalleryItem>) {
        val isEmpty = items.isEmpty()
        val isLoading = viewModel.isLoading.value == true
        val hasError = !viewModel.error.value.isNullOrEmpty()

        when {
            isEmpty && hasError -> {
                binding.emptyState.visibility = View.GONE
                binding.errorState.visibility = View.VISIBLE
            }
            isEmpty && !isLoading -> {
                binding.emptyState.visibility = View.VISIBLE
                binding.errorState.visibility = View.GONE
            }
            else -> {
                binding.emptyState.visibility = View.GONE
                binding.errorState.visibility = View.GONE
            }
        }
    }

    private fun openSubfolder(item: GalleryItem) {
        Logger.log(TAG, "openSubfolder: ${item.name} → ${item.path}")
        val intent = Intent(this, GalleryActivity::class.java).apply {
            putExtra(EXTRA_PATH, item.path)
        }
        startActivity(intent)
    }

    private fun openPhoto(item: GalleryItem) {
        // 🆕 v20.6.1: передаём ПУТЬ на Диске — FullscreenImageActivity
        // сама скачает превью через WebDAV (preview-ссылки REST API
        // отдают 403 без куки браузера).
        //
        // 🆕 v20.5.12 (Шаг 2): дополнительно передаём fileUrl (для
        // «Копировать ссылку», вариант B), размер и дату создания
        // (для «Информация»).
        Logger.log(TAG, "openPhoto: ${item.name} → ${item.path}")
        val intent = Intent(this, FullscreenImageActivity::class.java).apply {
            putExtra(FullscreenImageActivity.EXTRA_GALLERY_PATH, item.path)
            putExtra(FullscreenImageActivity.EXTRA_TITLE, item.name)
            item.fileUrl?.let { putExtra(FullscreenImageActivity.EXTRA_FILE_URL, it) }
            item.sizeBytes?.let { putExtra(FullscreenImageActivity.EXTRA_SIZE_BYTES, it) }
            item.createdMillis?.let { putExtra(FullscreenImageActivity.EXTRA_CREATED_MILLIS, it) }
        }
        startActivity(intent)
    }

    // ============================================================
    // 🆕 v20.5.14: ВИДЕО — ExoPlayer или внешний плеер
    // ============================================================

    private fun openVideo(item: GalleryItem) {
        Logger.log(TAG, "openVideo: ${item.name} (fileUrl=${item.fileUrl})")

        val url = item.fileUrl
        if (url.isNullOrEmpty()) {
            Logger.log(TAG, "openVideo: fileUrl is empty for '${item.name}'")
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_SHORT).show()
            return
        }

        val ext = item.name.substringAfterLast('.', "").lowercase()
        val mime = mimeForExtension(ext)

        if (isExoPlayerSupported(ext, mime)) {
            Logger.log(TAG, "openVideo: ExoPlayer for '$ext' ($mime)")
            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_URL, url)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, item.name)
                putExtra(VideoPlayerActivity.EXTRA_MIME, mime)
            }
            startActivity(intent)
        } else {
            Logger.log(TAG, "openVideo: external player for '$ext' ($mime)")
            openInExternalPlayer(url, mime, item.name)
        }
    }

    private fun isExoPlayerSupported(ext: String, mime: String): Boolean {
        if (ext.isNotEmpty() && ext in EXO_SUPPORTED_EXTENSIONS) return true
        if (mime in EXO_SUPPORTED_MIMES) return true
        return false
    }

    private fun mimeForExtension(ext: String): String {
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp", "3gpp" -> "video/3gpp"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            else -> "video/*"
        }
    }

    private fun openInExternalPlayer(url: String, mime: String, title: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            Logger.log(TAG, "openInExternalPlayer: launched '$title' ($mime)")
        } catch (e: ActivityNotFoundException) {
            Logger.log(TAG, "openInExternalPlayer: no app for $mime", e)
            Toast.makeText(this, R.string.video_no_player, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Logger.log(TAG, "openInExternalPlayer error: ${e.message}", e)
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_SHORT).show()
        }
    }
}
