package com.family.base.ui.controller

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.load
import com.family.base.R
import com.family.base.data.local.entity.FolderEntity
import com.family.base.data.local.entity.ItemEntity
import com.family.base.databinding.ActivityItemDetailBinding
import com.family.base.ui.FullscreenImageActivity
import com.family.base.ui.ItemDetailActivity
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.ImageUtils
import com.family.base.util.Logger

/**
 * Контроллер секции «📦 Вложенные» в карточке предмета.
 *
 * Отвечает за:
 *  - загрузку списка вложенных папок и предметов;
 *  - отрисовку строк (bindNestedFolder, bindNestedItem);
 *  - переходы внутрь вложенной папки или предмета.
 *
 * НЕ управляет режимом view/edit — этим занимается ItemViewController.
 */
class ItemNestedController(
    private val activity: AppCompatActivity,
    private val binding: ActivityItemDetailBinding,
    private val viewModel: MainViewModel,
    private val itemIdProvider: () -> String?,
    private val onNavigateToFolder: (String) -> Unit,
    private val onOpenNestedItem: (String) -> Unit
) {

    private val TAG = "ItemNestedController"

    // ============================================================
    // ЗАГРУЗКА
    // ============================================================

    fun loadNestedContent() {
        val id = itemIdProvider() ?: return

        viewModel.getNestedContent(id) { folders, items ->
            binding.nestedContainer.removeAllViews()

            val totalChildren = folders.size + items.size

            if (totalChildren == 0) {
                binding.tvNestedEmpty.visibility = View.VISIBLE
            } else {
                binding.tvNestedEmpty.visibility = View.GONE

                folders.forEach { folder ->
                    val view = bindNestedFolder(folder)
                    binding.nestedContainer.addView(view)
                }
                items.forEach { item ->
                    val view = bindNestedItem(item)
                    binding.nestedContainer.addView(view)
                }
            }

            Logger.log(TAG, "loadNestedContent: folders=${folders.size}, items=${items.size}")
        }
    }

    // ============================================================
    // ОТРИСОВКА СТРОК
    // ============================================================

    private fun bindNestedFolder(folder: FolderEntity): View {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.item_catalog_entry, binding.nestedContainer, false)

        val icon = view.findViewById<ImageView>(R.id.icon)
        val name = view.findViewById<TextView>(R.id.name)
        val info = view.findViewById<TextView>(R.id.info)
        val expiryInfo = view.findViewById<TextView>(R.id.expiryInfo)
        val lentInfo = view.findViewById<TextView>(R.id.lentInfo)
        val colorBar = view.findViewById<View>(R.id.colorBar)

        val iconFile = ImageUtils.getLocalImageFile(activity, "folder_${folder.id}")
        if (iconFile != null && iconFile.exists()) {
            icon.load(iconFile) {
                crossfade(true)
                placeholder(R.drawable.ic_folder_default)
                error(R.drawable.ic_folder_default)
            }
        } else {
            icon.load(R.drawable.ic_folder_default)
        }
        icon.setOnClickListener(null)

        name.text = "📁 ${folder.name}"
        info.text = ""
        expiryInfo.visibility = View.GONE
        lentInfo.visibility = View.GONE

        colorBar.setBackgroundColor(
            ContextCompat.getColor(activity, R.color.colorNormal)
        )

        view.setOnClickListener { openNestedFolder(folder) }

        return view
    }

    private fun bindNestedItem(item: ItemEntity): View {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.item_catalog_entry, binding.nestedContainer, false)

        val icon = view.findViewById<ImageView>(R.id.icon)
        val name = view.findViewById<TextView>(R.id.name)
        val info = view.findViewById<TextView>(R.id.info)
        val expiryInfo = view.findViewById<TextView>(R.id.expiryInfo)
        val lentInfo = view.findViewById<TextView>(R.id.lentInfo)
        val colorBar = view.findViewById<View>(R.id.colorBar)

        val localFile = ImageUtils.getLocalImageFile(activity, item.id)
        if (localFile != null && localFile.exists()) {
            icon.load(localFile) {
                crossfade(true)
                placeholder(R.drawable.ic_item_default)
                error(R.drawable.ic_item_default)
            }
            icon.setOnClickListener {
                val intent = Intent(activity, FullscreenImageActivity::class.java).apply {
                    putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, localFile.absolutePath)
                    putExtra(FullscreenImageActivity.EXTRA_TITLE, item.name)
                }
                activity.startActivity(intent)
            }
        } else {
            icon.load(R.drawable.ic_item_default)
            icon.setOnClickListener(null)
        }

        val typeEmoji = when (item.itemType) {
            "food" -> "🍎"
            "medicine" -> "💊"
            "thing" -> "📦"
            else -> "🗂"
        }
        name.text = "$typeEmoji ${item.name}"

        val infoParts = mutableListOf<String>()
        if (item.quantity > 1) {
            infoParts.add("×${item.quantity}")
        }
        if (item.price != null && item.price != 0.0) {
            val priceStr = if (item.price % 1.0 == 0.0) {
                item.price.toInt().toString()
            } else {
                String.format("%.2f", item.price)
            }
            infoParts.add("${priceStr} ₽")
        }
        info.text = infoParts.joinToString("  •  ")

        if (item.isExpired) {
            expiryInfo.visibility = View.VISIBLE
            expiryInfo.text = "⚠️ Просрочен"
            expiryInfo.setTextColor(ContextCompat.getColor(activity, android.R.color.holo_red_dark))
        } else if (item.daysUntilExpiry != Int.MAX_VALUE && item.daysUntilExpiry <= 7) {
            expiryInfo.visibility = View.VISIBLE
            expiryInfo.text = "⏰ Осталось ${item.daysUntilExpiry} дн."
            expiryInfo.setTextColor(ContextCompat.getColor(activity, android.R.color.holo_orange_dark))
        } else {
            expiryInfo.visibility = View.GONE
        }

        if (item.isLent && !item.lentTo.isNullOrEmpty()) {
            lentInfo.visibility = View.VISIBLE
            var text = "🤝 У ${item.lentTo}"
            if (!item.lentNote.isNullOrEmpty()) {
                text += " (${item.lentNote})"
            }
            lentInfo.text = text
        } else {
            lentInfo.visibility = View.GONE
        }

        val colorRes = when {
            item.isLent -> android.R.color.holo_orange_light
            item.isExpired -> R.color.colorExpired
            item.daysUntilExpiry in 0..3 -> R.color.colorWarning
            else -> R.color.colorNormal
        }
        colorBar.setBackgroundColor(ContextCompat.getColor(activity, colorRes))

        view.setOnClickListener { openNestedItem(item) }

        return view
    }

    // ============================================================
    // ПЕРЕХОДЫ
    // ============================================================

    private fun openNestedFolder(folder: FolderEntity) {
        Logger.log(TAG, "openNestedFolder: ${folder.name} (id=${folder.id})")
        onNavigateToFolder(folder.id)
    }

    private fun openNestedItem(item: ItemEntity) {
        Logger.log(TAG, "openNestedItem: ${item.name} (id=${item.id})")
        onOpenNestedItem(item.id)
    }
}
