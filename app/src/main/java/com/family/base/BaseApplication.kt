package com.family.base

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.family.base.util.Logger

class BaseApplication : Application(), ImageLoaderFactory {

    companion object {
        private var instance: BaseApplication? = null

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

        /**
         * Читает сохранённый режим темы из SharedPreferences.
         * Возвращает "light", "dark" или "system" (по умолчанию — "system").
         */
        fun getSavedThemeMode(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        }

        /**
         * Сохраняет режим темы в SharedPreferences.
         */
        fun saveThemeMode(context: Context, mode: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        }

        /**
         * Преобразует строку режима в константу AppCompatDelegate.
         */
        fun toNightMode(mode: String): Int {
            return when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        }

        /**
         * Применяет сохранённую тему. Вызывается из onCreate() и из AppSettingsActivity.
         */
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

        // Инициализируем Logger
        Logger.init(this)

        // ВАЖНО: применяем сохранённую тему до создания UI
        applySavedTheme(this)
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
                    .maxSizeBytes(100 * 1024 * 1024) // 100 MB
                    .build()
            }
            .build()
    }
}
