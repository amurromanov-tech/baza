package com.family.base.data.remote.model

import java.security.MessageDigest

/**
 * Один пользователь приложения в файле users.json на Яндекс.Диске.
 *
 * ВАЖНО: PIN НЕ хранится в открытом виде — только SHA-256-хеш.
 */
data class UserModel(
    val name: String,
    val pinHash: String
)

/**
 * Корневой объект файла users.json.
 *
 * Формат:
 * {
 *   "users": [
 *     { "name": "Алексей", "pinHash": "a665a459..." },
 *     ...
 *   ]
 * }
 */
data class UsersFile(
    val users: List<UserModel> = emptyList()
)

/**
 * Хелпер для хеширования PIN.
 * Используется и при сохранении, и при проверке.
 */
object PinHasher {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Проверка PIN: сравниваем хеши введённого PIN и сохранённого. */
    fun matches(pin: String, pinHash: String): Boolean =
        sha256(pin) == pinHash
}
