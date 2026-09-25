package com.family.base.data.parser

import com.family.base.data.model.CheckItem
import com.family.base.util.Logger

/**
 * Парсер текста чека.
 * 
 * Получает сырой текст от ML Kit OCR и превращает его в список CheckItem.
 * Использует эвристики: 
 *  - какие строки — мусор (ИТОГО, Кассир и т.д.)
 *  - какие строки содержат товар
 *  - как извлечь название, количество, цену
 */
object CheckParser {

    private const val TAG = "CheckParser"

    // ===== СТРОКИ-МУСОР (пропускаем) =====
    private val IGNORE_KEYWORDS = listOf(
        "итого", "итог", "сдача", "сдач", "наличн", "безналичн", "карт",
        "ндс", "сумма", "всего", "оплата", "оплачено", "счет", "счёт",
        "кассир", "фио", "продавец", "смена", "см.",
        "инн", "кпп", "огрн", "чек", "ккт", "ккм", "фн", "фд", "фп", "фпд",
        "смена", "z-отчет", "z-отчёт",
        "дата", "время", "адрес", "тел", "телефон", "сайт", "www",
        "спасибо", "благодарим", "покупайте", "скидка", "акция", "купон",
        "фн", "рн ккт", "№", "предчек", "касс",
        "мы", "вы", "приход", "расход",
        "ООО", "ОАО", "ЗАО", "ИП", "АО",
        "г.", "ул.", "д.", "кв.", "офис",
        "+7", "8-", "8(", "7(",
        "наименование", "кол-во", "цена", "стоимость", "сумма"
    )

    // ===== РЕГУЛЯРКИ =====
    // Цена: 89.90 или 89,90 или 1 234.56 или 1 234,56
    private val PRICE_REGEX = Regex("""(\d{1,3}(?:[\s\u00A0]?\d{3})*(?:[.,]\d{2})|\d+(?:[.,]\d{2}))""")

    // Количество × Цена: "2 x 89.90", "2х89.90", "2 × 89,90"
    private val QTY_TIMES_PRICE_REGEX = Regex(
        """(\d+[.,]?\d*)\s*[xх×X*]\s*(\d{1,3}(?:[\s\u00A0]?\d{3})*(?:[.,]\d{2})|\d+(?:[.,]\d{2}))"""
    )

    // Артикул/код в начале: "000012345"
    private val LEADING_CODE_REGEX = Regex("""^\d{6,}\s+""")

    /**
     * Главный метод: текст чека → список товаров.
     */
    fun parse(rawText: String): List<CheckItem> {
        Logger.log(TAG, "=== parse START, text length=${rawText.length} ===")

        if (rawText.isBlank()) {
            return emptyList()
        }

        // Разбиваем на строки, убираем пустые
        val lines = rawText
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        Logger.log(TAG, "Lines count: ${lines.size}")

        val items = mutableListOf<CheckItem>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val nextLine = lines.getOrNull(i + 1)

            // Пропускаем строки-мусор
            if (isGarbageLine(line)) {
                i++
                continue
            }

            // Пробуем распарсить как товар
            val item = tryParseItem(line, nextLine)

            if (item != null) {
                items.add(item)
                // Если использовали nextLine — перескакиваем через неё
                if (item.rawLine?.contains("\n") == true) {
                    i += 2
                } else {
                    i++
                }
            } else {
                i++
            }
        }

        Logger.log(TAG, "Parsed ${items.size} items")
        items.forEach { Logger.log(TAG, "  → ${it.name} | ${it.quantity} x ${it.price}") }

        return items
    }

    /**
     * Проверяем, является ли строка мусором (служебная).
     */
    private fun isGarbageLine(line: String): Boolean {
        val lower = line.lowercase()

        // Слишком короткая
        if (line.length < 3) return true

        // Содержит служебные слова
        for (keyword in IGNORE_KEYWORDS) {
            if (lower.contains(keyword)) return true
        }

        // Только цифры и разделители (штрих-код, номер чека)
        val onlyDigitsAndSymbols = line.all { it.isDigit() || it in "-+*. #№" }
        if (onlyDigitsAndSymbols) return true

        // Похоже на дату/время: 25.09.2026, 14:35
        if (Regex("""^\d{1,2}[./]\d{1,2}[./]\d{2,4}""").containsMatchIn(line)) return true
        if (Regex("""^\d{1,2}:\d{2}""").containsMatchIn(line)) return true

        return false
    }

    /**
     * Пытаемся распарсить строку как товар.
     * Возвращает CheckItem или null, если не похоже на товар.
     *
     * @param line текущая строка
     * @param nextLine следующая строка (может содержать количество и цену)
     */
    private fun tryParseItem(line: String, nextLine: String?): CheckItem? {

        // ===== СЛУЧАЙ 1: всё в одной строке: "Молоко 2 x 89.90  179.80" =====
        val qtyMatch = QTY_TIMES_PRICE_REGEX.find(line)
        if (qtyMatch != null) {
            val name = line.substring(0, qtyMatch.range.first).trim()
                .replace(LEADING_CODE_REGEX, "")
                .trim()

            if (name.length < 2) return null

            val qty = qtyMatch.groupValues[1].replace(',', '.').toDoubleOrNull()?.toInt() ?: 1
            val price = qtyMatch.groupValues[2].replace(',', '.').replace(" ", "").toDoubleOrNull()

            return CheckItem(
                name = cleanName(name),
                quantity = qty.coerceAtLeast(1),
                price = price,
                rawLine = line
            )
        }

        // ===== СЛУЧАЙ 2: количество и цена в следующей строке =====
        // Строка: "Молоко Простоквашино 1л"
        // След: "  2 x 89.90    179.80"
        if (nextLine != null) {
            val nextQtyMatch = QTY_TIMES_PRICE_REGEX.find(nextLine)
            if (nextQtyMatch != null) {
                val name = line.replace(LEADING_CODE_REGEX, "").trim()
                if (name.length >= 2 && !isGarbageLine(name)) {
                    val qty = nextQtyMatch.groupValues[1].replace(',', '.').toDoubleOrNull()?.toInt() ?: 1
                    val price = nextQtyMatch.groupValues[2].replace(',', '.').replace(" ", "").toDoubleOrNull()

                    return CheckItem(
                        name = cleanName(name),
                        quantity = qty.coerceAtLeast(1),
                        price = price,
                        rawLine = "$line\n$nextLine"
                    )
                }
            }
        }

        // ===== СЛУЧАЙ 3: название + цена без количества в одной строке =====
        // "Молоко Простоквашино 1л   89.90"
        // (редко, но бывает)
        val priceMatch = PRICE_REGEX.find(line)
        if (priceMatch != null && priceMatch.range.first > 3) {
            val name = line.substring(0, priceMatch.range.first).trim()
                .replace(LEADING_CODE_REGEX, "")
                .trim()
            if (name.length >= 3 && !isGarbageLine(name)) {
                val price = priceMatch.value.replace(',', '.').replace(" ", "").toDoubleOrNull()
                if (price != null && price > 0 && price < 100000) {
                    return CheckItem(
                        name = cleanName(name),
                        quantity = 1,
                        price = price,
                        rawLine = line
                    )
                }
            }
        }

        // ===== СЛУЧАЙ 4: только название, без цены =====
        // (весовой товар или цена в третьей строке)
        // Пробуем: если следующая строка — только цена/вес
        if (nextLine != null) {
            val nextPriceMatch = PRICE_REGEX.find(nextLine)
            if (nextPriceMatch != null && !QTY_TIMES_PRICE_REGEX.containsMatchIn(nextLine)) {
                val name = line.replace(LEADING_CODE_REGEX, "").trim()
                if (name.length >= 3 && !isGarbageLine(name)) {
                    val price = nextPriceMatch.value.replace(',', '.').replace(" ", "").toDoubleOrNull()
                    if (price != null && price > 0 && price < 100000) {
                        return CheckItem(
                            name = cleanName(name),
                            quantity = 1,
                            price = price,
                            rawLine = "$line\n$nextLine"
                        )
                    }
                }
            }
        }

        // Не товар
        return null
    }

    /**
     * Очистка названия: убираем лишние символы, но оставляем русские буквы, цифры и пробелы.
     */
    private fun cleanName(name: String): String {
        return name
            .replace(Regex("""[«»"']"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(120)
    }
}
