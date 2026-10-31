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

        lateinit var mainViewModel: MainViewModel
            private set

        val applicationScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // 🆕 v20.5.3: Coil для Яндекс.Диска
        private const val YANDEX_DISK_HOST = "downloader.disk.yandex.ru"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val TAG_COIL = "CoilNetwork"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Logger.init(this)
        applySavedTheme(this)

        mainViewModel = MainViewModel(this)
        Logger.log("BaseApplication", "Global MainViewModel initialized")

        ensureReminderChannel(this)

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
        // 🆕 v20.5.3: сетевой клиент Coil с принудительной подменой UA для хоста Яндекс.Диска.
        // 🆕 v20.5.7: добавлена диагностика ответа (код, Content-Type, длина),
        //             и Accept сужен до image/*.
        // 🆕 v20.5.8: добавлен Referer и Sec-Fetch-* для downloader.disk.yandex.ru,
        //             чтобы обойти 403 при загрузке preview-ссылок.
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val isYandex = request.url.host == YANDEX_DISK_HOST

                val newRequest = if (isYandex) {
                    request.newBuilder()
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .header("Accept", "image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5")
                        .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                        // 🆕 v20.5.8: имитируем браузерный контекст.
                        // Яндекс.Диск отдаёт 403 на preview-ссылки без Referer/Origin.
                        .header("Referer", "https://disk.yandex.ru/")
                        .header("Origin", "https://disk.yandex.ru")
                        .header("Sec-Fetch-Dest", "image")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Site", "cross-site")
                        .build()
                } else {
                    request
                }

                val response = chain.proceed(newRequest)

                if (isYandex) {
                    val code = response.code
                    val contentType = response.header("Content-Type") ?: "null"
                    val length = response.header("Content-Length") ?: "null"
                    Logger.log(
                        TAG_COIL,
                        "Coil network: code=$code, Content-Type=$contentType, " +
                            "Content-Length=$length, url=${request.url}"
                    )
                }

                response
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
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
