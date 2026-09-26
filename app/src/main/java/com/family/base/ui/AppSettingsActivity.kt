package com.family.base.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.base.BaseApplication
import com.family.base.databinding.ActivityAppSettingsBinding
import com.family.base.util.Logger

class AppSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSettingsBinding
    private val TAG = "AppSettingsActivity"

    private val languages = arrayOf("Русский", "English")
    private val sortOptions = arrayOf(
        "По имени",
        "По сроку годности",
        "По цене",
        "По дате добавления"
    )

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
        loadSettings()

        Logger.log(TAG, "=== AppSettingsActivity onCreate FINISHED ===")
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
    // УВЕДОМЛЕНИЯ
    // ============================================================
    private fun setupNotificationsSwitch() {
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            Logger.log(TAG, "Notifications: $isChecked")
            saveSetting("notifications_enabled", isChecked)
            Toast.makeText(
                this@AppSettingsActivity,
                "Уведомления будут доступны в следующем обновлении",
                Toast.LENGTH_SHORT
            ).show()
        }
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

            // Уведомления
            binding.switchNotifications.isChecked = prefs.getBoolean("notifications_enabled", true)

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
