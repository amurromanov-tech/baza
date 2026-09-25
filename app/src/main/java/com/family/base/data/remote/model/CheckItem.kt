package com.family.base.data.model

/**
 * Один товар, распознанный из чека.
 * Используется как временная модель между парсером и preview.
 */
data class CheckItem(
    /** Название товара (как распознано, можно отредактировать) */
    var name: String,

    /** Количество (по умолчанию 1) */
    var quantity: Int = 1,

    /** Цена за единицу (не за позицию!) */
    var price: Double? = null,

    /** Выбран ли этот товар для добавления в базу */
    var isSelected: Boolean = true,

    /** Исходная строка из чека (для отладки и отображения) */
    var rawLine: String? = null
) {
    /**
     * Итоговая цена позиции: quantity × price
     */
    val totalPrice: Double?
        get() = if (price != null) price!! * quantity else null
}
