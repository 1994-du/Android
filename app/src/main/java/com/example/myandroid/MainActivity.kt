package com.example.myandroid

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private lateinit var loginContainer: ScrollView
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginStatusText: TextView

    private lateinit var authStorage: AuthStorage
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    private val loginHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private var currentCallbackId: String? = null
    private var currentVoiceCallbackId: String? = null
    private var tempImageUri: Uri? = null
    private var mediaRecorder: MediaRecorder? = null
    private var voiceRecordFile: File? = null
    private var voiceRecordStartTimeMs: Long = 0L
    private var isVoiceRecording = false
    private var isAppInBackground = false
    private var keyboardHeight = 0
    private var isKeyboardVisible = false
    private var isWebViewInitialized = false
    private var isH5PageReady = false
    private var statusBarHeightDp = 0f
    private var pendingNotificationPayload: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        authStorage = AuthStorage(this)
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannel()

        webView = findViewById(R.id.webView)
        loginContainer = findViewById(R.id.loginContainer)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        loginProgress = findViewById(R.id.loginProgress)
        loginStatusText = findViewById(R.id.loginStatusText)

        setupBackNavigation()
        registerActivityLaunchers()
        configureWindowInsets()
        setupLoginUi()
        setupKeyboardListener()
        checkNotificationPermission()
        handleIntent(intent)

        if (authStorage.isLoggedIn()) {
            showWebContent(forceReload = false)
        } else {
            showLoginScreen()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    loginContainer.isVisible -> finish()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    private fun registerActivityLaunchers() {
        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            NativeWebSocketManager.onHostResume()
            val uri = tempImageUri
            if (success && uri != null) {
                handleImageResult(uri, "camera")
            } else {
                sendErrorToWeb("拍照失败")
            }
            tempImageUri = null
            currentCallbackId = null
        }

        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            NativeWebSocketManager.onHostResume()
            if (uri != null) {
                handleImageResult(uri)
            } else {
                sendErrorToWeb("选择图片失败")
            }
        }
    }

    private fun configureWindowInsets() {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
        statusBarHeightDp = statusBarHeight / resources.displayMetrics.density
        loginContainer.setPadding(
            loginContainer.paddingLeft,
            loginContainer.paddingTop + statusBarHeight,
            loginContainer.paddingRight,
            loginContainer.paddingBottom
        )
    }

    private fun setupLoginUi() {
        usernameInput.setText(authStorage.getSavedUsername().orEmpty())
        loginButton.setOnClickListener {
            attemptLogin()
        }
        passwordInput.setOnEditorActionListener { _, _, _ ->
            attemptLogin()
            true
        }
    }

    private fun attemptLogin() {
        val username = usernameInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()

        if (username.isBlank() || password.isBlank()) {
            loginStatusText.text = getString(R.string.login_missing_fields)
            return
        }

        setLoginLoading(true, getString(R.string.login_loading))
        performLogin(username, password)
    }

    private fun setLoginLoading(isLoading: Boolean, message: String? = null) {
        loginProgress.isVisible = isLoading
        loginButton.isEnabled = !isLoading
        usernameInput.isEnabled = !isLoading
        passwordInput.isEnabled = !isLoading
        loginStatusText.text = message.orEmpty()
    }

    private fun performLogin(username: String, password: String) {
        val payload = JSONObject()
            .put("username", username)
            .put("account", username)
            .put("password", password)

        val request = Request.Builder()
            .url(LOGIN_API_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        loginHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    setLoginLoading(false, e.message ?: getString(R.string.login_failed_default))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { httpResponse ->
                    val rawBody = httpResponse.body?.string().orEmpty()
                    runOnUiThread {
                        if (httpResponse.isSuccessful) {
                            handleLoginSuccessResponse(username, rawBody, httpResponse.code)
                        } else {
                            val errorMessage = rawBody.ifBlank {
                                "登录失败（HTTP ${httpResponse.code}）"
                            }
                            setLoginLoading(false, errorMessage)
                        }
                    }
                }
            }
        })
    }

    private fun handleLoginSuccessResponse(username: String, rawBody: String, httpCode: Int) {
        if (rawBody.isBlank()) {
            val fallbackResponse = JSONObject()
                .put("code", httpCode)
                .put("msg", "登录成功")
                .put("data", JSONObject().put("username", username))
                .toString()
            authStorage.saveLogin(username, fallbackResponse)
            passwordInput.text?.clear()
            Toast.makeText(this, "登录成功，正在进入 H5", Toast.LENGTH_SHORT).show()
            showWebContent(forceReload = true)
            return
        }

        val loginJson = runCatching { JSONObject(rawBody) }.getOrNull()
        if (loginJson == null) {
            setLoginLoading(false, "登录返回格式不正确")
            return
        }

        val businessCode = loginJson.optInt("code", httpCode)
        val message = loginJson.optString("msg").ifBlank { "登录失败" }
        val token = loginJson.optJSONObject("data")?.optString("token").orEmpty()

        if (businessCode == 200 && token.isNotBlank()) {
            authStorage.saveLogin(username, loginJson.toString())
            passwordInput.text?.clear()
            Toast.makeText(this, "登录成功，正在进入 H5", Toast.LENGTH_SHORT).show()
            showWebContent(forceReload = true)
        } else {
            setLoginLoading(false, message)
        }
    }

    private fun showLoginScreen(clearSavedState: Boolean = false, statusMessage: String? = null) {
        if (clearSavedState) {
            authStorage.clear()
            NativeWebSocketManager.close()
        }

        loginContainer.isVisible = true
        webView.isVisible = false
        setLoginLoading(false, statusMessage)
        usernameInput.setText(authStorage.getSavedUsername().orEmpty())
        passwordInput.text?.clear()
    }

    private fun showWebContent(forceReload: Boolean) {
        val hadInitializedWebView = isWebViewInitialized
        loginContainer.isVisible = false
        webView.isVisible = true
        ensureWebViewInitialized()
        injectDxChatState()
        if (forceReload && hadInitializedWebView) {
            isH5PageReady = false
            webView.reload()
        } else {
            dispatchPendingNotificationClick()
        }
    }

    private fun ensureWebViewInitialized() {
        if (isWebViewInitialized) {
            return
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.SINGLE_COLUMN
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        NativeWebSocketManager.bindWebView(webView)
        isH5PageReady = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                isH5PageReady = true
                injectDxChatState(view)
                view.evaluateJavascript(
                    """
                    window.STATUS_BAR_HEIGHT = $statusBarHeightDp;
                    window.dispatchEvent(new Event('statusBarReady'));
                    """.trimIndent(),
                    null
                )
                NativeWebSocketManager.onPageReady()
                NativeWebSocketManager.onHostResume()
                dispatchPendingNotificationClick()
            }
        }

        webView.addJavascriptInterface(PhotoJavaScriptInterface(), "AndroidPhoto")
        webView.addJavascriptInterface(ChatJavaScriptInterface(), "AndroidChat")
        webView.addJavascriptInterface(VoiceJavaScriptInterface(), "AndroidVoice")
        webView.addJavascriptInterface(WebSocketJavaScriptInterface(), "AndroidWebSocket")
        webView.addJavascriptInterface(DxChatNativeBridgeInterface(), DXCHAT_NATIVE_BRIDGE_NAME)
        webView.loadUrl(H5_URL)
        isWebViewInitialized = true
    }

    private fun injectDxChatState(targetWebView: WebView = webView) {
        val rawResponse = authStorage.getLoginResponse().orEmpty()
        val quotedRawResponse = JSONObject.quote(rawResponse)
        targetWebView.post {
            targetWebView.evaluateJavascript(
                """
                window.DXCHAT = window.DXCHAT || {};
                window.DXCHAT.isNative = true;
                window.DXCHAT.getSecurity = function(success, fail) {
                    try {
                        var token = $DXCHAT_NATIVE_BRIDGE_NAME.getSecurityToken();
                        if (token) {
                            if (typeof success === 'function') {
                                success(token);
                            }
                            return token;
                        }
                        var errorMessage = '未获取到令牌';
                        if (typeof fail === 'function') {
                            fail(errorMessage);
                        }
                        return '';
                    } catch (error) {
                        var finalMessage = error && error.message ? error.message : '获取令牌失败';
                        if (typeof fail === 'function') {
                            fail(finalMessage);
                        }
                        return '';
                    }
                };
                window.NATIVE_LOGIN_RESPONSE_RAW = $quotedRawResponse;
                try {
                    window.NATIVE_LOGIN_RESPONSE = window.NATIVE_LOGIN_RESPONSE_RAW
                        ? JSON.parse(window.NATIVE_LOGIN_RESPONSE_RAW)
                        : null;
                } catch (error) {
                    window.NATIVE_LOGIN_RESPONSE = window.NATIVE_LOGIN_RESPONSE_RAW || null;
                }
                window.dispatchEvent(new Event('dxchatReady'));
                window.dispatchEvent(new Event('nativeLoginReady'));
                """.trimIndent(),
                null
            )
        }
    }

    inner class PhotoJavaScriptInterface {
        @JavascriptInterface
        fun openCamera(callbackId: String) {
            currentCallbackId = callbackId
            if (checkCameraPermission()) {
                launchCamera()
            }
        }

        @JavascriptInterface
        fun openGallery(callbackId: String) {
            currentCallbackId = callbackId
            if (checkStoragePermission()) {
                launchGallery()
            }
        }
    }

    inner class ChatJavaScriptInterface {
        @JavascriptInterface
        fun showNotification(
            title: String,
            message: String,
            senderId: String?,
            senderName: String?,
            conversationId: String?,
            badgeCount: Int
        ) {
            runOnUiThread {
                if (isAppInBackground) {
                    notificationHelper.showChatMessageNotification(
                        title = title,
                        message = message,
                        senderId = senderId,
                        senderName = senderName,
                        conversationId = conversationId,
                        badgeCount = badgeCount
                    )
                } else {
                    playMessageSound()
                    vibrate()
                }
            }
        }

        @JavascriptInterface
        fun cancelNotification(conversationId: String?) {
            runOnUiThread {
                notificationHelper.cancelNotification(conversationId)
            }
        }

        @JavascriptInterface
        fun cancelAllNotifications() {
            runOnUiThread {
                notificationHelper.cancelAllNotifications()
            }
        }

        @JavascriptInterface
        fun updateBadgeCount(count: Int) {
            runOnUiThread {
                notificationHelper.updateBadgeCount(count)
            }
        }

        @JavascriptInterface
        fun clearCacheAndReload() {
            runOnUiThread {
                webView.clearCache(true)
                webView.clearHistory()
                webView.reload()
            }
        }

        @JavascriptInterface
        fun getKeyboardHeight() {
            runOnUiThread {
                val density = resources.displayMetrics.density
                val keyboardHeightDp = keyboardHeight / density
                webView.post {
                    webView.evaluateJavascript(
                        """
                        if (window.handleKeyboardHeight) {
                            window.handleKeyboardHeight($isKeyboardVisible, $keyboardHeightDp);
                        }
                        """.trimIndent(),
                        null
                    )
                }
            }
        }
    }

    inner class VoiceJavaScriptInterface {
        @JavascriptInterface
        fun startRecord(callbackId: String) {
            currentVoiceCallbackId = callbackId
            if (checkAudioPermission()) {
                startVoiceRecording()
            }
        }

        @JavascriptInterface
        fun stopRecord() {
            stopVoiceRecording(cancel = false)
        }

        @JavascriptInterface
        fun cancelRecord() {
            stopVoiceRecording(cancel = true)
        }

        @JavascriptInterface
        fun isRecording(): Boolean {
            return isVoiceRecording
        }
    }

    inner class WebSocketJavaScriptInterface {
        @JavascriptInterface
        fun connect(wsUrl: String, userInfoJson: String?) {
            NativeWebSocketManager.connect(wsUrl, userInfoJson)
        }

        @JavascriptInterface
        fun send(messageJson: String) {
            NativeWebSocketManager.send(messageJson)
        }

        @JavascriptInterface
        fun close() {
            NativeWebSocketManager.close()
        }

        @JavascriptInterface
        fun reconnect() {
            NativeWebSocketManager.reconnect()
        }

        @JavascriptInterface
        fun isConnected(): Boolean {
            return NativeWebSocketManager.isConnected()
        }
    }

    inner class DxChatNativeBridgeInterface {
        @JavascriptInterface
        fun getSecurityToken(): String {
            return authStorage.getSecurityToken().orEmpty()
        }

        @JavascriptInterface
        fun getLoginResponse(): String {
            return authStorage.getLoginResponse().orEmpty()
        }

        @JavascriptInterface
        fun getSavedUsername(): String {
            return authStorage.getSavedUsername().orEmpty()
        }

        @JavascriptInterface
        fun logout() {
            runOnUiThread {
                showLoginScreen(clearSavedState = true)
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
            false
        }
    }

    private fun checkAudioPermission(): Boolean {
        return if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1004)
            false
        }
    }

    private fun checkStoragePermission(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1002)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1001 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else {
                    sendErrorToWeb("相机权限被拒绝")
                }
            }

            1002 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    launchGallery()
                } else {
                    sendErrorToWeb("存储权限被拒绝")
                }
            }

            1004 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startVoiceRecording()
                } else {
                    sendVoiceError("录音权限被拒绝")
                }
            }
        }
    }

    private fun launchCamera() {
        try {
            NativeWebSocketManager.onHostResume()
            tempImageUri = createCameraImageUri()
            if (tempImageUri != null) {
                takePictureLauncher.launch(tempImageUri!!)
            } else {
                sendErrorToWeb("创建图片文件失败")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendErrorToWeb("相机启动失败: ${e.message}")
        }
    }

    private fun launchGallery() {
        NativeWebSocketManager.onHostResume()
        pickImageLauncher.launch("image/*")
    }

    private fun handleImageResult(uri: Uri, type: String = "gallery") {
        try {
            val callbackId = currentCallbackId ?: "default"
            val base64 = uriToBase64(uri)
            if (base64 != null) {
                val data = JSONObject()
                data.put("callbackId", callbackId)
                data.put("image", base64)
                data.put("type", type)
                val js =
                    "if (window.handlePhotoResult) { window.handlePhotoResult(${data}) }"
                webView.post {
                    webView.evaluateJavascript(js, null)
                }
            } else {
                sendErrorToWeb("图片转换失败")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendErrorToWeb("处理图片失败: ${e.message}")
        } finally {
            currentCallbackId = null
        }
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            if (bytes != null) {
                "data:image/jpeg;base64," +
                    android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startVoiceRecording() {
        if (isVoiceRecording) {
            sendVoiceError("正在录音中")
            return
        }

        try {
            val callbackId = currentVoiceCallbackId ?: "default"
            val file = createVoiceRecordFile()
            if (file == null) {
                sendVoiceError("创建录音文件失败")
                return
            }

            val recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(64000)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            voiceRecordFile = file
            voiceRecordStartTimeMs = System.currentTimeMillis()
            isVoiceRecording = true
            currentVoiceCallbackId = callbackId
        } catch (e: Exception) {
            e.printStackTrace()
            stopVoiceRecording(cancel = true)
            sendVoiceError("录音启动失败: ${e.message}")
        }
    }

    private fun stopVoiceRecording(cancel: Boolean) {
        if (!isVoiceRecording && voiceRecordFile == null) {
            if (!cancel) {
                sendVoiceError("当前没有正在录音")
            }
            currentVoiceCallbackId = null
            return
        }

        val callbackId = currentVoiceCallbackId ?: "default"
        val file = voiceRecordFile
        val durationMs = (System.currentTimeMillis() - voiceRecordStartTimeMs).coerceAtLeast(0L)
        val recorder = mediaRecorder

        isVoiceRecording = false
        mediaRecorder = null
        voiceRecordStartTimeMs = 0L

        try {
            if (recorder != null) {
                runCatching { recorder.stop() }
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (cancel) {
            runCatching { file?.delete() }
            voiceRecordFile = null
            currentVoiceCallbackId = null
            return
        }

        if (file == null || !file.exists()) {
            voiceRecordFile = null
            sendVoiceError("录音文件不存在")
            return
        }

        Thread {
            try {
                val base64 = audioFileToBase64(file)
                if (base64 == null) {
                    sendVoiceError("录音转换失败")
                    return@Thread
                }

                val data = JSONObject()
                data.put("callbackId", callbackId)
                data.put("audio", base64)
                data.put("type", "voice")
                data.put("mimeType", "audio/mp4")
                data.put("fileName", file.name)
                data.put("durationMs", durationMs)

                val js =
                    "if (window.handleVoiceResult) { window.handleVoiceResult(${data}) }"
                webView.post {
                    webView.evaluateJavascript(js, null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                sendVoiceError("处理录音失败: ${e.message}")
            } finally {
                runCatching { file.delete() }
                voiceRecordFile = null
                currentVoiceCallbackId = null
            }
        }.start()
    }

    private fun createVoiceRecordFile(): File? {
        return try {
            val dir = File(cacheDir, "voice_recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "voice_$timestamp.m4a")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun audioFileToBase64(file: File): String? {
        return try {
            val bytes = file.readBytes()
            "data:audio/mp4;base64," +
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun createCameraImageUri(): Uri? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_$timestamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/MyAndroid"
                    )
                }
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun sendErrorToWeb(error: String) {
        val callbackId = currentCallbackId ?: "default"
        val data = JSONObject()
        data.put("callbackId", callbackId)
        data.put("error", error)
        val js = "if (window.handlePhotoError) { window.handlePhotoError(${data}) }"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
        currentCallbackId = null
    }

    private fun sendVoiceError(error: String) {
        val callbackId = currentVoiceCallbackId ?: "default"
        val data = JSONObject()
        data.put("callbackId", callbackId)
        data.put("error", error)
        val js = "if (window.handleVoiceError) { window.handleVoiceError(${data}) }"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
        currentVoiceCallbackId = null
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                true
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1003
                )
                false
            }
        } else {
            true
        }
    }

    private fun playMessageSound() {
        try {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, soundUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrationEffect = android.os.VibrationEffect.createOneShot(
                    500,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
                vibrator.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val action = it.getStringExtra("action")
            if (action == "open_chat") {
                val payload = JSONObject()
                    .put("senderId", it.getStringExtra("senderId").orEmpty())
                    .put("senderName", it.getStringExtra("senderName").orEmpty())
                    .put("conversationId", it.getStringExtra("conversationId").orEmpty())
                pendingNotificationPayload = payload
                dispatchPendingNotificationClick()
                notificationHelper.cancelNotification(payload.optString("conversationId"))
            }
        }
    }

    private fun dispatchPendingNotificationClick() {
        if (!isWebViewInitialized || !isH5PageReady) {
            return
        }
        val payload = pendingNotificationPayload ?: return
        val js = """
            if (window.handleNotificationClick) {
                window.handleNotificationClick($payload);
            }
        """.trimIndent()
        webView.post {
            webView.evaluateJavascript(js, null)
        }
        pendingNotificationPayload = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
        if (isWebViewInitialized) {
            NativeWebSocketManager.bindWebView(webView)
            NativeWebSocketManager.onHostResume()
        }
    }

    override fun onPause() {
        super.onPause()
        isAppInBackground = true
    }

    private fun setupKeyboardListener() {
        val rootView = window.decorView.rootView
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)

            val screenHeight = rootView.height
            val detectedKeyboardHeight = screenHeight - rect.bottom

            if (detectedKeyboardHeight > screenHeight * 0.15) {
                if (!isKeyboardVisible) {
                    keyboardHeight = detectedKeyboardHeight
                    isKeyboardVisible = true
                    notifyKeyboardStatus(true, detectedKeyboardHeight)
                }
            } else {
                if (isKeyboardVisible) {
                    isKeyboardVisible = false
                    notifyKeyboardStatus(false, 0)
                }
            }
        }
    }

    private fun notifyKeyboardStatus(visible: Boolean, height: Int) {
        val density = resources.displayMetrics.density
        val heightDp = height / density

        webView.post {
            webView.evaluateJavascript(
                """
                if (window.handleKeyboardStatus) {
                    window.handleKeyboardStatus($visible, $heightDp);
                }
                """.trimIndent(),
                null
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isWebViewInitialized) {
            NativeWebSocketManager.unbindWebView(webView)
        }
        if (isVoiceRecording || voiceRecordFile != null) {
            runCatching {
                mediaRecorder?.stop()
            }
            runCatching {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            }
            mediaRecorder = null
            isVoiceRecording = false
            voiceRecordStartTimeMs = 0L
            runCatching { voiceRecordFile?.delete() }
            voiceRecordFile = null
            currentVoiceCallbackId = null
        }
        notificationHelper.cancelAllNotifications()
    }

    companion object {
        private const val H5_URL = "http://106.15.207.57/MyApp/"

        // If the backend login path is different, adjust this constant directly.
        private const val LOGIN_API_URL = "http://106.15.207.57/api/auth/app/login"
        private const val DXCHAT_NATIVE_BRIDGE_NAME = "DXCHAT_NATIVE"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
