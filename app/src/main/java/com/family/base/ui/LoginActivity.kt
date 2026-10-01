package com.family.base.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.base.Config
import com.family.base.R
import com.family.base.data.TokenStorage
import com.family.base.databinding.ActivityLoginBinding
import com.family.base.util.Logger
import net.openid.appauth.*
import java.util.concurrent.atomic.AtomicReference

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var tokenStorage: TokenStorage

    /**
     * 🆕 БАЗА6 этап 2: было lateinit var — падало в onDestroy(),
     * если onCreate() вышел раньше (токен уже есть → openMainActivity → finish).
     * Теперь nullable + безопасный вызов.
     */
    private var authService: AuthorizationService? = null

    private val authStateManager = AtomicReference<AuthState>()
    private val TAG = "LoginActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== LoginActivity onCreate START ===")

        try {
            binding = ActivityLoginBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        try {
            tokenStorage = TokenStorage(this)
            Logger.log(TAG, "TokenStorage initialized")

            val token = tokenStorage.getAccessToken()
            Logger.log(TAG, "Token exists: ${token != null}")

            if (token != null) {
                Logger.log(TAG, "Token found, opening MainActivity")
                openMainActivity()
                return
            }
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize TokenStorage", e)
            return
        }

        try {
            authService = AuthorizationService(this)
            Logger.log(TAG, "AuthorizationService initialized")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to initialize AuthorizationService", e)
            return
        }

        setupListeners()

        Logger.log(TAG, "=== LoginActivity onCreate FINISHED ===")
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            Logger.log(TAG, "Login button clicked")
            performLogin()
        }
    }

    private fun performLogin() {
        Logger.log(TAG, "Starting login flow...")

        // 🆕 ЗАЩИТА: authService может быть null, если инициализация упала
        val service = authService
        if (service == null) {
            Logger.log(TAG, "performLogin: authService is null — cannot start OAuth")
            Toast.makeText(this, "Ошибка авторизации: сервис недоступен", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(Config.YANDEX_OAUTH_AUTHORIZE_URL),
            Uri.parse(Config.YANDEX_OAUTH_TOKEN_URL)
        )

        val redirectUri = Uri.parse(Config.REDIRECT_URI)

        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            Config.CLIENT_ID,
            ResponseTypeValues.CODE,
            redirectUri
        )
            .setScope("cloud_api:disk.read cloud_api:disk.write")
            .build()

        Logger.log(TAG, "Auth request built: $authRequest")

        val authIntent = service.getAuthorizationRequestIntent(authRequest)

        try {
            Logger.log(TAG, "Starting auth intent...")
            startActivityForResult(authIntent, REQUEST_AUTH)
        } catch (e: Exception) {
            Logger.log(TAG, "Error starting auth intent", e)
            Toast.makeText(this, "Ошибка авторизации", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_AUTH) {
            Logger.log(TAG, "Auth result: requestCode=$requestCode, resultCode=$resultCode")

            if (resultCode == RESULT_OK && data != null) {
                Logger.log(TAG, "Auth successful, exchanging code for token...")
                exchangeCodeForToken(data)
            } else {
                Logger.log(TAG, "Auth cancelled or failed")
                Toast.makeText(this, "Авторизация отменена", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exchangeCodeForToken(data: Intent) {
        Logger.log(TAG, "Exchanging code for token...")

        // 🆕 ЗАЩИТА: на всякий случай проверяем сервис
        val service = authService
        if (service == null) {
            Logger.log(TAG, "exchangeCodeForToken: authService is null")
            Toast.makeText(this, "Ошибка авторизации: сервис недоступен", Toast.LENGTH_SHORT).show()
            return
        }

        val authResponse = AuthorizationResponse.fromIntent(data)
        val authException = AuthorizationException.fromIntent(data)

        if (authResponse != null) {
            Logger.log(TAG, "Auth response received, performing token exchange")

            try {
                service.performTokenRequest(
                    authResponse.createTokenExchangeRequest(),
                    object : AuthorizationService.TokenResponseCallback {
                        override fun onTokenRequestCompleted(
                            response: TokenResponse?,
                            ex: AuthorizationException?
                        ) {
                            if (response != null) {
                                Logger.log(TAG, "Token received successfully")
                                handleTokenResponse(response)
                            } else {
                                Logger.log(TAG, "Token request failed: ${ex?.message}")
                                Toast.makeText(
                                    this@LoginActivity,
                                    "Ошибка получения токена: ${ex?.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.log(TAG, "Error during token exchange", e)
                Toast.makeText(this, "Ошибка обмена токена", Toast.LENGTH_SHORT).show()
            }
        } else if (authException != null) {
            Logger.log(TAG, "Auth exception: ${authException.message}")
            Toast.makeText(this, "Ошибка авторизации: ${authException.message}", Toast.LENGTH_SHORT).show()
        } else {
            Logger.log(TAG, "No auth response or exception")
            Toast.makeText(this, "Неизвестная ошибка авторизации", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTokenResponse(response: TokenResponse) {
        Logger.log(TAG, "Handling token response...")

        val accessToken = response.accessToken
        val refreshToken = response.refreshToken
        val idToken = response.idToken

        if (accessToken != null) {
            Logger.log(TAG, "Access token received: ${accessToken.take(20)}...")

            try {
                tokenStorage.saveAccessToken(accessToken)
                tokenStorage.saveRefreshToken(refreshToken)
                tokenStorage.saveIdToken(idToken)

                // Извлекаем email пользователя из id_token
                idToken?.let {
                    try {
                        val payload = it.split(".")[1]
                        val decoded = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE))
                        val json = org.json.JSONObject(decoded)
                        val email = json.optString("email")
                        val name = json.optString("name")

                        if (email.isNotEmpty()) {
                            tokenStorage.saveUserInfo(email, name)
                            Logger.log(TAG, "User info saved: email=$email, name=$name")
                        }
                    } catch (e: Exception) {
                        Logger.log(TAG, "Error parsing id_token", e)
                    }
                }

                Logger.log(TAG, "Tokens saved successfully")
                Toast.makeText(this, "Авторизация успешна", Toast.LENGTH_SHORT).show()
                openMainActivity()
            } catch (e: Exception) {
                Logger.log(TAG, "Error saving tokens", e)
                Toast.makeText(this, "Ошибка сохранения токена", Toast.LENGTH_SHORT).show()
            }
        } else {
            Logger.log(TAG, "No access token in response")
            Toast.makeText(this, "Токен не получен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMainActivity() {
        Logger.log(TAG, "Opening MainActivity...")
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")

        // 🆕 БАЗА6 этап 2: безопасный dispose — authService может быть null
        try {
            authService?.dispose()
            Logger.log(TAG, "AuthorizationService disposed (or was null)")
        } catch (e: Exception) {
            Logger.log(TAG, "Error disposing AuthorizationService", e)
        } finally {
            authService = null
        }
    }

    companion object {
        private const val REQUEST_AUTH = 1001
    }
}
