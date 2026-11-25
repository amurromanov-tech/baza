package com.family.base

object Config {
    // Client ID Яндекс OAuth (замените на реальный, полученный при регистрации приложения)
    const val CLIENT_ID = "eca647a834c3431f974bc6f650f64208"

    // Внутреннее имя корневой папки (не обязано совпадать с именем на Диске)
    const val SHARED_FOLDER_NAME = "BAZA"

    // URL-ы OAuth
    const val YANDEX_OAUTH_AUTHORIZE_URL = "https://oauth.yandex.ru/authorize"
    const val YANDEX_OAUTH_TOKEN_URL = "https://oauth.yandex.ru/token"
    const val REDIRECT_URI = "com.family.base://oauth2redirect"

    // Базовые URL API Яндекс.Диска
    const val YANDEX_DISK_API_BASE = "https://cloud-api.yandex.net/v1/"
    const val YANDEX_DISK_PUBLIC_API_BASE = "https://cloud-api.yandex.net/v1/disk/public/"

    // Ключ для хранения публичного ключа папки в защищённом хранилище
    const val PREF_PUBLIC_KEY = "public_key"

    const val AI_RECIPE_PROXY_URL = "https://baza-ai-proxy.amur-romanov.workers.dev"

    // ============================================================
    // 🆕 v23.3.19: ЛОКАЛЬНЫЙ WEBDAV СИНК (ПК-ОБЛАКО: ZeroTier + Rclone)
    // ============================================================
    /**
     * Флаг переключения синхронизации на локальный WebDAV-сервер (ПК).
     * true  — синк идёт на ПК (http://10.251.135.94:8080/)
     * false — синк идёт на Яндекс.Диск (безопасный откат при проблемах)
     *
     * ⚠️ ПЕРЕД ВКЛЮЧЕНИЕМ (true) убедитесь, что папка BAZA полностью скопирована
     * с Яндекс.Диска в E:\BAZA-Sync, либо сделан полный ZIP-бэкап с эталонного телефона!
     */
    const val USE_LOCAL_WEBDAV_SYNC = true

    /** URL локального WebDAV-сервера (Rclone на ПК через ZeroTier) */
    const val LOCAL_WEBDAV_URL = "http://10.251.135.94:8080/"

    /** Логин для Basic Auth локального WebDAV */
    const val LOCAL_WEBDAV_USER = "baza"

    /**
     * Пароль для Basic Auth локального WebDAV.
     * ⚠️ ДОЛЖЕН ТОЧНО СОВПАДАТЬ с --pass в start-baza-server.bat на ПК!
     * Если браузер по новому паролю пускает, а приложение даёт красный индикатор —
     * значит здесь зашит старый пароль. Замените значение на актуальное.
     */
    // TODO (техдолг): вынести в шифрованное хранилище (EncryptedSharedPreferences)
    const val LOCAL_WEBDAV_PASSWORD = "Aa123123!"

    // ============================================================
    // ОБНОВЛЕНИЯ ПРИЛОЖЕНИЯ
    // ============================================================
    /**
     * Ссылка на version.json в GitHub raw.
     * Формат: https://raw.githubusercontent.com/<USER>/<REPO>/<BRANCH>/version.json
     */
    const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/amurromanov-tech/baza/main/version.json"

    /**
     * Публичная ссылка на папку или файл APK на Яндекс.Диске.
     */
    const val APK_YANDEX_PUBLIC_URL =
        "https://disk.yandex.ru/d/jA1J1i8ZFvK8Sg"

    /**
     * Имя APK-файла в публичной папке Яндекс.Диска.
     * v14.1.2: перешли на release-подпись, файл называется app-release.apk
     */
    const val APK_FILE_NAME = "/app-release.apk"

    // ============================================================
    // ПОЛЬЗОВАТЕЛИ (users.json на Яндекс.Диске)
    // ============================================================
    /**
     * Путь к файлу с пользователями на Яндекс.Диске.
     * Формат JSON:
     * {
     *   "users": [
     *     { "name": "Алексей", "pinHash": "sha256..." },
     *     ...
     *   ]
     * }
     */
    const val USERS_JSON_FILE = "data/users.json"
}

// ============================================================
// ПОЛЬЗОВАТЕЛИ ПРИЛОЖЕНИЯ
// ============================================================
/**
Список пользователей приложения.
PIN здесь НЕ хранится — только имя и эмодзи.
PIN задаёт сам пользователь при первом входе,
хранится в виде SHA-256-хеша в users.json на Яндекс.Диске.
🆕 v17.3.1: гость удалён полностью.
🆕 v20.3.9: поле canDelete — право удалять предметы/папки/задачи.
true  → Алексей (полный доступ);
false → Рима, Дима (могут всё, кроме удаления).
*/
enum class AppUser(
    val displayName: String,
    val emoji: String,
    val canDelete: Boolean
) {
    ALEXEY("Алексей", "👨", canDelete = true),
    RIMA("Рима", "👩", canDelete = false),
    DIMA("Дима", "🧑", canDelete = false);

    companion object {
        /** Все именованные пользователи (которым можно задать PIN). */
        fun users(): List<AppUser> = listOf(ALEXEY, RIMA, DIMA)

        /** Найти пользователя по имени. */
        fun byName(name: String?): AppUser? {
            if (name == null) return null
            return values().firstOrNull { it.displayName == name }
        }

        /**
         * 🆕 v20.3.9: может ли пользователь с таким именем удалять?
         *  - null / неизвестное имя → false (безопасный дефолт).
         *  - Алексей → true.
         *  - Рима, Дима → false.
         */
        fun canDelete(name: String?): Boolean {
            return byName(name)?.canDelete ?: false
        }
    }
}
