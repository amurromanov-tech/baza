package com.family.base.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.family.base.data.local.dao.*
import com.family.base.data.local.entity.*

@Database(
    entities = [
        FolderEntity::class,
        ItemEntity::class,
        HistoryEntry::class,
        SettingsEntity::class,
        LockEntity::class,
        SyncInfoEntity::class,
        TaskEntity::class,
        OutboxEntity::class,
        ProcessedChangelogEntity::class
    ],
    version = 19,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun itemDao(): ItemDao
    abstract fun historyDao(): HistoryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun lockDao(): LockDao
    abstract fun syncInfoDao(): SyncInfoDao
    abstract fun taskDao(): TaskDao
    abstract fun outboxDao(): OutboxDao
    abstract fun processedChangelogDao(): ProcessedChangelogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 2 НА 3 (АРХИВ)
        // ============================================================
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedReason TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedNote TEXT")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 3 НА 4 (ЗАЙМ)
        // ============================================================
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN isLent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN lentTo TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN lentDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN lentNote TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN returnDate INTEGER")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN itemSubtype TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
            }
        }

        // ============================================================
        // v12 → v13: пересоздаём settings с правильной схемой
        // ============================================================
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT 
                        id, 
                        isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3), 
                        COALESCE(notificationHour, 10), 
                        COALESCE(enableNotifications, 1), 
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())

                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
            }
        }

        // ============================================================
        // v13 → v14: системные папки + задачи «Домашние дела»
        // ============================================================
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        // ============================================================
        // v14 → v15: syncStatus + outbox (changelog)
        // ============================================================
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")
                db.execSQL("ALTER TABLE folders ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        id TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        changedBy TEXT NOT NULL,
                        isSynced INTEGER NOT NULL,
                        syncedAt INTEGER,
                        batchId TEXT,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_isSynced ON outbox(isSynced)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_entityType_entityId ON outbox(entityType, entityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_createdAt ON outbox(createdAt)")
            }
        }

        // ============================================================
        // 🆕 v15 → v16: processed_changelogs (учёт входящих пакетов)
        // ============================================================
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS processed_changelogs (
                        batchId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        changedBy TEXT,
                        createdAt INTEGER NOT NULL,
                        processedAt INTEGER NOT NULL,
                        entriesCount INTEGER NOT NULL,
                        appliedCount INTEGER NOT NULL,
                        isOwn INTEGER NOT NULL,
                        PRIMARY KEY(batchId)
                    )
                """.trimIndent())

                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_processed_changelogs_batchId ON processed_changelogs(batchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_processed_changelogs_deviceId ON processed_changelogs(deviceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_processed_changelogs_processedAt ON processed_changelogs(processedAt)")
            }
        }
                // ============================================================
        // 🆕 v16 → v17: системные папки «Покупки» и «Список покупок»
        //
        // Задача: превратить уже существующую папку «🛒 Покупки»
        // (если есть) в системную с фиксированным id, и создать
        // системную папку «🛒 Список покупок».
        //
        // ⚠️ Миграция НЕ пишет в outbox — иначе первый синк зальёт
        // псевдо-изменения. Changelog подхватит эти папки позже —
        // при следующем штатном изменении или через mergeData.
        // ============================================================
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // ── Шаг 1. Найти существующую «🛒 Покупки» (корневую) ──
                val oldId: String? = run {
                    val cursor = db.query(
                        "SELECT id FROM folders " +
                            "WHERE name = ? AND parentId IS NULL AND parentItemId IS NULL " +
                            "LIMIT 1",
                        arrayOf("🛒 Покупки")
                    )
                    var result: String? = null
                    if (cursor.moveToFirst()) {
                        result = cursor.getString(0)
                    }
                    cursor.close()
                    result
                }

                if (oldId != null && oldId != "system-folder-purchases") {
                    // Обновить id папки «Покупки» → фиксированный
                    db.execSQL(
                        "UPDATE folders SET id = 'system-folder-purchases', " +
                            "isSystem = 1, systemKey = 'purchases' " +
                            "WHERE id = ?",
                        arrayOf(oldId)
                    )

                    // Обновить parentId у детей: подпапки «Покупка DD.MM.YYYY #N»
                    db.execSQL(
                        "UPDATE folders SET parentId = 'system-folder-purchases' " +
                            "WHERE parentId = ?",
                        arrayOf(oldId)
                    )

                    // Обновить parentId у предметов внутри «Покупок»
                    db.execSQL(
                        "UPDATE items SET parentId = 'system-folder-purchases' " +
                            "WHERE parentId = ?",
                        arrayOf(oldId)
                    )

                } else if (oldId != null && oldId == "system-folder-purchases") {
                    // Уже системная — на всякий случай выставим флаги
                    db.execSQL(
                        "UPDATE folders SET isSystem = 1, systemKey = 'purchases' " +
                            "WHERE id = 'system-folder-purchases'"
                    )
                }
                // Если oldId == null — папки «Покупки» нет, создадим ниже пустую.

                // ── Шаг 2. Убедиться, что системная «🛒 Покупки» существует ──
                val purchasesExists: Boolean = run {
                    val cursor = db.query(
                        "SELECT 1 FROM folders WHERE id = 'system-folder-purchases' LIMIT 1"
                    )
                    val exists = cursor.moveToFirst()
                    cursor.close()
                    exists
                }

                if (!purchasesExists) {
                    val now = System.currentTimeMillis()
                    db.execSQL(
                        "INSERT INTO folders (" +
                            "id, name, parentId, parentItemId, createdAt, createdBy, updatedAt, " +
                            "path, iconUrl, iconThumbnailUrl, isSystem, systemKey, syncStatus" +
                            ") VALUES (" +
                            "'system-folder-purchases', '🛒 Покупки', NULL, NULL, ?, 'system', ?, " +
                            "'🛒 Покупки', NULL, NULL, 1, 'purchases', 'synced'" +
                            ")",
                        arrayOf(now, now)
                    )
                }

                // ── Шаг 3. Создать системную «🛒 Список покупок» (если нет) ──
                val shoppingExists: Boolean = run {
                    val cursor = db.query(
                        "SELECT 1 FROM folders WHERE id = 'system-folder-shopping-list' LIMIT 1"
                    )
                    val exists = cursor.moveToFirst()
                    cursor.close()
                    exists
                }

                if (!shoppingExists) {
                    val now = System.currentTimeMillis()
                    db.execSQL(
                        "INSERT INTO folders (" +
                            "id, name, parentId, parentItemId, createdAt, createdBy, updatedAt, " +
                            "path, iconUrl, iconThumbnailUrl, isSystem, systemKey, syncStatus" +
                            ") VALUES (" +
                            "'system-folder-shopping-list', '🛒 Список покупок', NULL, NULL, ?, 'system', ?, " +
                            "'🛒 Список покупок', NULL, NULL, 1, 'shopping_list', 'synced'" +
                            ")",
                        arrayOf(now, now)
                    )
                }
            }
        }

        // ============================================================
        // 🆕 v17 → v18: удаление мёртвой таблицы sync_queue
        //
        // Таблица sync_queue осталась с v16.0.5, когда отказались
        // от старой очереди в пользу outbox + changelog.
        // Никакой код её больше не использует (кроме BackupManagerV2,
        // где вызов clearAll() тоже убирается).
        //
        // Просто DROP TABLE. Никаких данных переносить не нужно —
        // таблица мёртвая, в ней нет актуальных данных.
        // ============================================================
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS sync_queue")
            }
        }

        // ============================================================
        // 🆕 v18 → v19: tombstones для удалений (№10)
        //
        // Добавляем в items, folders, tasks поля:
        //   isDeleted       BOOLEAN NOT NULL DEFAULT 0
        //   deletedAt       INTEGER NULL
        //   deletedBy       TEXT    NULL
        //   deleteConfirmed BOOLEAN NOT NULL DEFAULT 0
        //
        // В tasks колонка isDeleted УЖЕ БЫЛА (с v14) — её не трогаем,
        // добавляем только три новые.
        //
        // Существующие записи автоматически получают isDeleted=0,
        // то есть «живые» — данные не теряются.
        // ============================================================
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // items
                db.execSQL("ALTER TABLE items ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN deletedBy TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN deleteConfirmed INTEGER NOT NULL DEFAULT 0")

                // folders
                db.execSQL("ALTER TABLE folders ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE folders ADD COLUMN deletedBy TEXT")
                db.execSQL("ALTER TABLE folders ADD COLUMN deleteConfirmed INTEGER NOT NULL DEFAULT 0")

                // tasks — isDeleted уже есть, добавляем только 3
                db.execSQL("ALTER TABLE tasks ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN deletedBy TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN deleteConfirmed INTEGER NOT NULL DEFAULT 0")

                // Индексы для эффективного фильтра и cleanup
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_isDeleted ON items(isDeleted)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_isDeleted ON folders(isDeleted)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_isDeleted ON tasks(isDeleted)")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_deleteConfirmed_deletedAt ON items(deleteConfirmed, deletedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_deleteConfirmed_deletedAt ON folders(deleteConfirmed, deletedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_deleteConfirmed_deletedAt ON tasks(deleteConfirmed, deletedAt)")
            }
        }

        // ============================================================
        // ЦЕПОЧКИ «ЕСЛИ ПРОПУСТИЛИ» (старые)
        // ============================================================
        private val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedReason TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedNote TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN isLent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN lentTo TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN lentDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN lentNote TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN returnDate INTEGER")
            }
        }

        private val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedReason TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedNote TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN isLent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN lentTo TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN lentDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN lentNote TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN returnDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN itemSubtype TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN itemSubtype TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_5_7 = object : Migration(5, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_6_8 = object : Migration(6, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_7_9 = object : Migration(7, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_9_11 = object : Migration(9, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_9_12 = object : Migration(9, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_12 = object : Migration(10, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_13 = object : Migration(9, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
            }
        }

        private val MIGRATION_10_13 = object : Migration(10, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
            }
        }

        private val MIGRATION_11_13 = object : Migration(11, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
            }
        }

        // ============================================================
        // ЦЕПОЧКИ С ПРОПУСКОМ 13 (сразу в 14)
        // ============================================================
        private val MIGRATION_2_14 = object : Migration(2, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedReason TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedNote TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN isLent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN lentTo TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN lentDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN lentNote TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN returnDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN itemSubtype TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_3_14 = object : Migration(3, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN isLent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN lentTo TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN lentDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN lentNote TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN returnDate INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN itemSubtype TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_14 = object : Migration(4, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN itemSubtype TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_5_14 = object : Migration(5, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_6_14 = object : Migration(6, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_7_14 = object : Migration(7, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_8_14 = object : Migration(8, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_9_14 = object : Migration(9, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_10_14 = object : Migration(10, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_11_14 = object : Migration(11, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_12_14 = object : Migration(12, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE settings_new (
                        id INTEGER NOT NULL,
                        isFirstLaunch INTEGER NOT NULL,
                        notificationDaysBefore INTEGER NOT NULL,
                        notificationHour INTEGER NOT NULL,
                        enableNotifications INTEGER NOT NULL,
                        historyMigratedV12 INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO settings_new (id, isFirstLaunch, notificationDaysBefore, notificationHour, enableNotifications, historyMigratedV12)
                    SELECT id, isFirstLaunch, 
                        COALESCE(notificationDaysBefore, 3),
                        COALESCE(notificationHour, 10),
                        COALESCE(enableNotifications, 1),
                        COALESCE(historyMigratedV12, 0)
                    FROM settings
                """.trimIndent())
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_13_15 = object : Migration(13, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT,
                        priority INTEGER NOT NULL,
                        dueDate INTEGER,
                        isDone INTEGER NOT NULL,
                        doneAt INTEGER,
                        doneBy TEXT,
                        createdBy TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        recurrence INTEGER NOT NULL,
                        recurrenceSource TEXT,
                        sortOrder INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE items ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")
                db.execSQL("ALTER TABLE folders ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        id TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        changedBy TEXT NOT NULL,
                        isSynced INTEGER NOT NULL,
                        syncedAt INTEGER,
                        batchId TEXT,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_isSynced ON outbox(isSynced)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_entityType_entityId ON outbox(entityType, entityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_createdAt ON outbox(createdAt)")
            }
        }

        // ============================================================
        // 🆕 ЦЕПОЧКИ С ПРОПУСКОМ 15 (сразу в 16)
        // ============================================================
        private fun migrateTo16(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS processed_changelogs (
                    batchId TEXT NOT NULL,
                    deviceId TEXT NOT NULL,
                    changedBy TEXT,
                    createdAt INTEGER NOT NULL,
                    processedAt INTEGER NOT NULL,
                    entriesCount INTEGER NOT NULL,
                    appliedCount INTEGER NOT NULL,
                    isOwn INTEGER NOT NULL,
                    PRIMARY KEY(batchId)
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_processed_changelogs_batchId ON processed_changelogs(batchId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_processed_changelogs_deviceId ON processed_changelogs(deviceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_processed_changelogs_processedAt ON processed_changelogs(processedAt)")
        }

        private val MIGRATION_2_16 = object : Migration(2, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_3_16 = object : Migration(3, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_4_16 = object : Migration(4, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_5_16 = object : Migration(5, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_6_16 = object : Migration(6, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_6_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_7_16 = object : Migration(7, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_8_16 = object : Migration(8, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_8_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_9_16 = object : Migration(9, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_9_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_10_16 = object : Migration(10, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_10_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_11_16 = object : Migration(11, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_11_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_12_16 = object : Migration(12, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_12_14.migrate(db)
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        private val MIGRATION_13_16 = object : Migration(13, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_13_15.migrate(db)
                migrateTo16(db)
            }
        }

               private val MIGRATION_14_16 = object : Migration(14, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_14_15.migrate(db)
                migrateTo16(db)
            }
        }

        // ============================================================
        // 🆕 ЦЕПОЧКИ С ПРОПУСКОМ 16 (сразу в 17)
        //
        // Для каждой старой версии X: сначала применяем существующую
        // цепочку до 16, потом MIGRATION_16_17.
        // ============================================================
        private val MIGRATION_2_17 = object : Migration(2, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_3_17 = object : Migration(3, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_4_17 = object : Migration(4, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_5_17 = object : Migration(5, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_6_17 = object : Migration(6, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_6_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_7_17 = object : Migration(7, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_8_17 = object : Migration(8, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_8_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_9_17 = object : Migration(9, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_9_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_10_17 = object : Migration(10, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_10_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_11_17 = object : Migration(11, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_11_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_12_17 = object : Migration(12, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_12_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_13_17 = object : Migration(13, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_13_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_14_17 = object : Migration(14, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_14_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        private val MIGRATION_15_17 = object : Migration(15, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_15_16.migrate(db)
                MIGRATION_16_17.migrate(db)
            }
        }

        // ============================================================
        // 🆕 ЦЕПОЧКИ С ПРОПУСКОМ 17 (сразу в 18)
        //
        // Для каждой старой версии X: сначала применяем существующую
        // цепочку до 17, потом MIGRATION_17_18.
        // ============================================================
        private val MIGRATION_2_18 = object : Migration(2, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_3_18 = object : Migration(3, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_4_18 = object : Migration(4, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_5_18 = object : Migration(5, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_6_18 = object : Migration(6, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_6_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_7_18 = object : Migration(7, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_8_18 = object : Migration(8, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_8_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_9_18 = object : Migration(9, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_9_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_10_18 = object : Migration(10, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_10_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_11_18 = object : Migration(11, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_11_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_12_18 = object : Migration(12, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_12_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_13_18 = object : Migration(13, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_13_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_14_18 = object : Migration(14, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_14_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_15_18 = object : Migration(15, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_15_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_16_18 = object : Migration(16, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_16_17.migrate(db)
                MIGRATION_17_18.migrate(db)
            }
        }

        private val MIGRATION_17_18_DIRECT = MIGRATION_17_18

        // ============================================================
        // 🆕 ЦЕПОЧКИ С ПРОПУСКОМ 18 (сразу в 19)
        //
        // Для каждой старой версии X: сначала применяем существующую
        // цепочку до 18, потом MIGRATION_18_19.
        // ============================================================
        private val MIGRATION_17_19 = object : Migration(17, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_17_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_2_19 = object : Migration(2, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_3_19 = object : Migration(3, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_4_19 = object : Migration(4, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_5_19 = object : Migration(5, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_6_19 = object : Migration(6, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_6_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_7_19 = object : Migration(7, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_8_19 = object : Migration(8, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_8_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_9_19 = object : Migration(9, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_9_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_10_19 = object : Migration(10, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_10_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_11_19 = object : Migration(11, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_11_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_12_19 = object : Migration(12, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_12_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_13_19 = object : Migration(13, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_13_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_14_19 = object : Migration(14, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_14_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_15_19 = object : Migration(15, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_15_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_16_19 = object : Migration(16, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_16_18.migrate(db)
                MIGRATION_18_19.migrate(db)
            }
        }

        private val MIGRATION_18_19_DIRECT = MIGRATION_18_19

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "baza.db"
                )
                    .addMigrations(
                        // Основные шаги
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,

                        // Старые цепочки с пропуском 13 (до v14)
                        MIGRATION_2_4,
                        MIGRATION_2_5,
                        MIGRATION_4_6,
                        MIGRATION_5_7,
                        MIGRATION_6_8,
                        MIGRATION_7_9,
                        MIGRATION_9_11,
                        MIGRATION_9_12,
                        MIGRATION_10_12,
                        MIGRATION_9_13,
                        MIGRATION_10_13,
                        MIGRATION_11_13,

                        // Цепочки с пропуском 13 (сразу в 14)
                        MIGRATION_2_14,
                        MIGRATION_3_14,
                        MIGRATION_4_14,
                        MIGRATION_5_14,
                        MIGRATION_6_14,
                        MIGRATION_7_14,
                        MIGRATION_8_14,
                        MIGRATION_9_14,
                        MIGRATION_10_14,
                        MIGRATION_11_14,
                        MIGRATION_12_14,

                        // Цепочки 13 → 15
                        MIGRATION_13_15,

                        // 🆕 Цепочки с пропуском 15 (сразу в 16)
                        MIGRATION_2_16,
                        MIGRATION_3_16,
                        MIGRATION_4_16,
                        MIGRATION_5_16,
                        MIGRATION_6_16,
                        MIGRATION_7_16,
                        MIGRATION_8_16,
                        MIGRATION_9_16,
                        MIGRATION_10_16,
                        MIGRATION_11_16,
                        MIGRATION_12_16,
                        MIGRATION_13_16,
                        MIGRATION_14_16,

                        // 🆕 v16 → v17: системные «Покупки» и «Список покупок»
                        MIGRATION_16_17,

                        // 🆕 Цепочки с пропуском 16 (сразу в 17)
                        MIGRATION_2_17,
                        MIGRATION_3_17,
                        MIGRATION_4_17,
                        MIGRATION_5_17,
                        MIGRATION_6_17,
                        MIGRATION_7_17,
                        MIGRATION_8_17,
                        MIGRATION_9_17,
                        MIGRATION_10_17,
                        MIGRATION_11_17,
                        MIGRATION_12_17,
                        MIGRATION_13_17,
                        MIGRATION_14_17,
                        MIGRATION_15_17,

                        // 🆕 v17 → v18: DROP TABLE sync_queue
                        MIGRATION_17_18_DIRECT,

                        // 🆕 Цепочки с пропуском 17 (сразу в 18)
                        MIGRATION_2_18,
                        MIGRATION_3_18,
                        MIGRATION_4_18,
                        MIGRATION_5_18,
                        MIGRATION_6_18,
                        MIGRATION_7_18,
                        MIGRATION_8_18,
                        MIGRATION_9_18,
                        MIGRATION_10_18,
                        MIGRATION_11_18,
                        MIGRATION_12_18,
                        MIGRATION_13_18,
                        MIGRATION_14_18,
                        MIGRATION_15_18,
                        MIGRATION_16_18,

                        // 🆕 v18 → v19: tombstones (№10)
                        MIGRATION_18_19_DIRECT,

                        // 🆕 Цепочки с пропуском 18 (сразу в 19)
                        MIGRATION_17_19,
                        MIGRATION_2_19,
                        MIGRATION_3_19,
                        MIGRATION_4_19,
                        MIGRATION_5_19,
                        MIGRATION_6_19,
                        MIGRATION_7_19,
                        MIGRATION_8_19,
                        MIGRATION_9_19,
                        MIGRATION_10_19,
                        MIGRATION_11_19,
                        MIGRATION_12_19,
                        MIGRATION_13_19,
                        MIGRATION_14_19,
                        MIGRATION_15_19,
                        MIGRATION_16_19
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun resetInstance() {
            INSTANCE = null
        }
    }
}
