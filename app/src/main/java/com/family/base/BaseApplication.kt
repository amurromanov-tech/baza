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
import com.family.base.util.TaskReminderPreferences
import com.family.base.util.TaskReminderReceiver
import com.family.base.util.TaskReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BaseApplication : Application(), ImageLoaderFactory {

    companion object {
        private var instance: BaseApplication? = null

        // ============================================================
        // ГЛОБАЛЬНЫЙ VIEWMODEL — живёт всё время жизни приложения
        // ============================================================
        lateinit var mainViewModel: MainViewModel
            private set

        // ============================================================
        // ГЛОБАЛЬНЫЙ СКОУП ДЛЯ ФОНОВЫХ ЗАДАЧ
        // ============================================================
        val applicationScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // ============================================================
        // 🆕 v20.4.10: User-Agent для Coil при загрузке с Яндекс.Диска
        // ============================================================
        // Яндекс.Диск блокирует запросы к /preview/... с не-браузерным
        // User-Agent (OkHttp по умолчанию шлёт "okhttp/4.11.0" → 403).
        // Браузер шлёт браузерный UA → 200. Coil использует свой
        // OkHttpClient, поэтому подменяем UA ТОЛЬКО для хоста
        // downloader.disk.yandex.ru — остальные запросы не трогаем.
        private const val YANDEX_DISK_HOST = "downloader.disk.yandex.ru"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Logger.init(this)

        applySavedTheme(this)

        mainViewModel = MainViewModel(this)
        Logger.log("BaseApplication", "Global MainViewModel initialized")

        // Канал уведомлений для напоминаний
        ensureReminderChannel(this)

        // 🆕 v20.1.0 (Вариант A): глобальное время суток убрано.
        // Здесь по-прежнему только isEnabled — читаем флаг «напоминания включены».
        // Планировщик сам решит: per-task remindAt / dueDate-15мин / умный пинг 08:00.
        try {
            if (TaskReminderPreferences.isEnabled(this)) {
                applicationScope.launch {
                    try {
                        TaskReminderScheduler.rescheduleAllSuspend(this@BaseApplication)
                        Logger.log("BaseApplication", "Task reminders rescheduled on cold start (IO)")
                    } catch (e: Exception) {
                        Logger.log("BaseApplication", "Task reminders schedule error (IO): ${e.message}")
                    }
                }
            } else {
                Logger.log("BaseApplication", "Task reminders disabled — skip cold-start reschedule")
            }
        } catch (e: Exception) {
            Logger.log("BaseApplication", "Task reminders launch error: ${e.message}")
        }
    }

    private fun ensureReminderChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(TaskReminderReceiver.CHANNEL_ID) != null) return

            val channel = NotificationChannel(
                TaskReminderReceiver.CHANNEL_ID,
                TaskReminderReceiver.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Напоминания о домашних делах"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
            Logger.log("BaseApplication", "Reminder channel created")
        } catch (e: Exception) {
            Logger.log("BaseApplication", "ensureReminderChannel error: ${e.message}")
        }
    }

    override fun newImageLoader(): ImageLoader {
        // 🆕 v20.4.10: OkHttpClient для Coil с подменой User-Agent
        // для хоста downloader.disk.yandex.ru (иначе 403 на preview).
        val coilOkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val newRequest = if (host == YANDEX_DISK_HOST) {
                    request.newBuilder()
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .header("Accept", "image/*,*/*;q=0.8")
                        .build()
                } else {
                    request
                }
                chain.proceed(newRequest)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(coilOkHttpClient)
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
