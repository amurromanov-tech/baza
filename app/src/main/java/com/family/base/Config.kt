package com.family.base

object Config {
    // Client ID Яндекс OAuth (замените на реальный, полученный при регистрации приложения)
    const val CLIENT_ID = "https://raw.githubusercontent.com/amurromanov-tech/baza/main/version.json"  // ← ЗАМЕНИТЕ ЭТУ СТРОКУ

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
     *
     * ⚠️ Замени <USER>, <REPO>, <BRANCH> на свои значения.
     * Например: https://raw.githubusercontent.com/ivanov/baza/main/version.json
     */
    const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/amurromanov-tech/baza/main/version.json"

    /**
     * Публичная ссылка на папку или файл APK на Яндекс.Диске.
     * Пример: https://disk.yandex.ru/d/XXXXXXXXXXXX
     *
     * ⚠️ UpdateManager сам получит прямую ссылку через API Яндекс.Диска.
     * ⚠️ При создании публичной ссылки НЕ включай опцию «Запретить скачивание».
     */
    const val APK_YANDEX_PUBLIC_URL =
        "https://disk.yandex.ru/d/jA1J1i8ZFvK8Sg"

    /**
     * Имя APK-файла в публичной папке Яндекс.Диска.
     * Используется в UpdateManager при запросе прямой ссылки через API.
     *
     * ⚠️ Должно ТОЧНО совпадать с именем файла на Диске (с расширением .apk).
     */
    const val APK_FILE_NAME = "app-debug.apk"
}
