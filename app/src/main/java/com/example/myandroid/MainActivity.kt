package com.example.myandroid

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import android.os.Build
import android.view.WindowManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.RingtoneManager
import android.content.Context
import org.json.JSONObject
class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    private var currentCallbackId: String? = null
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var takePicturePreviewLauncher: ActivityResultLauncher<Void?>
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private var tempImageUri: Uri? = null
    private lateinit var notificationHelper: NotificationHelper
    private var isAppInBackground = false
    private var keyboardHeight = 0
    private var isKeyboardVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ⭐ 关键：让内容延伸到状态栏
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // ⭐ 状态栏透明
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        // ⭐ 拦截返回键（包括手势返回）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()  // 返回 H5 上一页
                } else {
                    finish() // 退出 App
                }
            }
        })
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.SINGLE_COLUMN
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // ⭐ 获取状态栏高度
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else 0
        val density = resources.displayMetrics.density
        val statusBarHeightDp = statusBarHeight / density
        webView.webViewClient = object:WebViewClient(){
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                val url = request.url.toString()

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
            }
            override fun onPageFinished(view: WebView,url:String){
                super.onPageFinished(view,url)
                view.evaluateJavascript("""
                    window.STATUS_BAR_HEIGHT = $statusBarHeightDp;
                    window.dispatchEvent(new Event('statusBarReady'));
                """.trimIndent(), null)
            }
        }
        webView.loadUrl("http://106.15.207.57/MyApp/")

        // Initialize activity result launchers
        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && tempImageUri != null) {
                tempImageUri?.let { uri ->
                    handleImageResult(uri)
                }
            } else {
                sendErrorToWeb("拍照失败")
            }
            tempImageUri = null
        }

        // 相机预览模式（不需要存储权限）
        takePicturePreviewLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                val callbackId = currentCallbackId ?: "default"
                val base64 = bitmapToBase64(bitmap)
                if (base64 != null) {
                    val data = JSONObject()
                    data.put("callbackId", callbackId)
                    data.put("image", base64)
                    data.put("type", "camera")
                    val js = "if (window.handlePhotoResult) { window.handlePhotoResult(" + data.toString() + ") }"
                    webView.post {
                        webView.evaluateJavascript(js, null)
                    }
                } else {
                    sendErrorToWeb("图片转换失败")
                }
            } else {
                sendErrorToWeb("拍照失败")
            }
            currentCallbackId = null
        }

        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                handleImageResult(uri)
            } else {
                sendErrorToWeb("选择图片失败")
            }
        }

        // Add JavaScript interface
        webView.addJavascriptInterface(PhotoJavaScriptInterface(), "AndroidPhoto")
        webView.addJavascriptInterface(ChatJavaScriptInterface(), "AndroidChat")
        
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannel()
        
        checkNotificationPermission()
        
        handleIntent(intent)
        
        setupKeyboardListener()
    }

    @Suppress("DEPRECATION")
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
                    webView.evaluateJavascript("""
                        if (window.handleKeyboardHeight) {
                            window.handleKeyboardHeight($isKeyboardVisible, $keyboardHeightDp);
                        }
                    """.trimIndent(), null)
                }
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
            false
        }
    }

    private fun checkStoragePermission(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用 READ_MEDIA_IMAGES
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 12- 使用 READ_EXTERNAL_STORAGE
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1002)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
        }
    }

    private fun launchCamera() {
        try {
            // 使用相机预览模式，不需要存储权限
            takePicturePreviewLauncher.launch(null)
        } catch (e: Exception) {
            e.printStackTrace()
            sendErrorToWeb("相机启动失败: ${e.message}")
        }
    }

    private fun launchGallery() {
        pickImageLauncher.launch("image/*")
    }

    private fun handleImageResult(uri: Uri) {
        try {
            val callbackId = currentCallbackId ?: "default"
            val base64 = uriToBase64(uri)
            if (base64 != null) {
                val data = JSONObject()
                data.put("callbackId", callbackId)
                data.put("image", base64)
                data.put("type", "gallery")
                val js = "if (window.handlePhotoResult) { window.handlePhotoResult(" + data.toString() + ") }"
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
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            if (bytes != null) {
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun bitmapToBase64(bitmap: android.graphics.Bitmap): String? {
        try {
            val byteArrayOutputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
            val bytes = byteArrayOutputStream.toByteArray()
            byteArrayOutputStream.close()
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun sendErrorToWeb(error: String) {
        val callbackId = currentCallbackId ?: "default"
        val data = JSONObject()
        data.put("callbackId", callbackId)
        data.put("error", error)
        val js = "if (window.handlePhotoError) { window.handlePhotoError(" + data.toString() + ") }"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
        currentCallbackId = null
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
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
                val vibrationEffect = android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
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
                val senderId = it.getStringExtra("senderId")
                val senderName = it.getStringExtra("senderName")
                val conversationId = it.getStringExtra("conversationId")
                
                webView.post {
                    val jsCode = """
                        if (window.handleNotificationClick) {
                            window.handleNotificationClick({
                                senderId: '${senderId ?: ""}',
                                senderName: '${senderName ?: ""}',
                                conversationId: '${conversationId ?: ""}'
                            });
                        }
                    """.trimIndent()
                    webView.evaluateJavascript(jsCode, null)
                }
                
                notificationHelper.cancelNotification(conversationId)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
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
            val keyboardHeight = screenHeight - rect.bottom
            
            if (keyboardHeight > screenHeight * 0.15) {
                // 输入法弹出
                if (!isKeyboardVisible) {
                    this.keyboardHeight = keyboardHeight
                    isKeyboardVisible = true
                    notifyKeyboardStatus(true, keyboardHeight)
                }
            } else {
                // 输入法收起
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
            webView.evaluateJavascript("""
                if (window.handleKeyboardStatus) {
                    window.handleKeyboardStatus($visible, $heightDp);
                }
            """.trimIndent(), null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationHelper.cancelAllNotifications()
    }
}