package com.family.base.ui.adapter

import com.family.base.data.local.entity.TaskEntity
import java.util.Calendar

/**
 * Секция списка «🏠 Домашние дела»: заголовок + задачи.
 *
 * Используется в TaskListActivity для группировки. Сам по себе
 * не адаптер — это просто data-класс + объект-группировщик.
 *
 * 🆕 v20.0.0 (шаг 3): переработка окна «Домашние дела».
 *
 * Группировка (в порядке отображения):
 *  1. 🔴 ПРОСРОЧЕНО       — dueDate/remindAt в прошлом
 *  2. 🟠 СЕГОДНЯ          — dueDate/remindAt попадает в сегодня
 *  3. 🟡 ЗАВТРА           — dueDate/remindAt попадает в завтра
 *  4. ⚪ НА ЭТОЙ НЕДЕЛЕ    — послезавтра … воскресенье текущей недели
 *  5. ⚫ ПОТОМ             — позже конца текущей недели
 *  6. ▫️ БЕЗ НАПОМИНАНИЙ   — dueDate = null и remindAt = null (чек-лист)
 *
 * Пустые секции не показываются.
 * «Без напоминаний» — всегда последняя (перед выполненными, если те включены).
 *
 * Выполненные задачи (isDone = true) в эту группировку не попадают:
 * они либо скрыты (фильтр ACTIVE), либо показываются плоским списком
 * (фильтр DONE), либо отдельной секцией в конце (фильтр ALL).
 */
data class TaskSection(
    val type: Type,
    val title: String,
    val tasks: List<TaskEntity>
) {
    enum class Type {
        OVERDUE,
        TODAY,
        TOMORROW,
        THIS_WEEK,
        LATER,
        NO_REMINDER,
        DONE
    }

    val isEmpty: Boolean get() = tasks.isEmpty()
}

/**
 * Логика группировки задач по времени.
 *
 * Все вычисления — относительно «сейчас» и локального часового пояса.
 * Сравнение по «дню» (начало/конец суток), а не по точному timestamp:
 * задача с dueDate = сегодня 23:59 попадает в «Сегодня», а не в «Просрочено»,
 * даже если сейчас 22:00.
 */
object TaskSectionGrouper {

    private const val TITLE_OVERDUE      = "🔴 Просрочено"
    private const val TITLE_TODAY        = "🟠 Сегодня"
    private const val TITLE_TOMORROW     = "🟡 Завтра"
    private const val TITLE_THIS_WEEK    = "⚪ На этой неделе"
    private const val TITLE_LATER        = "⚫ Потом"
    private const val TITLE_NO_REMINDER  = "▫️ Без напоминаний"
    private const val TITLE_DONE         = "✅ Выполненные"

    /**
     * Сгруппировать активные задачи (isDone = false).
     *
     * @param tasks   активные задачи, уже отсортированные (порядок внутри
     *                секций сохраняется как есть — TaskListActivity вызывает
     *                эту функцию после applySort).
     * @param nowMs   текущее время (для тестов можно передать любое).
     */
    fun groupActive(tasks: List<TaskEntity>, nowMs: Long = System.currentTimeMillis()): List<TaskSection> {
        if (tasks.isEmpty()) return emptyList()

        val overdue = mutableListOf<TaskEntity>()
        val today = mutableListOf<TaskEntity>()
        val tomorrow = mutableListOf<TaskEntity>()
        val thisWeek = mutableListOf<TaskEntity>()
        val later = mutableListOf<TaskEntity>()
        val noReminder = mutableListOf<TaskEntity>()

        val dayStartToday = dayStart(nowMs)
        val dayStartTomorrow = dayStartToday + 24L * 60L * 60L * 1000L
        val dayStartAfterTomorrow = dayStartTomorrow + 24L * 60L * 60L * 1000L
        val dayStartNextWeek = startOfNextWeek(nowMs)

        for (task in tasks) {
            // «Эффективная» дата задачи: remindAt имеет приоритет (Путь 2),
            // иначе dueDate. Если ни одного — задача без напоминаний.
            val effectiveAt = task.remindAt ?: task.dueDate

            if (effectiveAt == null) {
                noReminder.add(task)
                continue
            }

            when {
                effectiveAt < nowMs -> overdue.add(task)
                effectiveAt < dayStartTomorrow -> today.add(task)
                effectiveAt < dayStartAfterTomorrow -> tomorrow.add(task)
                effectiveAt < dayStartNextWeek -> thisWeek.add(task)
                else -> later.add(task)
            }
        }

        val result = mutableListOf<TaskSection>()
        if (overdue.isNotEmpty()) {
            result.add(TaskSection(TaskSection.Type.OVERDUE, TITLE_OVERDUE, overdue))
        }
        if (today.isNotEmpty()) {
            result.add(TaskSection(TaskSection.Type.TODAY, TITLE_TODAY, today))
        }
        if (tomorrow.isNotEmpty()) {
            result.add(TaskSection(TaskSection.Type.TOMORROW, TITLE_TOMORROW, tomorrow))
        }
        if (thisWeek.isNotEmpty()) {
            result.add(TaskSection(TaskSection.Type.THIS_WEEK, TITLE_THIS_WEEK, thisWeek))
        }
        if (later.isNotEmpty()) {
            result.add(TaskSection(TaskSection.Type.LATER, TITLE_LATER, later))
        }
        if (noReminder.isNotEmpty()) {
            result.add(TaskSection(TaskSection.Type.NO_REMINDER, TITLE_NO_REMINDER, noReminder))
        }
        return result
    }

    /**
     * Сгруппировать выполненные задачи — все в одну секцию «✅ Выполненные».
     * Порядок сохраняется как пришёл (TaskListActivity передаёт после applySort).
     */
    fun groupDone(doneTasks: List<TaskEntity>): List<TaskSection> {
        if (doneTasks.isEmpty()) return emptyList()
        return listOf(TaskSection(TaskSection.Type.DONE, TITLE_DONE, doneTasks))
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНОЕ
    // ============================================================

    /** Начало суток (00:00:00.000) для переданного timestamp. */
    private fun dayStart(ts: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    /**
     * Начало следующей недели (понедельник 00:00), идущее после текущего дня.
     * Если сегодня воскресенье — это завтрашний понедельник.
     */
    private fun startOfNextWeek(ts: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Calendar.DAY_OF_WEEK: воскресенье = 1, понедельник = 2, …
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val daysUntilNextMonday = when (dow) {
            Calendar.MONDAY -> 7
            Calendar.TUESDAY -> 6
            Calendar.WEDNESDAY -> 5
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 3
            Calendar.SATURDAY -> 2
            Calendar.SUNDAY -> 1
            else -> 7
        }
        c.add(Calendar.DAY_OF_YEAR, daysUntilNextMonday)
        return c.timeInMillis
    }
}
