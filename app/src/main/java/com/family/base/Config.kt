package com.family.base

object Config {
    // Client ID Яндекс OAuth (замените на реальный, полученный при регистрации приложения)
    const val CLIENT_ID = "eca647a834c3431f974bc6f650f64208"  // ← ЗАМЕНИТЕ ЭТУ СТРОКУ

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
}
