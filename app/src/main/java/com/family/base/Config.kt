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
// ЛОКАЛЬНЫЙ WEBDAV СИНК (ПК-ОБЛАКО: ZeroTier + Rclone)
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

/** Пароль для Basic Auth локального WebDAV */
// TODO (техдолг): вынести в шифрованное хранилище (EncryptedSharedPreferences)
const val LOCAL_WEBDAV_PASSWORD = "MySuperSecretPassword2026"

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
