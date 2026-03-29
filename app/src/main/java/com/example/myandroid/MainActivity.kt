package com.example.myandroid

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.activity.OnBackPressedCallback
import android.os.Build
import android.view.WindowManager
class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView

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
    }
}