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
import com.family.base.data.local.AppDatabase
import com.family.base.data.model.GalleryItem
import com.family.base.data.repository.GalleryRepository
import com.family.base.databinding.ActivityGalleryBinding
import com.family.base.ui.adapter.GalleryAdapter
import com.family.base.ui.viewmodel.GalleryViewModel
import com.family.base.util.Logger

/**
 * 🆕 v20.4.9: экран галереи Яндекс.Диска.
 *
 * 🆕 v20.6.1: адаптеру передаётся GalleryRepository — он нужен
 * для скачивания превью через WebDAV (direct preview-ссылки
 * REST API отдают 403 без куки браузера).
 */
class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GalleryActivity"
        const val EXTRA_PATH = "gallery_start_path"
    }

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var viewModel: GalleryViewModel
    private lateinit var adapter: GalleryAdapter
    private lateinit var repository: GalleryRepository

    private var startPath: String = "/Романовы"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== GalleryActivity onCreate ===")

        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 🆕 v20.6.1: репозиторий создаём один раз и передаём в адаптер.
        val db = AppDatabase.getInstance(this)
        repository = GalleryRepository(db)

        // ViewModel
        val factory = GalleryViewModel.Factory(repository)
        viewModel = ViewModelProvider(this, factory)[GalleryViewModel::class.java]

        // Путь из Intent (если открываем вложенную папку)
        val fromIntent = intent.getStringExtra(EXTRA_PATH)
        if (!fromIntent.isNullOrEmpty()) {
            startPath = fromIntent
            Logger.log(TAG, "onCreate: startPath=$startPath")
        } else {
            Logger.log(TAG, "onCreate: startPath=$startPath")
        }

        setupToolbar()
        setupRecycler()
        setupSwipeRefresh()

        observeViewModel()
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

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_refresh -> {
                    Logger.log(TAG, "refresh button clicked")
                    viewModel.refresh()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecycler() {
        adapter = GalleryAdapter(
            onFolderClick = { item ->
                Logger.log(TAG, "openFolder: ${item.path}")
                val intent = Intent(this, GalleryActivity::class.java)
                intent.putExtra(EXTRA_PATH, item.path)
                startActivity(intent)
            },
            onPhotoClick = { item ->
                Logger.log(TAG, "openPhotoFullscreen: ${item.name}")
                openFullscreen(item)
            },
            onVideoClick = { item ->
                Logger.log(TAG, "video click: ${item.name}")
                Toast.makeText(this, R.string.gallery_video_soon, Toast.LENGTH_SHORT).show()
            }
        )

        // 🆕 v20.6.1: адаптер получает репозиторий для WebDAV-превью.
        adapter.galleryRepository = repository

        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)

        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - 5) {
                    viewModel.loadNextPage()
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

    private fun observeViewModel() {
        viewModel.items.observe(this) { items ->
            Logger.log(TAG, "observeViewModel: items=${items?.size ?: 0}")
            adapter.submitList(items ?: emptyList())
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.swipeRefresh.isRefreshing = loading
        }

        viewModel.error.observe(this) { error ->
            if (!error.isNullOrEmpty()) {
                binding.errorText.text = error
                binding.errorText.visibility = View.VISIBLE
                binding.emptyText.visibility = View.GONE
            } else {
                binding.errorText.visibility = View.GONE
            }
        }

        viewModel.isEmpty.observe(this) { empty ->
            if (empty) {
                binding.emptyText.visibility = View.VISIBLE
                binding.errorText.visibility = View.GONE
            } else {
                binding.emptyText.visibility = View.GONE
            }
        }

        viewModel.folderPath.observe(this) { path ->
            if (!path.isNullOrEmpty()) {
                // Заголовок: показываем имя текущей папки, а не корень.
                val shortName = path.substringAfterLast('/')
                if (shortName.isNotEmpty() && shortName != "Романовы") {
                    supportActionBar?.title = shortName
                } else {
                    supportActionBar?.title = getString(R.string.gallery_title)
                }
            }
        }
    }

    private fun openFullscreen(item: GalleryItem) {
        val intent = Intent(this, FullscreenImageActivity::class.java)
        // 🆕 v20.6.1: передаём ПУТЬ на Диске — FullscreenImageActivity
        // сама скачает превью через WebDAV.
        intent.putExtra(FullscreenImageActivity.EXTRA_GALLERY_PATH, item.path)
        intent.putExtra(FullscreenImageActivity.EXTRA_TITLE, item.name)
        startActivity(intent)
    }
}
