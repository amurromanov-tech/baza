package com.family.base.ui.adapter

import android.view.LayoutInflater
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
 */
class ReceiptItemsAdapter : ListAdapter<ReceiptRepository.ReceiptItem, ReceiptItemsAdapter.ViewHolder>(
    DiffCallback
) {

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

        fun bind(item: ReceiptRepository.ReceiptItem) {
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

    private object DiffCallback : DiffUtil.ItemCallback<ReceiptRepository.ReceiptItem>() {
        override fun areItemsTheSame(
            oldItem: ReceiptRepository.ReceiptItem,
            newItem: ReceiptRepository.ReceiptItem
        ): Boolean {
            return oldItem.name == newItem.name &&
                oldItem.priceKopecks == newItem.priceKopecks &&
                oldItem.quantity == newItem.quantity
        }

        override fun areContentsTheSame(
            oldItem: ReceiptRepository.ReceiptItem,
            newItem: ReceiptRepository.ReceiptItem
        ): Boolean {
            return oldItem == newItem
        }
    }
}
