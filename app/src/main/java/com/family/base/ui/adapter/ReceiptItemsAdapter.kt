package com.family.base.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.family.base.data.repository.ReceiptRepository
import com.family.base.databinding.ItemReceiptPositionBinding
import java.util.Locale

/**
 * 🆕 Адаптер для списка позиций чека (ReceiptPreviewActivity).
 *
 * Суммы и цены приходят в копейках (как отдаёт proverkacheka.com),
 * здесь конвертируются в рубли для отображения.
 *
 * 🆕 ЧЕКИ + ИСТОРИЯ: у позиции может быть бейдж «🔄» — если в БД
 * нашёлся эталон (архивный или активный товар с таким же именем/штрих-кодом).
 * Наличие бейджа подсказывает пользователю, что поля будут заполнены
 * автоматически (A) или появится диалог выбора (B).
 *
 * Модель для отображения — ReceiptPositionUi:
 *  - item: ReceiptRepository.ReceiptItem (данные из чека)
 *  - hasMatch: Boolean (найден эталон в истории)
 *  - matchCount: Int (сколько эталонов найдено: 1 — авто, >1 — диалог)
 */
class ReceiptItemsAdapter : ListAdapter<ReceiptItemsAdapter.ReceiptPositionUi, ReceiptItemsAdapter.ViewHolder>(
    DiffCallback
) {

    /**
     * UI-модель позиции чека.
     */
    data class ReceiptPositionUi(
        val item: ReceiptRepository.ReceiptItem,
        val hasMatch: Boolean = false,
        val matchCount: Int = 0
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemReceiptPositionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemReceiptPositionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(ui: ReceiptPositionUi) {
            val item = ui.item

            binding.tvName.text = item.name.ifBlank { "—" }

            binding.tvPriceQuantity.text = String.format(
                Locale.getDefault(),
                "%.2f ₽ × %s",
                item.priceKopecks / 100.0,
                formatQuantity(item.quantity)
            )
            binding.tvSum.text = String.format(
                Locale.getDefault(),
                "%.2f ₽",
                item.sumKopecks / 100.0
            )

            // 🆕 Бейдж «🔄» — если найден эталон в истории
            if (ui.hasMatch) {
                binding.tvMatchBadge.visibility = View.VISIBLE
                binding.tvMatchBadge.text = when {
                    ui.matchCount > 1 -> "🔄 ${ui.matchCount} варианта"
                    else -> "🔄 из истории"
                }
            } else {
                binding.tvMatchBadge.visibility = View.GONE
            }
        }

        /**
         * Количество: 1 → "1", 1.301 → "1.301", 0.5 → "0.5".
         * Убираем лишние нули после запятой.
         */
        private fun formatQuantity(q: Double): String {
            return if (q == q.toLong().toDouble()) {
                q.toLong().toString()
            } else {
                q.toString().trimEnd('0').trimEnd('.')
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ReceiptPositionUi>() {
        override fun areItemsTheSame(
            oldItem: ReceiptPositionUi,
            newItem: ReceiptPositionUi
        ): Boolean {
            return oldItem.item.name == newItem.item.name &&
                oldItem.item.priceKopecks == newItem.item.priceKopecks &&
                oldItem.item.quantity == newItem.item.quantity
        }

        override fun areContentsTheSame(
            oldItem: ReceiptPositionUi,
            newItem: ReceiptPositionUi
        ): Boolean {
            return oldItem == newItem
        }
    }
}
