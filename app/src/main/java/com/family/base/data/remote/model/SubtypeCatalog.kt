package com.family.base.data.model

/**
 * Справочник подтипов для каждого типа предмета.
 *
 * Использование:
 *   val subtypes = SubtypeCatalog.getSubtypes("food")
 *   val display = SubtypeCatalog.getDisplayName("food", "dairy")  // "🥛 Молочка"
 */
object SubtypeCatalog {

    /**
     * Одна опция подтипа — ключ + отображаемое имя (с эмодзи).
     */
    data class Subtype(val key: String, val displayName: String)

    // ============================================================
    // 🍎 ЕДА
    // ============================================================
    private val FOOD = listOf(
        Subtype("dairy",         "🥛 Молочка"),
        Subtype("bakery",        "🍞 Хлебобулочные"),
        Subtype("canned",        "🥫 Консервы"),
        Subtype("meat_fish",     "🥩 Мясо / Рыба"),
        Subtype("vegetables",    "🥦 Овощи / Фрукты"),
        Subtype("grocery",       "🍝 Бакалея"),
        Subtype("sweets",        "🍬 Сладости"),
        Subtype("drinks",        "🥤 Напитки"),
        Subtype("spices",        "🧂 Приправы"),
        Subtype("frozen",        "🧊 Заморозка"),
        Subtype("semi_finished", "🍱 Полуфабрикаты"),
        Subtype("food_other",    "🍽 Прочее (еда)")
    )

    // ============================================================
    // 💊 ЛЕКАРСТВО
    // ============================================================
    private val MEDICINE = listOf(
        Subtype("antibiotics",   "💉 Антибиотики"),
        Subtype("antipyretic",   "🌡 Жаропонижающие"),
        Subtype("cold",          "🤧 От простуды"),
        Subtype("vitamins",      "💊 Витамины"),
        Subtype("painkiller",    "🩹 Обезболивающие"),
        Subtype("gastro",        "💚 Для ЖКТ"),
        Subtype("cardio",        "🫀 Для сердца"),
        Subtype("external",      "🧴 Наружные"),
        Subtype("allergy",       "🌿 От аллергии"),
        Subtype("sedative",      "😌 Успокоительные"),
        Subtype("medicine_other","🩺 Прочее (лекарства)")
    )

    // ============================================================
    // 📦 ПРЕДМЕТ
    // ============================================================
    private val THING = listOf(
        Subtype("tool_manual",   "🔧 Инструмент (ручной)"),
        Subtype("tool_electric", "⚡ Электроинструмент"),
        Subtype("electric",      "🔌 Электрика"),
        Subtype("stationery",    "✏️ Канцелярия"),
        Subtype("house_chem",    "🧴 Бытовая химия"),
        Subtype("clothes",       "👕 Одежда"),
        Subtype("household",     "🏠 Хозтовары"),
        Subtype("electronics",   "💻 Электроника"),
        Subtype("kids",          "🧸 Детское"),
        Subtype("kitchen",       "🍳 Кухонное"),
        Subtype("repair",        "🛠 Ремонт / Стройка"),
        Subtype("car",           "🚗 Для авто"),
        Subtype("garden",        "🌱 Сад / Огород"),
        Subtype("thing_other",   "📦 Прочее (предметы)")
    )

    // ============================================================
    // 🗂 ДРУГОЕ
    // ============================================================
    private val OTHER = listOf(
        Subtype("documents",     "📄 Документы"),
        Subtype("money",         "💰 Деньги / Ценности"),
        Subtype("other_other",   "🗂 Прочее (другое)")
    )

    /**
     * Возвращает список подтипов для указанного типа.
     * Если тип неизвестен или null — возвращает `OTHER`.
     */
    fun getSubtypes(type: String?): List<Subtype> {
        return when (type) {
            "food" -> FOOD
            "medicine" -> MEDICINE
            "thing" -> THING
            "other" -> OTHER
            else -> OTHER
        }
    }

    /**
     * Возвращает отображаемое имя подтипа (с эмодзи).
     * Если ключ не найден — возвращает `null`.
     */
    fun getDisplayName(type: String?, subtypeKey: String?): String? {
        if (subtypeKey.isNullOrEmpty()) return null
        return getSubtypes(type).firstOrNull { it.key == subtypeKey }?.displayName
    }

    /**
     * Проверяет, существует ли подтип для указанного типа.
     */
    fun isValid(type: String?, subtypeKey: String?): Boolean {
        if (subtypeKey.isNullOrEmpty()) return false
        return getSubtypes(type).any { it.key == subtypeKey }
    }
}
