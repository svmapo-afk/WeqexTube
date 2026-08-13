package com.github.libretube.ui.activities

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.github.libretube.oauth.YoutubeOAuthManager
import com.github.libretube.ui.base.BaseActivity
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest

class YoutubeLoginActivity : BaseActivity() {
    private lateinit var status: TextView
    private lateinit var loginButton: Button
    private lateinit var signOutButton: Button

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { activityResult ->
        val data = activityResult.data
        if (data == null) {
            showFailure("Вход отменён.")
            return@registerForActivityResult
        }
        runCatching {
            Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
        }.onSuccess { result ->
            if (YoutubeOAuthManager.accept(result) != null) showAuthorized()
            else showFailure("Google не вернул токен YouTube.")
        }.onFailure {
            showFailure("Ошибка авторизации Google: ${it.message.orEmpty()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "YouTube-аккаунт"
        YoutubeOAuthManager.initialize(applicationContext)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(this).apply {
            text = "Вход через Google"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        }, matchWrap())
        layout.addView(TextView(this).apply {
            text = "WeqexTube запросит доступ только для чтения YouTube. Пароль и client_secret приложение не получает и не хранит."
            textSize = 16f
            setPadding(0, padding / 2, 0, padding)
        }, matchWrap())
        status = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, padding)
        }
        layout.addView(status, matchWrap())
        loginButton = Button(this).apply {
            text = "ВОЙТИ ЧЕРЕЗ GOOGLE"
            setOnClickListener { authorize() }
        }
        layout.addView(loginButton, matchWrap())
        signOutButton = Button(this).apply {
            text = "ОТКЛЮЧИТЬ GOOGLE-АККАУНТ"
            setOnClickListener { revokeAccess() }
        }
        layout.addView(signOutButton, matchWrap())
        setContentView(layout)
        updateState()
    }

    private fun authorize() {
        loginButton.isEnabled = false
        status.text = "Открываю системный вход Google…"
        Identity.getAuthorizationClient(this)
            .authorize(YoutubeOAuthManager.authorizationRequest())
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    authorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else if (YoutubeOAuthManager.accept(result) != null) {
                    showAuthorized()
                } else {
                    showFailure("Google не вернул токен YouTube.")
                }
            }
            .addOnFailureListener {
                showFailure("Не удалось открыть вход Google: ${it.message.orEmpty()}")
            }
    }

    private fun revokeAccess() {
        loginButton.isEnabled = false
        signOutButton.isEnabled = false
        status.text = "Отключаю аккаунт…"
        val request = RevokeAccessRequest.builder()
            .setScopes(YoutubeOAuthManager.requestedScopes)
            .build()
        Identity.getAuthorizationClient(this).revokeAccess(request).addOnCompleteListener {
            YoutubeOAuthManager.markAuthorized(false)
            updateState()
        }
    }

    private fun showAuthorized() {
        status.text = "YouTube-аккаунт подключён. Токен будет обновляться через Google Play services."
        loginButton.isEnabled = true
        signOutButton.isEnabled = true
    }

    private fun showFailure(message: String) {
        status.text = message
        loginButton.isEnabled = true
        signOutButton.isEnabled = YoutubeOAuthManager.isSignedIn()
    }

    private fun updateState() {
        val authorized = YoutubeOAuthManager.isSignedIn()
        status.text = if (authorized) "YouTube-аккаунт подключён." else "YouTube-аккаунт не подключён."
        loginButton.isEnabled = true
        signOutButton.isEnabled = authorized
    }

    private fun matchWrap() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
