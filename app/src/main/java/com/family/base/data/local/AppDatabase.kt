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
    version = 12,  // ← увеличили с 11 до 12 (historyMigratedV12 в settings)
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

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 4 НА 5 (ПОДТИПЫ)
        // ============================================================
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN itemSubtype TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 5 НА 6 (originalId — частичное списание)
        // ============================================================
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 6 НА 7 (lastRevisionDate — ревизия)
        // ============================================================
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 7 НА 8 (parentItemId — ВЛОЖЕННЫЕ ПРЕДМЕТЫ)
        // ============================================================
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 8 НА 9 (parentItemId в sync_queue)
        // ============================================================
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 9 НА 10 (purchaseDate — ДАТА ПОКУПКИ)
        // ============================================================
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 10 НА 11 (itemName в history)
        // ============================================================
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 11 НА 12 (historyMigratedV12 в settings)
        // ============================================================
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 2 НА 4 (ЕСЛИ ПРОПУСТИЛИ 3)
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

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 2 НА 5 (ЕСЛИ ПРОПУСТИЛИ 3 И 4)
        // ============================================================
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

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 4 НА 6 (ЕСЛИ ПРОПУСТИЛИ 5)
        // ============================================================
        private val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN itemSubtype TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 5 НА 7 (ЕСЛИ ПРОПУСТИЛИ 6)
        // ============================================================
        private val MIGRATION_5_7 = object : Migration(5, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN originalId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 6 НА 8 (ЕСЛИ ПРОПУСТИЛИ 7)
        // ============================================================
        private val MIGRATION_6_8 = object : Migration(6, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lastRevisionDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 7 НА 9 (ЕСЛИ ПРОПУСТИЛИ 8)
        // ============================================================
        private val MIGRATION_7_9 = object : Migration(7, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentItemId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN parentItemId TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 9 НА 11 (ЕСЛИ ПРОПУСТИЛИ 10)
        // ============================================================
        private val MIGRATION_9_11 = object : Migration(9, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 9 НА 12 (ЕСЛИ ПРОПУСТИЛИ 10 И 11)
        // ============================================================
        private val MIGRATION_9_12 = object : Migration(9, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN purchaseDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
            }
        }

        // ============================================================
        // МИГРАЦИЯ С ВЕРСИИ 10 НА 12 (ЕСЛИ ПРОПУСТИЛИ 11)
        // ============================================================
        private val MIGRATION_10_12 = object : Migration(10, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN itemName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE settings ADD COLUMN historyMigratedV12 INTEGER NOT NULL DEFAULT 0")
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
                        MIGRATION_2_4,
                        MIGRATION_2_5,
                        MIGRATION_4_6,
                        MIGRATION_5_7,
                        MIGRATION_6_8,
                        MIGRATION_7_9,
                        MIGRATION_9_11,
                        MIGRATION_9_12,
                        MIGRATION_10_12
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
