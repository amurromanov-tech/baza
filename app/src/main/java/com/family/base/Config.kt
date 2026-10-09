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
 * Список пользователей приложения.
 *
 * PIN здесь НЕ хранится — только имя и эмодзи.
 * PIN задаёт сам пользователь при первом входе,
 * хранится в виде SHA-256-хеша в users.json на Яндекс.Диске.
 */
enum class AppUser(
    val displayName: String,
    val emoji: String
) {
    ALEXEY("Алексей", "👨"),
    RIMA("Рима", "👩"),
    DIMA("Дима", "🧑"),
    GUEST("Гость", "👤");

    companion object {
        /** Пользователи, которым можно задать PIN (без гостя). */
        fun namedUsers(): List<AppUser> = listOf(ALEXEY, RIMA, DIMA)

        /** Найти пользователя по имени. */
        fun byName(name: String?): AppUser? {
            if (name == null) return null
            return values().firstOrNull { it.displayName == name }
        }

        /** Является ли имя «Гостем». */
        fun isGuest(name: String?): Boolean = name == GUEST.displayName
    }
}
