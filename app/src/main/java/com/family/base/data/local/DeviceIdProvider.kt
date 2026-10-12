package com.family.base.data.local

import android.content.Context
import java.util.UUID

/**
 * Поставщик стабильного deviceId для этого устройства.
 *
 * Используется в SyncManager для формирования имён пакетов changelog:
 *   /BAZA/changelog/{deviceId}_{timestamp}.json
 *
 * deviceId генерируется один раз при первом обращении и хранится
 * в SharedPreferences. Не зависит от ANDROID_ID (не сбрасывается при wipe),
 * не зависит от установки приложения (переживает обновление).
 *
 * ⚠️ При полной переустановке (uninstall + install) SharedPreferences
 * стираются → deviceId будет новым. Это нормально: старые пакеты
 * в changelog просто останутся с прежним deviceId, а новые пойдут с новым.
 */
object DeviceIdProvider {

    private const val PREFS_NAME = "baza_device"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var cached: String? = null

    /**
     * Получить deviceId (генерирует при первом обращении).
     */
    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            cached = id
            return id
        }
    }

    /**
     * Сбросить кэш (для отладки/тестов).
     */
    fun reset() {
        cached = null
    }
}
