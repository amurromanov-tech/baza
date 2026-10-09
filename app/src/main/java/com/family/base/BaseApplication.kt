package com.family.base

import android.app.Application
import android.content.Context
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

       // 🆕 v14.1: канал уведомлений для напоминаний о задачах
// try {
//     createTaskReminderChannel()
// } catch (e: Exception) {
//     Logger.log("BaseApplication", "Failed to create notification channel: ${e.message}")
// }

// 🆕 v14.1: планировщик напоминаний
// try {
//     TaskReminderScheduler.schedule(this)
//     Logger.log("BaseApplication", "TaskReminderScheduler scheduled")
// } catch (e: Exception) {
//     Logger.log("BaseApplication", "Failed to schedule task reminders: ${e.message}")
// }
    }

    /**
     * 🆕 v14.1: создаём канал уведомлений для напоминаний о задачах.
     * Логика вынесена в TaskReminderWorker.createChannelIfNeeded(),
     * но вызвать её здесь полезно, чтобы канал был создан при первом
     * старте приложения (а не при первом срабатывании воркера).
     */
    private fun createTaskReminderChannel() {
        // Канал создаётся лениво в воркере. Здесь просто логируем,
        // что приложение готово к напоминаниям.
        Logger.log("BaseApplication", "Task reminder channel ready (id=${TaskReminderWorker.CHANNEL_ID})")
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
