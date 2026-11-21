package com.rubertt.musiccontrol

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pendingChannel = 0
    private val pickAudio = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "NativeAudio")
        setContentView(web)
        web.loadUrl("file:///android_asset/www/index.html")
    }

    inner class Bridge {
        @JavascriptInterface fun choose(channel: Int) {
            pendingChannel = channel.coerceIn(0, 4)
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }
                startActivityForResult(intent, pickAudio)
            }
        }
        @JavascriptInterface fun command(action: String, channel: Int, value: Float) {
            val intent = Intent(this@MainActivity, AudioService::class.java).apply {
                putExtra("action", action)
                putExtra("channel", channel.coerceIn(0, 4))
                putExtra("value", value.coerceIn(0f, 1f))
            }
            if (action == "play" || action == "load") startForegroundService(intent) else startService(intent)
        }
        @JavascriptInterface fun stopAll() {
            startService(Intent(this@MainActivity, AudioService::class.java).setAction("STOP_ALL"))
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickAudio && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val intent = Intent(this, AudioService::class.java).apply {
                    putExtra("action", "load")
                    putExtra("channel", pendingChannel)
                    putExtra("uri", uri.toString())
                }
                startForegroundService(intent)
                web.evaluateJavascript("window.trackLoaded && window.trackLoaded($pendingChannel, 'Áudio selecionado')", null)
            }
        }
    }
}
