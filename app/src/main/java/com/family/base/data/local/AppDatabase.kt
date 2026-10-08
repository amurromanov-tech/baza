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
        SyncInfoEntity::class
    ],
    version = 13,
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
        // 🆕 v12 → v13: пересоздаём settings с правильной схемой
        // ============================================================
        /**
         * Проблема: миграция 11→12 добавила historyMigratedV12 через
         * ALTER TABLE с DEFAULT 0. Room видит defaultValue='0' вместо
         * 'undefined' и падает при валидации.
         *
         * Решение: пересоздаём таблицу settings с идентичной схемой
         * (все 6 полей, все NOT NULL, без defaults у historyMigratedV12).
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Создаём новую таблицу с правильной схемой
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

                // 2. Копируем данные (все колонки в новом порядке)
                //    Осторожно: старые значения могут быть с любыми дефолтами.
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

                // 3. Удаляем старую
                db.execSQL("DROP TABLE settings")

                // 4. Переименовываем
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")
            }
        }

        // Цепочки "если пропустили"
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

        // Цепочки "с пропуском 12"
        private val MIGRATION_9_13 = object : Migration(9, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
                // settings_new — не нужна, при новой установке settings создаётся сразу
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "baza.db"
                )
                    .addMigrations(
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
                        MIGRATION_11_13
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
