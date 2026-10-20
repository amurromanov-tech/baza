package com.family.base.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 🆕 v20.2.0: обёртка для голосового ввода (Подход A — системный RecognizerIntent).
 *
 * Что делает:
 *  - проверяет разрешение RECORD_AUDIO (запрашивает у Activity при необходимости);
 *  - запускает системный распознаватель речи через ActivityResultLauncher;
 *  - вставляет распознанный текст в указанное поле — В ПОЗИЦИЮ КУРСОРА,
 *    не заменяя существующий текст;
 *  - ловит ActivityNotFoundException (если на устройстве нет сервиса
 *    распознавания речи) и показывает Toast.
 *
 * Что НЕ делает:
 *  - не запрашивает разрешение сам (только через ActivityCompat.requestPermissions,
 *    результат приходит в Activity.onRequestPermissionsResult);
 *  - не хранит состояние — поле, в которое вставлять, Activity запоминает само.
 *
 * Как использовать (в Activity):
 *
 *   1. Объявить launcher:
 *      private val voiceLauncher = registerForActivityResult(
 *          ActivityResultContracts.StartActivityForResult()
 *      ) { result ->
 *          if (result.resultCode == Activity.RESULT_OK) {
 *              val text = VoiceInputHelper.extractRecognizedText(result.data)
 *              if (!text.isNullOrEmpty() && pendingVoiceField != null) {
 *                  VoiceInputHelper.insertRecognizedText(pendingVoiceField!!, text)
 *              }
 *              pendingVoiceField = null
 *          }
 *      }
 *
 *   2. Объявить поле:
 *      private var pendingVoiceField: EditText? = null
 *
 *   3. По клику на иконку микрофона:
 *      pendingVoiceField = editText
 *      VoiceInputHelper.startVoiceInput(this, voiceLauncher, "Говорите…")
 *
 *   4. В onRequestPermissionsResult:
 *      if (requestCode == VoiceInputHelper.REQUEST_CODE_MIC) {
 *          if (granted) {
 *              pendingVoiceField?.let {
 *                  VoiceInputHelper.startVoiceInput(this, voiceLauncher, "Говорите…")
 *              }
 *          } else {
 *              Toast...
 *          }
 *      }
 */
object VoiceInputHelper {

    /**
     * Request code для runtime-разрешения RECORD_AUDIO.
     * Не пересекается с CAMERA_PERMISSION_REQUEST = 100 в AddItemActivity.
     */
    const val REQUEST_CODE_MIC = 101

    private const val LANGUAGE = "ru-RU"

    /**
     * Проверяет, выдано ли разрешение RECORD_AUDIO.
     */
    fun hasPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Запрашивает разрешение RECORD_AUDIO.
     * Результат придёт в Activity.onRequestPermissionsResult с кодом REQUEST_CODE_MIC.
     */
    fun requestPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE_MIC
        )
    }

    /**
     * Запускает системный распознаватель речи.
     *
     * Если разрешение RECORD_AUDIO не выдано — сначала запрашивает его,
     * а распознавание НЕ запускает (Activity должна вызвать startVoiceInput
     * повторно после onRequestPermissionsResult с grant).
     *
     * Если сервиса распознавания нет — ловит ActivityNotFoundException
     * и показывает Toast.
     *
     * @param activity  Activity, из которой запускаем (нужен для requestPermission и Toast).
     * @param launcher  launcher, зарегистрированный в onCreate этой Activity.
     * @param prompt    подсказка в диалоге распознавания (например, «Говорите…»).
     */
    fun startVoiceInput(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>,
        prompt: String = "Говорите…"
    ) {
        if (!hasPermission(activity)) {
            requestPermission(activity)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Logger.log(
                "VoiceInputHelper",
                "ActivityNotFoundException: no speech recognition service on this device"
            )
            Toast.makeText(
                activity,
                "Голосовой ввод недоступен на этом устройстве",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Logger.log("VoiceInputHelper", "startVoiceInput error: ${e.message}", e)
            Toast.makeText(
                activity,
                "Не удалось запустить голосовой ввод: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Извлекает распознанный текст из результата Activity.
     * Берёт первый (самый вероятный) вариант.
     *
     * @return текст или null, если результата нет.
     */
    fun extractRecognizedText(data: Intent?): String? {
        if (data == null) return null
        val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        return results?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Вставляет текст в поле — В ПОЗИЦИЮ КУРСОРА, не заменяя существующий.
     *
     * Если поле пустое — просто ставит текст.
     * Если курсор не установлен (null) — вставляет в конец.
     *
     * @param field  поле, куда вставлять.
     * @param text   распознанный текст.
     */
    fun insertRecognizedText(field: EditText, text: String) {
        val current = field.text?.toString() ?: ""
        val selStart = field.selectionStart.coerceAtLeast(0)
        val selEnd = field.selectionEnd.coerceAtLeast(0)
        val minSel = minOf(selStart, selEnd)
        val maxSel = maxOf(selStart, selEnd)

        val newText = buildString {
            append(current.substring(0, minSel))
            append(text)
            append(current.substring(maxSel))
        }

        field.setText(newText)

        // Курсор — после вставленного фрагмента
        val newCursor = minSel + text.length
        field.setSelection(newCursor.coerceIn(0, newText.length))

        Logger.log(
            "VoiceInputHelper",
            "insertRecognizedText: inserted ${text.length} chars at $minSel (locale=${Locale.getDefault()})"
        )
    }
}
