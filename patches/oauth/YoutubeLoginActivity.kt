package com.github.libretube.ui.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.github.libretube.oauth.YoutubeOAuthManager
import com.github.libretube.ui.base.BaseActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class YoutubeLoginActivity : BaseActivity() {
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var startButton: Button
    private lateinit var openButton: Button
    private lateinit var signOutButton: Button
    private var verificationUrl = "https://www.google.com/device"
    private var pollingJob: Job? = null

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
            text = "Авторизация нужна только для запросов YouTube. Пароль приложение не получает. Токен хранится зашифрованным на этом телефоне."
            textSize = 16f
            setPadding(0, padding / 2, 0, padding)
        }, matchWrap())
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        layout.addView(status, matchWrap())
        code = TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, padding, 0, padding)
            setOnClickListener {
                if (text.isNotBlank()) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Код Google", text))
                    Toast.makeText(this@YoutubeLoginActivity, "Код скопирован", Toast.LENGTH_SHORT).show()
                }
            }
        }
        layout.addView(code, matchWrap())
        startButton = Button(this).apply { text = "Получить код"; setOnClickListener { startLogin() } }
        layout.addView(startButton, matchWrap())
        openButton = Button(this).apply {
            text = "Открыть Google и ввести код"
            isEnabled = false
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl))) }
        }
        layout.addView(openButton, matchWrap())
        signOutButton = Button(this).apply {
            text = "Выйти из аккаунта"
            setOnClickListener { YoutubeOAuthManager.signOut(); updateSignedInState() }
        }
        layout.addView(signOutButton, matchWrap())
        setContentView(layout)
        updateSignedInState()
    }

    override fun onDestroy() { pollingJob?.cancel(); super.onDestroy() }

    private fun startLogin() {
        pollingJob?.cancel()
        startButton.isEnabled = false
        status.text = "Получаю код…"
        pollingJob = lifecycleScope.launch {
            runCatching { YoutubeOAuthManager.requestDeviceAuthorization() }
                .onSuccess { authorization ->
                    code.text = authorization.userCode
                    verificationUrl = authorization.verificationUrl
                    openButton.isEnabled = true
                    status.text = "Войдите в Google и введите этот код."
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl))) }
                    when (val result = YoutubeOAuthManager.waitForAuthorization(authorization)) {
                        is YoutubeOAuthManager.PollResult.Success -> {
                            status.text = "Вход выполнен. Вернитесь к видео и повторите запуск."
                            code.text = ""
                            openButton.isEnabled = false
                            startButton.isEnabled = true
                            signOutButton.isEnabled = true
                        }
                        is YoutubeOAuthManager.PollResult.Failure -> { status.text = result.message; startButton.isEnabled = true }
                        YoutubeOAuthManager.PollResult.Pending -> Unit
                    }
                }
                .onFailure { status.text = "Не удалось начать вход: ${it.message.orEmpty()}"; startButton.isEnabled = true }
        }
    }

    private fun updateSignedInState() {
        val signedIn = YoutubeOAuthManager.isSignedIn()
        status.text = if (signedIn) "Аккаунт подключён." else "Аккаунт не подключён."
        signOutButton.isEnabled = signedIn
        startButton.isEnabled = true
        code.text = ""
        openButton.isEnabled = false
    }

    private fun matchWrap() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
