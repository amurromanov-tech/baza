package com.family.base.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.family.base.BaseApplication
import com.family.base.databinding.ActivityAppSettingsBinding
import com.family.base.util.Logger
import com.family.base.util.TaskReminderPreferences

class AppSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSettingsBinding
    private val TAG = "AppSettingsActivity"

    /**
     * 🆕 v20.4.2: защита от рекурсии при программной установке isChecked.
     */
    private var suppressNotificationsListener = false

    private val languages = arrayOf("Русский", "English")
    private val sortOptions = arrayOf(
        "По имени",
        "По сроку годности",
        "По цене",
        "По дате добавления"
    )

    // ============================================================
    // 🆕 v20.4.2: ЛАУНЧЕР ЗАПРОСА POST_NOTIFICATIONS
    // ============================================================
    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.log(TAG, "POST_NOTIFICATIONS result: granted=$granted")
        updateNotificationsSwitch()
        if (granted) {
            Toast.makeText(this, "Уведомления включены", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Разрешение не выдано. Напоминания не будут приходить.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== AppSettingsActivity onCreate START ===")

        try {
            binding = ActivityAppSettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        setupLanguageSpinner()
        setupSortSpinner()
        setupThemeSelector()
        setupNotificationsSwitch()
        setupTodayDialogSwitch()
        loadSettings()

        Logger.log(TAG, "=== AppSettingsActivity onCreate FINISHED ===")
    }

    override fun onResume() {
        super.onResume()
        // 🆕 v20.4.2: если пользователь вернулся из системных настроек —
        // обновляем состояние переключателя.
        updateNotificationsSwitch()
    }

    // ============================================================
    // СПИННЕР ЯЗЫКА
    // ============================================================
    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter

        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Logger.log(TAG, "Language selected: ${languages[position]}")
                saveSetting("language", languages[position])
                Toast.makeText(
                    this@AppSettingsActivity,
                    "Смена языка будет доступна в следующем обновлении",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ============================================================
    // СПИННЕР СОРТИРОВКИ
    // ============================================================
    private fun setupSortSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSort.adapter = adapter

        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Logger.log(TAG, "Sort selected: ${sortOptions[position]}")
                saveSetting("sort_order", sortOptions[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ============================================================
    // ТЕМА (RadioGroup: Светлая / Тёмная / Как в системе)
    // ============================================================
    private fun setupThemeSelector() {
        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.radioLight.id -> BaseApplication.THEME_LIGHT
                binding.radioDark.id -> BaseApplication.THEME_DARK
                binding.radioSystem.id -> BaseApplication.THEME_SYSTEM
                else -> return@setOnCheckedChangeListener
            }

            Logger.log(TAG, "Theme selected: $mode")

            val current = BaseApplication.getSavedThemeMode(this)
            if (current == mode) {
                Logger.log(TAG, "Theme unchanged, skipping")
                return@setOnCheckedChangeListener
            }

            // Сохраняем и применяем мгновенно
            BaseApplication.saveThemeMode(this, mode)
            BaseApplication.applySavedTheme(this)

            val label = when (mode) {
                BaseApplication.THEME_LIGHT -> "Светлая тема"
                BaseApplication.THEME_DARK -> "Тёмная тема"
                else -> "Как в системе"
            }
            Toast.makeText(this@AppSettingsActivity, "Тема: $label", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 🆕 v20.4.2: УВЕДОМЛЕНИЯ — реальное разрешение POST_NOTIFICATIONS
    // ============================================================

    /**
     * Переключатель «Уведомления» теперь отражает реальное состояние
     * разрешения POST_NOTIFICATIONS (Android 13+) или системного
     * флага «уведомления разрешены» (Android < 13).
     *
     *  - Включение: если разрешения нет → запрашиваем; если пользователь
     *    ранее отказал «навсегда» — открываем системные настройки.
     *  - Выключение: программно отозвать разрешение нельзя → открываем
     *    системные настройки уведомлений приложения.
     */
    private fun setupNotificationsSwitch() {
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (suppressNotificationsListener) return@setOnCheckedChangeListener

            Logger.log(TAG, "Notifications switch toggled: $isChecked")

            if (isChecked) {
                requestNotificationsPermission()
            } else {
                openAppNotificationSettings()
            }
        }
    }

    /**
     * Обновляет состояние переключателя по факту, без вызова listener'а.
     */
    private fun updateNotificationsSwitch() {
        val enabled = areNotificationsGranted()
        suppressNotificationsListener = true
        binding.switchNotifications.isChecked = enabled
        suppressNotificationsListener = false
        Logger.log(TAG, "updateNotificationsSwitch: enabled=$enabled")
    }

    private fun areNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // До Android 13 разрешение не требуется, но пользователь мог
            // выключить уведомления в системных настройках приложения.
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            return nm.areNotificationsEnabled()
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Разрешение не нужно, но уведомления могут быть выключены в системе.
            if (!areNotificationsGranted()) {
                openAppNotificationSettings()
            } else {
                Toast.makeText(this, "Уведомления уже включены", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            // Разрешение есть, но пользователь мог выключить уведомления в системе.
            if (!areNotificationsGranted()) {
                openAppNotificationSettings()
            } else {
                Toast.makeText(this, "Уведомления уже включены", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Пробуем запросить. Если система больше не показывает диалог
        // (пользователь отказал «навсегда» / дважды), shouldShowRequestPermissionRationale
        // вернёт false — тогда отправляем в настройки.
        val activity = this
        val shouldShow = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val prefs = getSharedPreferences(BaseApplication.PREFS_NAME, MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean("post_notifications_asked", false)

        if (!shouldShow && alreadyAsked) {
            Logger.log(TAG, "requestNotificationsPermission: no rationale, open settings")
            showOpenSettingsDialog()
            return
        }

        prefs.edit().putBoolean("post_notifications_asked", true).apply()
        Logger.log(TAG, "requestNotificationsPermission: launching launcher")
        try {
            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            Logger.log(TAG, "requestNotificationsPermission: launch error: ${e.message}")
            updateNotificationsSwitch()
        }
    }

    private fun showOpenSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Уведомления отключены")
            .setMessage(
                "Разрешение на уведомления не выдано.\n\n" +
                    "Откройте настройки приложения и включите уведомления, " +
                    "иначе напоминания о делах не будут приходить."
            )
            .setPositiveButton("Открыть настройки") { _, _ -> openAppNotificationSettings() }
            .setNegativeButton("Отмена") { _, _ -> updateNotificationsSwitch() }
            .setOnCancelListener { updateNotificationsSwitch() }
            .show()
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            startActivity(intent)
            Logger.log(TAG, "openAppNotificationSettings: opened")
        } catch (e: Exception) {
            Logger.log(TAG, "openAppNotificationSettings error: ${e.message}")
            Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
            updateNotificationsSwitch()
        }
    }

    // ============================================================
    // 🆕 v20.3.4: СВОДКА НА СЕГОДНЯ
    // ============================================================
    private fun setupTodayDialogSwitch() {
        binding.switchTodayDialog.setOnCheckedChangeListener { _, isChecked ->
            Logger.log(TAG, "Today dialog enabled: $isChecked")
            TaskReminderPreferences.setTodayDialogEnabled(this, isChecked)
        }
    }

    private fun loadTodayDialogSetting() {
        binding.switchTodayDialog.isChecked =
            TaskReminderPreferences.isTodayDialogEnabled(this)
        Logger.log(TAG, "Today dialog setting loaded")
    }

    // ============================================================
    // ЗАГРУЗКА СОХРАНЁННЫХ НАСТРОЕК
    // ============================================================
    private fun loadSettings() {
        try {
            val prefs = getSharedPreferences(BaseApplication.PREFS_NAME, MODE_PRIVATE)

            // Тема
            val themeMode = BaseApplication.getSavedThemeMode(this)
            Logger.log(TAG, "Loaded theme mode: $themeMode")
            when (themeMode) {
                BaseApplication.THEME_LIGHT -> binding.radioLight.isChecked = true
                BaseApplication.THEME_DARK -> binding.radioDark.isChecked = true
                else -> binding.radioSystem.isChecked = true
            }

            // 🆕 v20.4.2: уведомления — реальное состояние разрешения
            updateNotificationsSwitch()

            // 🆕 v20.3.4: сводка на сегодня
            loadTodayDialogSetting()

            // Язык
            val savedLanguage = prefs.getString("language", "Русский")
            val langIndex = languages.indexOf(savedLanguage).coerceAtLeast(0)
            binding.spinnerLanguage.setSelection(langIndex)

            // Сортировка
            val savedSort = prefs.getString("sort_order", "По имени")
            val sortIndex = sortOptions.indexOf(savedSort).coerceAtLeast(0)
            binding.spinnerSort.setSelection(sortIndex)

            Logger.log(TAG, "Settings loaded")
        } catch (e: Exception) {
            Logger.log(TAG, "Error loading settings", e)
        }
    }

    // ============================================================
    // СОХРАНЕНИЕ НАСТРОЕК
    // ============================================================
    private fun saveSetting(key: String, value: Any) {
        try {
            val prefs = getSharedPreferences(BaseApplication.PREFS_NAME, MODE_PRIVATE)
            prefs.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    else -> return
                }
                apply()
            }
            Logger.log(TAG, "Setting saved: $key = $value")
        } catch (e: Exception) {
            Logger.log(TAG, "Error saving setting: $key", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
