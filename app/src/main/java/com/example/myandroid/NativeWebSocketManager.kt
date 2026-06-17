package com.example.myandroid

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

object NativeWebSocketManager {
    private const val TAG = "NativeWebSocket"
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val MAX_PENDING_MESSAGES = 200
    private const val MAX_PENDING_WEB_EVENTS = 200

    private val reconnectDelaysMs = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingQueue = ArrayDeque<String>()
    private val pendingWebEvents = ArrayDeque<String>()

    private lateinit var appContext: Context
    private var initialized = false
    private var webViewRef: WeakReference<WebView>? = null
    private var pageReady = false
    private var currentWsUrl: String? = null
    private var currentUserInfoJson: String? = null
    private var webSocket: WebSocket? = null
    private var reconnectRunnable: Runnable? = null
    private var connectTimeoutRunnable: Runnable? = null
    private var reconnectAttempt = 0
    private var socketToken = 0
    private var manualCloseRequested = false
    private var lastReason: String? = null

    @Volatile
    private var connected = false

    @Volatile
    private var ready = false

    @Volatile
    private var state = "CLOSED"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        registerNetworkCallback()
    }

    fun bindWebView(webView: WebView) {
        mainHandler.post {
            webViewRef = WeakReference(webView)
        }
    }

    fun unbindWebView(webView: WebView?) {
        mainHandler.post {
            val current = webViewRef?.get()
            if (webView == null || current == null || current === webView) {
                webViewRef?.clear()
                webViewRef = null
                pageReady = false
            }
        }
    }

    fun onPageReady() {
        mainHandler.post {
            pageReady = true
            flushPendingWebEvents()
            emitStatus()
        }
    }

    fun onHostResume() {
        mainHandler.post {
            if (!manualCloseRequested && currentWsUrl != null && !ready && state != "CONNECTING") {
                reconnectInternal(immediate = true)
            } else {
                emitStatus()
            }
        }
    }

    fun connect(wsUrl: String, userInfoJson: String?) {
        mainHandler.post {
            val normalizedUrl = wsUrl.trim()
            if (normalizedUrl.isEmpty()) {
                emitStatus(reason = "Missing wsUrl")
                return@post
            }
            Log.d(TAG, "connect start, wsUrl=$normalizedUrl")

            manualCloseRequested = false
            currentWsUrl = normalizedUrl
            currentUserInfoJson = userInfoJson

            when (state) {
                "OPEN" -> {
                    Log.d(TAG, "connect reuse OPEN")
                    emitStatus()
                    return@post
                }
                "CONNECTING" -> {
                    Log.d(TAG, "connect already CONNECTING, keep timeout running")
                    scheduleConnectTimeout()
                    return@post
                }
            }

            if (state == "FAILED" || state == "CLOSED" || state == "RECONNECTING" || webSocket == null) {
                cleanupCurrentSocket("connect")
                openConnection()
                return@post
            }

            cleanupCurrentSocket("connect-reset")
            openConnection()
        }
    }

    fun send(messageJson: String) {
        mainHandler.post {
            if (messageJson.isBlank()) return@post
            Log.d(TAG, "send, ready=$ready state=$state")
            if (ready && webSocket?.send(messageJson) == true) {
                Log.d(TAG, "send immediately: ${messageJson.take(120)}")
                return@post
            }

            enqueuePendingMessage(messageJson)
            Log.d(TAG, "enqueue pending message, size=${pendingQueue.size}")
            if (!manualCloseRequested) {
                reconnectInternal(immediate = true)
            }
        }
    }

    fun close() {
        mainHandler.post {
            manualCloseRequested = true
            cancelReconnect()
            cancelConnectTimeout()
            pendingQueue.clear()
            connected = false
            ready = false
            state = "CLOSED"
            lastReason = "Closed by H5"
            Log.d(TAG, "close start")
            cleanupCurrentSocket("close")
            emitStatus(reason = lastReason)
        }
    }

    fun reconnect() {
        mainHandler.post {
            if (currentWsUrl.isNullOrBlank()) {
                emitStatus(reason = "Missing wsUrl")
                return@post
            }

            manualCloseRequested = false
            Log.d(TAG, "reconnect start")
            forceReconnect()
        }
    }

    fun isConnected(): Boolean {
        Log.d(TAG, "isConnected -> $ready")
        return ready
    }

    private fun openConnection() {
        val wsUrl = currentWsUrl ?: return
        Log.d(TAG, "openConnection: $wsUrl")

        cancelReconnect()
        cancelConnectTimeout()
        socketToken += 1
        val token = socketToken
        connected = false
        ready = false
        state = "CONNECTING"
        lastReason = null
        emitStatus()
        scheduleConnectTimeout()

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    if (token != socketToken) {
                        webSocket.close(1000, "Superseded")
                        return@post
                    }

                    this@NativeWebSocketManager.webSocket = webSocket
                    cancelConnectTimeout()
                    connected = true
                    ready = true
                    state = "OPEN"
                    lastReason = null
                    reconnectAttempt = 0
                    Log.d(TAG, "onOpen: $wsUrl")
                    emitStatus()
                    sendOnlineMessage()
                    flushPendingQueue()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post {
                    if (token != socketToken) return@post
                    Log.d(TAG, "onMessage text: ${text.take(200)}")
                    dispatchMessageToH5(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                mainHandler.post {
                    if (token != socketToken) return@post
                    Log.d(TAG, "onMessage bytes")
                    dispatchMessageToH5(bytes.utf8())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (token != socketToken) return@post
                    Log.w(TAG, "onClosing: code=$code reason=$reason manual=$manualCloseRequested")
                    connected = false
                    ready = false
                    state = "CLOSING"
                    lastReason = reason.ifBlank { "Closing" }
                    emitStatus(reason = lastReason)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (token != socketToken) return@post
                    cleanupSocketReference()
                    connected = false
                    ready = false
                    lastReason = reason.ifBlank { "Closed($code)" }
                    Log.w(TAG, "onClosed: code=$code reason=$reason manual=$manualCloseRequested")
                    state = "CLOSED"
                    emitStatus(reason = lastReason)
                    if (!manualCloseRequested) {
                        scheduleReconnect(reason = lastReason)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    if (token != socketToken) return@post
                    cleanupSocketReference()
                    cancelConnectTimeout()
                    connected = false
                    ready = false
                    state = "FAILED"
                    lastReason = buildString {
                        append(t.message ?: "WebSocket failure")
                        response?.code?.let { append(" (HTTP ").append(it).append(")") }
                    }
                    Log.e(TAG, "onFailure: url=$wsUrl response=${response?.code} reason=$lastReason", t)
                    emitStatus(reason = lastReason)
                    if (!manualCloseRequested) scheduleReconnect(reason = lastReason)
                }
            }
        })
    }

    private fun reconnectInternal(immediate: Boolean) {
        if (manualCloseRequested || currentWsUrl.isNullOrBlank()) return
        if (ready || state == "CONNECTING") return

        if (immediate) {
            cancelReconnect()
            forceReconnect()
        } else {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect(reason: String? = lastReason) {
        if (manualCloseRequested || currentWsUrl.isNullOrBlank()) return
        if (ready || state == "CONNECTING" || reconnectRunnable != null) return

        val delay = reconnectDelaysMs[reconnectAttempt.coerceAtMost(reconnectDelaysMs.lastIndex)]
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(reconnectDelaysMs.size - 1)
        state = "RECONNECTING"
        lastReason = reason
        Log.w(TAG, "scheduleReconnect: attempt=$reconnectAttempt delay=${delay}ms url=$currentWsUrl reason=$reason")
        emitStatus(reason = reason)

        reconnectRunnable = Runnable {
            reconnectRunnable = null
            if (manualCloseRequested || currentWsUrl.isNullOrBlank() || ready || state == "CONNECTING") {
                return@Runnable
            }
            Log.d(TAG, "reconnect runnable fired")
            forceReconnect()
        }
        mainHandler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun scheduleConnectTimeout() {
        cancelConnectTimeout()
        connectTimeoutRunnable = Runnable {
            if (ready || state != "CONNECTING") return@Runnable
            lastReason = "WebSocket connection timeout"
            Log.w(TAG, "connect timeout fired")
            cleanupSocketReference()
            state = "FAILED"
            connected = false
            ready = false
            emitStatus(reason = lastReason)
            if (!manualCloseRequested) {
                scheduleReconnect(reason = lastReason)
            }
        }
        mainHandler.postDelayed(connectTimeoutRunnable!!, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeoutRunnable?.let(mainHandler::removeCallbacks)
        connectTimeoutRunnable = null
    }

    private fun forceReconnect() {
        Log.d(TAG, "forceReconnect")
        cleanupCurrentSocket("reconnect")
        state = "RECONNECTING"
        connected = false
        ready = false
        emitStatus(reason = lastReason)
        openConnection()
    }

    private fun cleanupCurrentSocket(from: String) {
        Log.d(TAG, "cleanupCurrentSocket from=$from")
        cancelConnectTimeout()
        val socket = webSocket
        socketToken += 1
        webSocket = null
        runCatching {
            socket?.cancel()
        }.onFailure {
            Log.w(TAG, "cleanupCurrentSocket cancel failure: ${it.message}")
        }
        cleanupSocketReference()
    }

    private fun cleanupSocketReference() {
        webSocket = null
    }

    private fun flushPendingQueue() {
        if (!ready) return

        while (pendingQueue.isNotEmpty()) {
            val nextMessage = pendingQueue.removeFirst()
            val sent = webSocket?.send(nextMessage) == true
            if (!sent) {
                pendingQueue.addFirst(nextMessage)
                connected = false
                ready = false
                lastReason = "Failed to flush pending queue"
                Log.e(TAG, lastReason!!)
                state = "FAILED"
                emitStatus(reason = lastReason)
                if (!manualCloseRequested) {
                    scheduleReconnect(reason = lastReason)
                }
                return
            }
        }
    }

    private fun enqueuePendingMessage(messageJson: String) {
        if (pendingQueue.size >= MAX_PENDING_MESSAGES) {
            pendingQueue.removeFirst()
        }
        pendingQueue.addLast(messageJson)
    }

    private fun dispatchMessageToH5(messageJson: String) {
        val js = """
            if (window.handleNativeWebSocketMessage) {
                window.handleNativeWebSocketMessage(${JSONObject.quote(messageJson)});
            }
        """.trimIndent()
        evaluateOrBuffer(js)
    }

    private fun emitStatus(reason: String? = null) {
        val status = JSONObject()
            .put("connected", connected)
            .put("ready", ready)
            .put("state", state)
            .put("url", currentWsUrl ?: "")
            .put("attempt", reconnectAttempt)

        val finalReason = reason ?: lastReason
        if (!finalReason.isNullOrBlank()) {
            status.put("reason", finalReason)
        }

        val js = """
            if (window.handleNativeWebSocketStatus) {
                window.handleNativeWebSocketStatus(${JSONObject.quote(status.toString())});
            }
        """.trimIndent()
        evaluateOrBuffer(js)
    }

    private fun evaluateOrBuffer(js: String) {
        val webView = webViewRef?.get()
        if (webView == null || !pageReady) {
            if (pendingWebEvents.size >= MAX_PENDING_WEB_EVENTS) {
                pendingWebEvents.removeFirst()
            }
            pendingWebEvents.addLast(js)
            return
        }

        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    private fun flushPendingWebEvents() {
        val webView = webViewRef?.get() ?: return
        if (!pageReady) return

        while (pendingWebEvents.isNotEmpty()) {
            val js = pendingWebEvents.removeFirst()
            webView.post {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    private fun sendOnlineMessage() {
        val userInfoJson = currentUserInfoJson ?: return

        runCatching {
            val userInfo = JSONObject(userInfoJson)
            val payload = JSONObject()
                .put("userId", userInfo.opt("userId"))
                .put(
                    "username",
                    userInfo.optString(
                        "username",
                        userInfo.optString("fromUsername", userInfo.optString("name"))
                    )
                )
                .put("avatar", userInfo.optString("avatar"))

            val onlineMessage = JSONObject()
                .put("type", "userOnline")
                .put("payload", payload)

            Log.d(TAG, "sendOnlineMessage: $onlineMessage")
            webSocket?.send(onlineMessage.toString())
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerNetworkCallback() {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (!manualCloseRequested && currentWsUrl != null && !ready && state != "CONNECTING") {
                        reconnectInternal(immediate = true)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder().build()
            connectivityManager.registerNetworkCallback(request, callback)
        }
    }
}
