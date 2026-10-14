package com.family.base.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Единая обёртка над сетевыми вызовами к Яндекс.Диску.
 *
 * Задачи:
 *  - retry с backoff для временных ошибок (DNS, timeout, 5xx, IOException);
 *  - НЕ ретраить осмысленные ответы сервера (4xx, кроме 429);
 *  - различать «файла нет (404)» и «сеть упала» — этого не умел старый код;
 *  - логировать TIMING, чтобы видеть, где синк тормозит;
 *  - не глотать CancellationException (иначе корутины нельзя отменить).
 *
 * Возвращает Result<T>. Внутри failure — NetworkException с полем kind.
 *
 * ⚠️ ВАЖНО:
 *  - Блок не должен сам ловить сетевые исключения — пусть они летят наружу,
 *    исполнитель сам решит, ретраить или нет.
 *  - Блок НЕ должен содержать побочных эффектов, которые нельзя повторять
 *    (например, запись в БД). Ретрай может выполнить блок 2-3 раза.
 *    Если побочка неизбежна — оборачивайте в executor только сам сетевой вызов.
 */
object NetworkCallExecutor {

    private const val TAG = "NetworkCallExecutor"

    /** Общее число попыток (1 основная + 2 ретрая). */
    private const val DEFAULT_MAX_ATTEMPTS = 3

    /** Паузы между попытками, мс. Индекс = номер попытки после первой. */
    private val DEFAULT_BACKOFF_MS = longArrayOf(500L, 1500L, 3000L)

    /**
     * Категория сетевой ошибки. Используется в логах и в UI,
     * чтобы отличать «нет интернета» от «Яндекс лёг».
     */
    enum class Kind {
        /** DNS не резолвится / нет сети */
        DNS,
        /** Таймаут соединения, чтения или общий */
        TIMEOUT,
        /** 5xx от сервера */
        SERVER_5XX,
        /** 4xx от сервера (кроме 429) — как правило, осмысленный ответ */
        HTTP_4XX,
        /** 429 Too Many Requests */
        RATE_LIMITED,
        /** Прочие IOException */
        IO,
        /** Всё остальное */
        UNKNOWN
    }

    /**
     * Ошибка сетевого вызова. Хранит kind для дальнейшей обработки.
     */
    class NetworkException(
        val kind: Kind,
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause) {

        /** Стоит ли повторять вызов. */
        val isRetryable: Boolean
            get() = when (kind) {
                Kind.DNS, Kind.TIMEOUT, Kind.SERVER_5XX, Kind.RATE_LIMITED, Kind.IO -> true
                Kind.HTTP_4XX, Kind.UNKNOWN -> false
            }

        override fun toString(): String = "NetworkException($kind): $message"
    }

    /**
     * Результат HTTP-ответа внутри блока.
     * Блок возвращает его, чтобы исполнитель понял: 200 — успех,
     * 404 — валидный ответ, 5xx — retry.
     *
     * Для не-HTTP блоков (например, «просто suspend-функция, которая уже
     * кидает исключения») используйте runSafely с обычным T.
     */
    data class HttpOutcome<T>(
        val value: T,
        val httpCode: Int
    )

    /**
     * Обёртка для блоков, которые возвращают «сырое» значение T.
     * Ретраит по исключениям, логирует TIMING, но НЕ знает про HTTP-коды.
     *
     * Если внутри блока нужно различать 404 и сетевую ошибку — используйте
     * runHttpSafely.
     */
    suspend fun <T> runSafely(
        label: String,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        block: suspend () -> T
    ): Result<T> = execute(label, maxAttempts) { block() }

    /**
     * Обёртка для HTTP-вызовов. Блок возвращает HttpOutcome<T>,
     * где httpCode = код ответа. Executor сам решает:
     *  - 2xx → success;
     *  - 404 → success (value = null по договорённости вызывающего);
     *  - 4xx (кроме 404, 429) → failure без retry;
     *  - 429 → retry;
     *  - 5xx → retry.
     *
     * @param treat404AsSuccess если true — 404 вернётся как success(null).
     *        Если false — 404 вернётся как failure(HTTP_4XX).
     */
    suspend fun <T> runHttpSafely(
        label: String,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        treat404AsSuccess: Boolean = true,
        block: suspend () -> HttpOutcome<T?>
    ): Result<T?> = execute(label, maxAttempts) {
        val outcome = block()
        val code = outcome.httpCode
        when {
            code in 200..299 -> outcome.value
            code == 404 && treat404AsSuccess -> null
            code == 429 -> throw NetworkException(
                Kind.RATE_LIMITED,
                "HTTP 429 on $label",
                null
            )
            code in 400..499 -> throw NetworkException(
                Kind.HTTP_4XX,
                "HTTP $code on $label",
                null
            )
            code in 500..599 -> throw NetworkException(
                Kind.SERVER_5XX,
                "HTTP $code on $label",
                null
            )
            else -> throw NetworkException(
                Kind.UNKNOWN,
                "HTTP $code on $label",
                null
            )
        }
    }

    // ============================================================
    // ВНУТРЕННЯЯ КУХНЯ
    // ============================================================

    private suspend fun <T> execute(
        label: String,
        maxAttempts: Int,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        val attempts = maxAttempts.coerceAtLeast(1)
        var lastError: NetworkException? = null

        for (attempt in 1..attempts) {
            val start = System.currentTimeMillis()
            try {
                val value = block()
                val elapsed = System.currentTimeMillis() - start
                Logger.log(TAG, "TIMING $label attempt=$attempt OK ${elapsed}ms")
                return@withContext Result.success(value)
            } catch (ce: CancellationException) {
                // Отмену корутины пробрасываем как есть — иначе
                // applicationScope не сможет остановить работу.
                throw ce
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                val netErr = classify(e, label)
                lastError = netErr

                Logger.log(
                    TAG,
                    "TIMING $label attempt=$attempt FAIL ${elapsed}ms kind=${netErr.kind} msg=${netErr.message}"
                )

                val canRetry = netErr.isRetryable && attempt < attempts
                if (!canRetry) {
                    Logger.log(TAG, "$label giving up after attempt=$attempt kind=${netErr.kind}")
                    return@withContext Result.failure(netErr)
                }

                val backoff = DEFAULT_BACKOFF_MS.getOrElse(attempt - 1) { 3000L }
                Logger.log(TAG, "$label retry in ${backoff}ms (attempt ${attempt + 1}/$attempts)")
                delay(backoff)
            }
        }

        // Сюда попадаем только если цикл завершился без return — на всякий случай.
        val err = lastError ?: NetworkException(Kind.UNKNOWN, "Unknown failure on $label")
        Result.failure(err)
    }

    /**
     * Классифицирует исключение в NetworkException.
     * Если исключение уже NetworkException — возвращает как есть.
     */
    private fun classify(e: Exception, label: String): NetworkException {
        if (e is NetworkException) return e

        // DNS
        if (isDnsError(e)) {
            return NetworkException(Kind.DNS, "$label: DNS failure: ${e.message}", e)
        }

        // Таймауты
        if (e is SocketTimeoutException || e is TimeoutException) {
            return NetworkException(Kind.TIMEOUT, "$label: timeout: ${e.message}", e)
        }
        // OkHttp иногда оборачивает таймаут в IOException("timeout")
        if (e is IOException && e.message?.contains("timeout", ignoreCase = true) == true) {
            return NetworkException(Kind.TIMEOUT, "$label: timeout: ${e.message}", e)
        }

        // Прочие IOException
        if (e is IOException) {
            return NetworkException(Kind.IO, "$label: IO error: ${e.message}", e)
        }

        return NetworkException(Kind.UNKNOWN, "$label: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    private fun isDnsError(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is UnknownHostException) return true
            val msg = cause.message
            if (msg != null && (
                    msg.contains("No address associated with hostname") ||
                        msg.contains("Unable to resolve host", ignoreCase = true) ||
                        msg.contains("nodename nor servname", ignoreCase = true)
                    )
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}
