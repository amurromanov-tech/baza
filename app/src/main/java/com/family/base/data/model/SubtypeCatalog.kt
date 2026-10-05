package com.family.base.data.model

import java.util.Locale

/**
 * Справочник подтипов для каждого типа предмета.
 *
 * Использование:
 *   val subtypes = SubtypeCatalog.getSubtypes("food")
 *   val display = SubtypeCatalog.getDisplayName("food", "dairy")  // "🥛 Молочка"
 *
 * 🆕 БАЗА7: getSubtypes() возвращает список, отсортированный по алфавиту
 *           (по тексту после эмодзи, русская локаль).
 */
object SubtypeCatalog {

    /**
     * Одна опция подтипа — ключ + отображаемое имя (с эмодзи).
     */
    data class Subtype(val key: String, val displayName: String)

    // ============================================================
    // 🍎 ЕДА
    // ============================================================
    // Примечание: список в исходнике в произвольном порядке —
    // сортировка по алфавиту делается в getSubtypes().
    private val FOOD = listOf(
        Subtype("dairy",         "🥛 Молочка"),
        Subtype("bakery",        "🍞 Хлебобулочные"),
        Subtype("canned",        "🥫 Консервы"),
        Subtype("meat_fresh",    "🥩 Мясо свежее"),
        Subtype("fish_fresh",    "🐟 Рыба свежая"),
        Subtype("vegetables",    "🥦 Овощи"),
        Subtype("fruits",        "🍎 Фрукты"),
        Subtype("grocery",       "🍝 Бакалея"),
        Subtype("sweets",        "🍬 Сладости"),
        Subtype("drinks",        "🥤 Напитки"),
        Subtype("spices",        "🧂 Приправы"),
        Subtype("frozen",        "🧊 Заморозка"),
        Subtype("semi_finished", "🍱 Полуфабрикаты"),
        Subtype("salad",         "🥗 Салаты"),
        Subtype("soup",          "🍲 Супы"),
        Subtype("hot_appetizer", "🍢 Горячие закуски"),
        Subtype("food_other",    "🍽 Прочее (еда)")
    )

    // ============================================================
    // 💊 ЛЕКАРСТВО
    // ============================================================
    private val MEDICINE = listOf(
        Subtype("antibiotics",    "💉 Антибиотики"),
        Subtype("antipyretic",    "🌡 Жаропонижающие"),
        Subtype("cold",           "🤧 От простуды"),
        Subtype("vitamins",       "💊 Витамины"),
        Subtype("painkiller",     "🩹 Обезболивающие"),
        Subtype("gastro",         "💚 Для ЖКТ"),
        Subtype("cardio",         "🫀 Для сердца"),
        Subtype("external",       "🧴 Наружные"),
        Subtype("allergy",        "🌿 От аллергии"),
        Subtype("sedative",       "😌 Успокоительные"),
        Subtype("medicine_other", "🩺 Прочее (лекарства)")
    )

    // ============================================================
    // 📦 ПРЕДМЕТ
    // ============================================================
    private val THING = listOf(
        Subtype("tool_manual",   "🔧 Инструмент (ручной)"),
        Subtype("tool_electric", "⚡ Электроинструмент"),
        Subtype("electric",      "🔌 Электрика"),
        Subtype("stationery",    "✏️ Канцелярия"),
        Subtype("cosmetics",     "🧴 Косметика"),
        Subtype("house_chem",    "🧼 Бытовая химия"),
        Subtype("shoes",         "👟 Обувь"),
        Subtype("clothes",       "👕 Одежда"),
        Subtype("household",     "🏠 Хозтовары"),
        Subtype("electronics",   "💻 Электроника"),
        Subtype("computer",      "🖥 Для компьютера"),
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
        Subtype("documents",   "📄 Документы"),
        Subtype("money",       "💰 Деньги / Ценности"),
        Subtype("other_other", "🗂 Прочее (другое)")
    )

    /**
     * Возвращает список подтипов для указанного типа, отсортированный
     * по алфавиту (по тексту после эмодзи, русская локаль).
     *
     * Если тип неизвестен или null — возвращает `OTHER`.
     */
    fun getSubtypes(type: String?): List<Subtype> {
        val list = when (type) {
            "food" -> FOOD
            "medicine" -> MEDICINE
            "thing" -> THING
            "other" -> OTHER
            else -> OTHER
        }
        return list.sortedWith(SUBTYPE_ALPHABETICAL_ORDER)
    }

    /**
     * Компаратор: сортировка по тексту без эмодзи (по первой букве
     * «чистого» имени), русская локаль.
     *
     * Логика:
     *   1. Берём displayName (например, "🥛 Молочка").
     *   2. Отрезаем всё до первого пробела — остаётся "Молочка".
     *   3. Сравниваем строки через String.CASE_INSENSITIVE_ORDER
     *      с учётом русской локали.
     */
    private val SUBTYPE_ALPHABETICAL_ORDER: Comparator<Subtype> =
        Comparator { a, b ->
            val nameA = cleanName(a.displayName)
            val nameB = cleanName(b.displayName)
            nameA.compareTo(nameB, ignoreCase = true)
        }

    /**
     * Убирает эмодзи и пробелы в начале: "🥛 Молочка" → "Молочка".
     * Если в строке нет пробела — возвращает всю строку без изменений.
     */
    private fun cleanName(displayName: String): String {
        val spaceIdx = displayName.indexOf(' ')
        return if (spaceIdx in 0 until displayName.length - 1) {
            displayName.substring(spaceIdx + 1).trim()
        } else {
            displayName.trim()
        }
    }

    /**
     * Возвращает отображаемое имя подтипа (с эмодзи).
     * Если ключ не найден — возвращает `null`.
     *
     * ⚠️ БАЗА7: ключ "meat_fish" удалён. Старые предметы с этим ключом
     *          вернут null — подтип просто не отобразится.
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
