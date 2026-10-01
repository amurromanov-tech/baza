package com.family.base.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.base.AppUser
import com.family.base.BaseApplication
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.data.local.AppDatabase
import com.family.base.data.remote.model.PinHasher
import com.family.base.data.remote.model.UserModel
import com.family.base.data.remote.model.UsersFile
import com.family.base.data.repository.CatalogRepository
import com.family.base.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectUserActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SelectUserActivity"
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 4
    }

    private lateinit var tokenStorage: TokenStorage
    private lateinit var repository: CatalogRepository

    private var usersFile: UsersFile? = null
    private var isLoading = false

    // Views
    private lateinit var btnUserAlexey: Button
    private lateinit var btnUserRima: Button
    private lateinit var btnUserDima: Button
    private lateinit var btnGuest: Button
    private lateinit var tvStatusAlexey: TextView
    private lateinit var tvStatusRima: TextView
    private lateinit var tvStatusDima: TextView
    private lateinit var tvLoading: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== SelectUserActivity onCreate START ===")

        try {
            setContentView(R.layout.activity_select_user)
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            finish()
            return
        }

        tokenStorage = TokenStorage(this)
        repository = CatalogRepository(AppDatabase.getInstance(this))

        btnUserAlexey = findViewById(R.id.btnUserAlexey)
        btnUserRima = findViewById(R.id.btnUserRima)
        btnUserDima = findViewById(R.id.btnUserDima)
        btnGuest = findViewById(R.id.btnGuest)
        tvStatusAlexey = findViewById(R.id.tvStatusAlexey)
        tvStatusRima = findViewById(R.id.tvStatusRima)
        tvStatusDima = findViewById(R.id.tvStatusDima)
        tvLoading = findViewById(R.id.tvLoading)

        setupClickListeners()

        // Скачиваем users.json с Диска
        loadUsers()

        Logger.log(TAG, "=== SelectUserActivity onCreate FINISHED ===")
    }

    private fun setupClickListeners() {
        btnUserAlexey.setOnClickListener { onUserClicked(AppUser.ALEXEY) }
        btnUserRima.setOnClickListener { onUserClicked(AppUser.RIMA) }
        btnUserDima.setOnClickListener { onUserClicked(AppUser.DIMA) }
        btnGuest.setOnClickListener { onGuestClicked() }
    }

    // ============================================================
    // ЗАГРУЗКА СПИСКА ПОЛЬЗОВАТЕЛЕЙ
    // ============================================================
    private fun loadUsers() {
        isLoading = true
        updateLoadingState()

        lifecycleScope.launch {
            try {
                usersFile = withContext(Dispatchers.IO) { repository.downloadUsersJson() }
                Logger.log(TAG, "Loaded users: ${usersFile?.users?.size ?: 0}")
            } catch (e: Exception) {
                Logger.log(TAG, "Error loading users: ${e.message}")
                usersFile = null
            } finally {
                isLoading = false
                updateUserStatuses()
                updateLoadingState()
            }
        }
    }

    private fun updateLoadingState() {
        tvLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        val enabled = !isLoading
        btnUserAlexey.isEnabled = enabled
        btnUserRima.isEnabled = enabled
        btnUserDima.isEnabled = enabled
        btnGuest.isEnabled = enabled
    }

    private fun updateUserStatuses() {
        updateStatusFor(tvStatusAlexey, AppUser.ALEXEY)
        updateStatusFor(tvStatusRima, AppUser.RIMA)
        updateStatusFor(tvStatusDima, AppUser.DIMA)
    }

    private fun updateStatusFor(tv: TextView, user: AppUser) {
        val saved = repository.findUserByName(usersFile, user.displayName)
        tv.text = if (saved != null) "✅ PIN задан" else "⚠️ PIN не задан"
    }

    // ============================================================
    // КЛИК ПО ПОЛЬЗОВАТЕЛЮ
    // ============================================================
    private fun onUserClicked(user: AppUser) {
        val saved = repository.findUserByName(usersFile, user.displayName)
        if (saved == null) {
            showSetPinDialog(user)
        } else {
            showEnterPinDialog(user, saved)
        }
    }

    private fun onGuestClicked() {
        Logger.log(TAG, "Guest selected")
        tokenStorage.setCurrentUser(AppUser.GUEST.displayName)
        Toast.makeText(this, "Вход как гость (только просмотр)", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    // ============================================================
    // ДИАЛОГ: ЗАДАТЬ PIN (регистрация)
    // ============================================================
    private fun showSetPinDialog(user: AppUser) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val etPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN (4 цифры)"
            setSelectAllOnFocus(true)
        }
        val etPinRepeat = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Повторите PIN"
        }
        container.addView(etPin)
        container.addView(etPinRepeat)

        val dialog = AlertDialog.Builder(this)
            .setTitle("${user.emoji} Задать PIN для ${user.displayName}")
            .setMessage("Придумайте PIN из 4 цифр. Он будет сохранён на Яндекс.Диске.")
            .setView(container)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = etPin.text.toString().trim()
                val pinRepeat = etPinRepeat.text.toString().trim()

                if (pin.length < MIN_PIN_LENGTH) {
                    Toast.makeText(this, "PIN должен быть $MIN_PIN_LENGTH цифры", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pin != pinRepeat) {
                    Toast.makeText(this, "PIN не совпадает", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                saveNewUserPin(user, pin)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveNewUserPin(user: AppUser, pin: String) {
        lifecycleScope.launch {
            try {
                val pinHash = PinHasher.sha256(pin)
                val newUser = UserModel(name = user.displayName, pinHash = pinHash)

                val updated = withContext(Dispatchers.IO) {
                    repository.addOrUpdateUser(usersFile, newUser)
                }
                val success = withContext(Dispatchers.IO) {
                    repository.uploadUsersJson(updated)
                }

                if (success) {
                    usersFile = updated
                    tokenStorage.setCurrentUser(user.displayName)
                    Toast.makeText(this@SelectUserActivity, "PIN сохранён. Добро пожаловать, ${user.displayName}!", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this@SelectUserActivity, "Не удалось сохранить PIN на Диск", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error saving PIN: ${e.message}", e)
                Toast.makeText(this@SelectUserActivity, "Ошибка сохранения PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================================================
    // ДИАЛОГ: ВВЕСТИ PIN (вход)
    // ============================================================
    private fun showEnterPinDialog(user: AppUser, saved: UserModel) {
        val etPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setSelectAllOnFocus(true)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        container.addView(etPin)

        val dialog = AlertDialog.Builder(this)
            .setTitle("${user.emoji} ${user.displayName}")
            .setMessage("Введите PIN")
            .setView(container)
            .setPositiveButton("Войти", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = etPin.text.toString().trim()
                if (pin.isEmpty()) {
                    Toast.makeText(this, "Введите PIN", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (PinHasher.matches(pin, saved.pinHash)) {
                    tokenStorage.setCurrentUser(user.displayName)
                    Toast.makeText(this@SelectUserActivity, "Добро пожаловать, ${user.displayName}!", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this, "Неверный PIN", Toast.LENGTH_SHORT).show()
                    etPin.setText("")
                }
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
