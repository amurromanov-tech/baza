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
        SyncQueueEntity::class,
        SyncInfoEntity::class,
        TaskEntity::class,
        OutboxEntity::class
    ],
    version = 15,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun itemDao(): ItemDao
    abstract fun historyDao(): HistoryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun lockDao(): LockDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncInfoDao(): SyncInfoDao
    abstract fun taskDao(): TaskDao
    abstract fun outboxDao(): OutboxDao

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
        // 🆕 v14 → v15: syncStatus + outbox (changelog)
        // ============================================================
        /**
         * Добавляем:
         *  1. items.syncStatus   TEXT NOT NULL DEFAULT 'synced'
         *  2. folders.syncStatus TEXT NOT NULL DEFAULT 'synced'
         *  3. tasks.syncStatus   TEXT NOT NULL DEFAULT 'synced'
         *  4. таблицу outbox (см. OutboxEntity)
         *
         * ⚠️ Схема outbox должна ТОЧНО совпадать с Room-генерацией:
         *    Boolean  → INTEGER NOT NULL
         *    Long     → INTEGER NOT NULL
         *    Long?    → INTEGER
         *    String   → TEXT NOT NULL
         *    String?  → TEXT
         *
         * ⚠️ Индексы должны совпадать с @Entity(indices = [...]).
         *
         * Дефолт 'synced' для существующих записей: после апгрейда
         * v14 → v15 первый синк НЕ будет заливать всю базу,
         * только реальные новые изменения.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. syncStatus в items
                db.execSQL("ALTER TABLE items ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")

                // 2. syncStatus в folders
                db.execSQL("ALTER TABLE folders ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")

                // 3. syncStatus в tasks
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'synced'")

                // 4. Таблица outbox
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

                // 5. Индексы outbox (должны совпадать с @Entity(indices))
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_isSynced ON outbox(isSynced)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_entityType_entityId ON outbox(entityType, entityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_createdAt ON outbox(createdAt)")
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

        // ============================================================
        // 🆕 ЦЕПОЧКИ С ПРОПУСКОМ 14 (сразу в 15)
        // ============================================================
        /**
         * Для каждой цепочки X → 14 добавляем шаг 14 → 15,
         * чтобы старые БД (v2..v13) корректно доехали до v15.
         *
         * Логика: сначала выполняем ту же миграцию X → 14,
         * потом добавляем поля/таблицу v15.
         */
        private fun migrateLegacyTo15(db: SupportSQLiteDatabase, legacyMigration: Migration) {
            // Прогоняем старую цепочку до v14
            legacyMigration.migrate(db)
            // Затем — дельта v15
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

        private val MIGRATION_2_15 = object : Migration(2, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_2_14)
            }
        }

        private val MIGRATION_3_15 = object : Migration(3, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_3_14)
            }
        }

        private val MIGRATION_4_15 = object : Migration(4, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_4_14)
            }
        }

        private val MIGRATION_5_15 = object : Migration(5, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_5_14)
            }
        }

        private val MIGRATION_6_15 = object : Migration(6, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_6_14)
            }
        }

        private val MIGRATION_7_15 = object : Migration(7, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_7_14)
            }
        }

        private val MIGRATION_8_15 = object : Migration(8, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_8_14)
            }
        }

        private val MIGRATION_9_15 = object : Migration(9, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_9_14)
            }
        }

        private val MIGRATION_10_15 = object : Migration(10, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_10_14)
            }
        }

        private val MIGRATION_11_15 = object : Migration(11, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_11_14)
            }
        }

        private val MIGRATION_12_15 = object : Migration(12, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacyTo15(db, MIGRATION_12_14)
            }
        }

        private val MIGRATION_13_15 = object : Migration(13, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 13 → 14
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
                // 14 → 15
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

                        // 🆕 Цепочки с пропуском 14 (сразу в 15)
                        MIGRATION_2_15,
                        MIGRATION_3_15,
                        MIGRATION_4_15,
                        MIGRATION_5_15,
                        MIGRATION_6_15,
                        MIGRATION_7_15,
                        MIGRATION_8_15,
                        MIGRATION_9_15,
                        MIGRATION_10_15,
                        MIGRATION_11_15,
                        MIGRATION_12_15,
                        MIGRATION_13_15
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
