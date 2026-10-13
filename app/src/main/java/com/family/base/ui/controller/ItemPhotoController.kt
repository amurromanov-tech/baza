package com.family.base.ui.controller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.local.AppDatabase
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.ui.FullscreenImageActivity
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Контроллер всей логики фото в карточке предмета.
 *
 * Отвечает за:
 *  - тихую фоновую догрузку фото из облака (loadPhotoInBackground);
 *  - fullscreen-просмотр;
 *  - выбор источника (камера / галерея);
 *  - удаление фото;
 *  - pending-new-photo (выбрано новое фото, но ещё не сохранено);
 *  - permission на камеру.
 *
 * НЕ управляет режимами view/edit — этим занимается ItemViewController.
 * НЕ сохраняет сам предмет — этим занимается ItemEditController.
 */
class ItemPhotoController(
    private val activity: AppCompatActivity,
    private val binding: ActivityItemDetailBinding,
    private val db: AppDatabase,
    private val viewModel: MainViewModel,
    private val itemIdProvider: () -> String?,
    private val currentItemProvider: () -> ItemEntity?,
    private val isEditModeProvider: () -> Boolean,
    private val isGuestModeProvider: () -> Boolean,
    private val onCurrentItemChanged: (ItemEntity) -> Unit,
    private val onPhotoChanged: () -> Unit
) {

    private val TAG = "ItemPhotoController"

    private val CAMERA_PERMISSION_REQUEST = 200

    private var newImageBytes: ByteArray? = null
    private var photoUri: Uri? = null

    // 🆕 Ссылка на фоновую задачу загрузки фото
    private var photoLoadJob: Job? = null

    // ============================================================
    // LAUNCHERS — регистрируются снаружи, чтобы активити владела ими
    // ============================================================

    fun registerLaunchers(
        pickImageLauncher: ActivityResultLauncher<String>,
        takePhotoLauncher: ActivityResultLauncher<Uri>
    ) {
        // Launchers создаются в активити и передаются сюда для использования.
        // Мы сохраняем их в поля, чтобы использовать в showImageSourceDialog и др.
        this.pickImageLauncher = pickImageLauncher
        this.takePhotoLauncher = takePhotoLauncher
    }

    private var pickImageLauncher: ActivityResultLauncher<String>? = null
    private var takePhotoLauncher: ActivityResultLauncher<Uri>? = null

    /**
     * Вызывается из активити в колбэке pickImageLauncher.
     */
    fun onImagePicked(uri: Uri?) {
        if (uri == null) return
        try {
            val bitmap = ImageUtils.loadBitmapWithExif(activity, uri) ?: return
            val processedBytes = ImageUtils.processImage(bitmap)
            newImageBytes = processedBytes
            binding.ivPhoto.setImageBitmap(bitmap)
            binding.ivPhoto.visibility = View.VISIBLE
            binding.ivPhotoPlaceholder.visibility = View.GONE
            binding.photoOverlay.visibility = View.VISIBLE
            binding.btnAddPhoto.visibility = View.GONE
            onPhotoChanged()
            Toast.makeText(activity, "Фото выбрано. Не забудь сохранить.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Logger.log(TAG, "Error picking image", e)
            Toast.makeText(activity, "Ошибка выбора фото", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Вызывается из активити в колбэке takePhotoLauncher.
     */
    fun onPhotoTaken(success: Boolean) {
        if (!success) {
            Toast.makeText(activity, "Фото не сделано", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = photoUri ?: return
        try {
            val bitmap = ImageUtils.loadBitmapWithExif(activity, uri) ?: return
            val processedBytes = ImageUtils.processImage(bitmap)
            newImageBytes = processedBytes
            binding.ivPhoto.setImageBitmap(bitmap)
            binding.ivPhoto.visibility = View.VISIBLE
            binding.ivPhotoPlaceholder.visibility = View.GONE
            binding.photoOverlay.visibility = View.VISIBLE
            binding.btnAddPhoto.visibility = View.GONE
            onPhotoChanged()
            Toast.makeText(activity, "Фото сделано. Не забудь сохранить.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Logger.log(TAG, "Error processing camera photo", e)
            Toast.makeText(activity, "Ошибка обработки фото", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // PENDING NEW PHOTO
    // ============================================================

    fun hasPendingNewPhoto(): Boolean = newImageBytes != null

    /**
     * Сохраняет выбранное новое фото локально (если есть).
     * Вызывается из активити перед editController.saveChanges().
     */
    fun savePendingPhoto() {
        val id = itemIdProvider() ?: return
        val bytes = newImageBytes ?: return
        try {
            ImageUtils.saveImageLocally(activity.applicationContext, id, bytes)
            Logger.log(TAG, "savePendingPhoto: saved ${bytes.size} bytes for $id")
        } catch (e: Exception) {
            Logger.log(TAG, "savePendingPhoto: error for $id: ${e.message}")
        }
    }

    /**
     * Возвращает imageUrl для сохранения:
     *  - null, если есть новое фото (надо перезалить);
     *  - иначе текущий imageUrl предмета.
     */
    fun getImageUrlForSave(currentItem: ItemEntity): String? {
        return if (newImageBytes != null) null else currentItem.imageUrl
    }

    fun resetPendingPhoto() {
        newImageBytes = null
    }

    // ============================================================
    // ТИХАЯ ЗАГРУЗКА ФОТО ИЗ ОБЛАКА
    // ============================================================

    fun loadPhotoInBackground(id: String) {
        if (photoLoadJob?.isActive == true) {
            Logger.log(TAG, "loadPhotoInBackground: already running, skip $id")
            return
        }

        val localFile = ImageUtils.getLocalImageFile(activity, id)
        if (localFile != null && localFile.exists() && localFile.length() > 0L) {
            Logger.log(TAG, "loadPhotoInBackground: local file exists, skip $id")
            return
        }

        if (!activity.isFinishing && !activity.isDestroyed) {
            binding.photoLoadingBar.visibility = View.VISIBLE
        }

        photoLoadJob = BaseApplication.applicationScope.launch {
            val bytes = try {
                // repository берём из viewModel через публичный метод
                // (в MainViewModel уже есть uploadFolderImage, нужен downloadItemImageBytes)
                viewModel.downloadItemImageBytes(id)
            } catch (e: Exception) {
                Logger.log(TAG, "loadPhotoInBackground: download error for $id: ${e.message}")
                null
            }

            if (bytes == null || bytes.isEmpty()) {
                Logger.log(TAG, "loadPhotoInBackground: no photo on disk for $id (or error)")
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        binding.photoLoadingBar.visibility = View.GONE
                    }
                }
                return@launch
            }

            try {
                ImageUtils.saveImageLocally(activity.applicationContext, id, bytes)
                Logger.log(TAG, "loadPhotoInBackground: saved locally $id (${bytes.size} bytes)")
            } catch (e: Exception) {
                Logger.log(TAG, "loadPhotoInBackground: save error for $id: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        binding.photoLoadingBar.visibility = View.GONE
                    }
                }
                return@launch
            }

            // Обновляем imageUrl в БД, если был пустой
            try {
                val item = withContext(Dispatchers.IO) { db.itemDao().getItemById(id) }
                if (item != null && item.imageUrl.isNullOrEmpty()) {
                    val updated = item.copy(
                        imageUrl = "images/$id.jpg",
                        updatedDate = System.currentTimeMillis(),
                        updatedBy = "photo-loader"
                    )
                    withContext(Dispatchers.IO) { db.itemDao().updateItem(updated) }
                    Logger.log(TAG, "loadPhotoInBackground: imageUrl updated in DB for $id")
                }
            } catch (e: Exception) {
                Logger.log(TAG, "loadPhotoInBackground: DB update error for $id: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) {
                    Logger.log(TAG, "loadPhotoInBackground: activity gone, skip UI update for $id")
                    return@withContext
                }

                val freshFile = ImageUtils.getLocalImageFile(activity, id)
                if (freshFile != null && freshFile.exists()) {
                    binding.ivPhoto.load(freshFile) { crossfade(true) }
                    binding.ivPhoto.visibility = View.VISIBLE
                    binding.ivPhotoPlaceholder.visibility = View.GONE
                    binding.photoOverlay.visibility = View.VISIBLE
                    Logger.log(TAG, "loadPhotoInBackground: UI updated for $id")
                }
                binding.photoLoadingBar.visibility = View.GONE
            }
        }
    }

    // ============================================================
    // FULLSCREEN
    // ============================================================

    fun openFullscreenPhoto() {
        val id = itemIdProvider() ?: return
        val localFile = ImageUtils.getLocalImageFile(activity, id)
        if (localFile == null || !localFile.exists()) {
            Toast.makeText(activity, "Фото отсутствует", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(activity, FullscreenImageActivity::class.java).apply {
            putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, localFile.absolutePath)
            putExtra(FullscreenImageActivity.EXTRA_TITLE, binding.tvTitle.text.toString())
        }
        activity.startActivity(intent)
    }

    // ============================================================
    // ВЫБОР ИСТОЧНИКА ФОТО
    // ============================================================

    fun showImageSourceDialog() {
        if (isGuestModeProvider()) return
        val options = arrayOf("📸 Сделать фото", "🖼️ Выбрать из галереи")
        AlertDialog.Builder(activity)
            .setTitle("Выберите источник фото")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (checkCameraPermission()) openCamera() else requestCameraPermission()
                    1 -> pickImageLauncher?.launch("image/*")
                }
            }
            .show()
    }

    fun showPhotoActionsDialog(hasPhoto: Boolean) {
        val actions = if (hasPhoto) {
            arrayOf(
                "📸 Сделать фото",
                "🖼️ Выбрать из галереи",
                "👁 Открыть на весь экран",
                "❌ Удалить фото"
            )
        } else {
            arrayOf(
                "📸 Сделать фото",
                "🖼️ Выбрать из галереи"
            )
        }

        AlertDialog.Builder(activity)
            .setTitle(if (hasPhoto) "Действия с фото" else "Добавить фото")
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "📸 Сделать фото" ->
                        if (checkCameraPermission()) openCamera() else requestCameraPermission()

                    "🖼️ Выбрать из галереи" ->
                        pickImageLauncher?.launch("image/*")

                    "👁 Открыть на весь экран" ->
                        openFullscreenPhoto()

                    "❌ Удалить фото" ->
                        confirmDeletePhoto()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ============================================================
    // УДАЛЕНИЕ ФОТО
    // ============================================================

    private fun confirmDeletePhoto() {
        AlertDialog.Builder(activity)
            .setTitle("Удалить фото?")
            .setMessage("Фото будет удалено с этого предмета. Данные предмета не пострадают.")
            .setPositiveButton("Удалить") { _, _ -> deletePhoto() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deletePhoto() {
        val id = itemIdProvider() ?: return
        val item = currentItemProvider() ?: return

        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val f = ImageUtils.getLocalImageFile(activity, id)
                    if (f != null && f.exists()) {
                        val deleted = f.delete()
                        Logger.log(TAG, "Local photo delete: id=$id, file=${f.absolutePath}, deleted=$deleted")
                    } else {
                        Logger.log(TAG, "Local photo delete: id=$id — file not found")
                    }
                }

                val updated = item.copy(
                    imageUrl = null,
                    updatedDate = System.currentTimeMillis(),
                    updatedBy = "user"
                )
                viewModel.updateItemFull(updated)

                newImageBytes = null
                binding.ivPhoto.setImageDrawable(null)
                binding.ivPhoto.visibility = View.GONE
                binding.ivPhotoPlaceholder.visibility = View.VISIBLE
                binding.photoOverlay.visibility = View.VISIBLE

                if (isEditModeProvider()) {
                    binding.btnAddPhoto.visibility = View.VISIBLE
                }

                onCurrentItemChanged(updated)

                Toast.makeText(activity, "Фото удалено", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.log(TAG, "Error deleting photo", e)
                Toast.makeText(activity, "Ошибка удаления фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // КАМЕРА
    // ============================================================

    private fun checkCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    private fun openCamera() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(activity.cacheDir, "IMG_$ts.jpg")
            photoUri = androidx.core.content.FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                photoFile
            )
            takePhotoLauncher?.launch(photoUri)
        } catch (e: Exception) {
            Logger.log(TAG, "Error opening camera", e)
            Toast.makeText(activity, "Ошибка открытия камеры", Toast.LENGTH_SHORT).show()
        }
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                Toast.makeText(activity, "Разрешение на камеру не получено", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return false
    }

    // ============================================================
    // ДЛЯ VIEW-РЕЖИМА: показ/скрытие ivPhoto без запуска загрузки
    // ============================================================

    fun showLocalPhotoOrPlaceholder(id: String) {
        val localFile = ImageUtils.getLocalImageFile(activity, id)
        if (localFile != null && localFile.exists()) {
            binding.ivPhoto.visibility = View.VISIBLE
            binding.ivPhotoPlaceholder.visibility = View.GONE
            binding.photoOverlay.visibility = View.VISIBLE
            binding.ivPhoto.load(localFile) { crossfade(true) }
        } else {
            binding.ivPhoto.visibility = View.GONE
            binding.ivPhotoPlaceholder.visibility = View.VISIBLE
            binding.photoOverlay.visibility = View.VISIBLE
        }
    }
}
