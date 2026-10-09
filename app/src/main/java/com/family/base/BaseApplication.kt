package com.family.base

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.family.base.ui.viewmodel.MainViewModel
import com.family.base.util.Logger
import com.family.base.util.TaskReminderScheduler
import com.family.base.util.TaskReminderWorker

class BaseApplication : Application(), ImageLoaderFactory {

    companion object {
        private var instance: BaseApplication? = null

        // ============================================================
        // ГЛОБАЛЬНЫЙ VIEWMODEL — живёт всё время жизни приложения
        // ============================================================
        lateinit var mainViewModel: MainViewModel
            private set

        // ============================================================
        // НАСТРОЙКИ ТЕМЫ (SharedPreferences)
        // ============================================================
        const val PREFS_NAME = "baza_settings"
        const val KEY_THEME_MODE = "theme_mode"

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        fun getAppContext(): Context {
            return instance?.applicationContext ?: throw IllegalStateException("Application not initialized")
        }

        fun getSavedThemeMode(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        }

        fun saveThemeMode(context: Context, mode: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        }

        fun toNightMode(mode: String): Int {
            return when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        }

        fun applySavedTheme(context: Context) {
            val mode = getSavedThemeMode(context)
            val nightMode = toNightMode(mode)
            AppCompatDelegate.setDefaultNightMode(nightMode)
            Logger.log("BaseApplication", "Applied theme: $mode (nightMode=$nightMode)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Logger.init(this)

        // Применяем сохранённую тему до создания UI
        applySavedTheme(this)

        // Создаём глобальный MainViewModel — живёт всё время жизни приложения
        mainViewModel = MainViewModel(this)
        Logger.log("BaseApplication", "Global MainViewModel initialized")

        // 🆕 v14.1.0: канал уведомлений для напоминаний (безопасно, без WorkManager)
        ensureReminderChannel(this)

        // 🆕 v14.1.0: восстановление напоминания при холодном старте
        // В try/catch — если WorkManager даст сбой, приложение продолжит работу
        try {
            if (com.family.base.util.TaskReminderPreferences.isEnabled(this)) {
                TaskReminderScheduler.reschedule(this)
                Logger.log("BaseApplication", "Task reminders rescheduled on cold start")
            }
        } catch (e: Exception) {
            Logger.log("BaseApplication", "Task reminders schedule error: ${e.message}")
        }
    }

    /**
     * Создать канал уведомлений для напоминаний (Android 8+).
     * Безопасно вызывать многократно — повторное создание игнорируется системой.
     */
    private fun ensureReminderChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(TaskReminderWorker.CHANNEL_ID) != null) return

            val channel = NotificationChannel(
                TaskReminderWorker.CHANNEL_ID,
                TaskReminderWorker.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ежедневные напоминания о домашних делах"
            }
            manager.createNotificationChannel(channel)
            Logger.log("BaseApplication", "Reminder channel created")
        } catch (e: Exception) {
            Logger.log("BaseApplication", "ensureReminderChannel error: ${e.message}")
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100 * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
