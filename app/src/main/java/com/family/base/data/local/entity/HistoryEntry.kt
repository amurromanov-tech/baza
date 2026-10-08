package com.family.base.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val action: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val changedBy: String,
    val changedAt: Long = System.currentTimeMillis(),
    /**
     * Имя предмета на момент записи (для отображения в истории после удаления).
     * Заполняется для всех операций с предметами.
     * Для операций с папками — имя папки.
     */
    val itemName: String? = null
)

/*
 * Список action (для справки):
 *  - "create"              — создание предмета
 *  - "update"              — изменение полей (имя, кол-во, цена, срок, тип, подтип, дата покупки, штрих-код, описание)
 *  - "quantity_change"     — быстрое изменение количества (без захода в редактирование)
 *  - "delete"              — удаление предмета
 *  - "archive"             — архивация (вручную или через списание всего)
 *  - "unarchive"           — возврат из архива
 *  - "unarchive_part"      — возврат части (слияние с оригиналом)
 *  - "write_off"           — списание в архив (новая запись)
 *  - "write_off_part"      — частичное списание с оригинала
 *  - "move"                — перемещение предмета
 *  - "split_in"            — отделённая часть (новая запись после split)
 *  - "split_out"           — оригинал после split
 *  - "revision"            — отметка ревизии
 *  - "lend"                — выдача
 *  - "return"              — возврат займа
 *  - "create_folder"       — создание папки
 *  - "rename_folder"       — переименование папки
 *  - "delete_folder"       — удаление папки
 *  - "move_folder"         — перемещение папки
 *  - "add_nested_folder"   — создание вложенной папки внутри предмета
 *  - "copy"                — копирование предмета
 */
