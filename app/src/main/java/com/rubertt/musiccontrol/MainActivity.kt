package com.rubertt.musiccontrol

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val pickFolder = 1002
    private val pickAudio = 1001
    private var pendingChannel = 0

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
        @JavascriptInterface fun chooseFolder() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, pickFolder)
            }
        }
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
        @JavascriptInterface fun assign(uri: String, channel: Int, title: String) {
            val intent = Intent(this@MainActivity, AudioService::class.java).apply {
                putExtra("action", "load")
                putExtra("channel", channel.coerceIn(0, 4))
                putExtra("uri", uri)
                putExtra("title", title)
            }
            startForegroundService(intent)
            val safeTitle = JSONObject.quote(title)
            runOnUiThread {
                web.evaluateJavascript("window.trackLoaded && window.trackLoaded($channel, $safeTitle)", null)
            }
        }
        @JavascriptInterface fun command(action: String, channel: Int, value: Float, durationSeconds: Float) {
            val intent = Intent(this@MainActivity, AudioService::class.java).apply {
                putExtra("action", action)
                putExtra("channel", channel.coerceIn(0, 4))
                putExtra("value", value.coerceIn(0f, 1f))
                putExtra("durationMs", (durationSeconds.coerceIn(0.2f, 60f) * 1000).toLong())
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
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == pickFolder) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val songs = scanAudioFolder(uri)
                val payload = JSONArray()
                songs.forEach { payload.put(JSONObject().put("name", it.first).put("uri", it.second)) }
                val json = payload.toString()
                web.evaluateJavascript("window.libraryLoaded && window.libraryLoaded($json)", null)
            } catch (e: Exception) {
                web.evaluateJavascript("window.libraryError && window.libraryError('Não foi possível ler esta pasta.')", null)
            }
        } else if (requestCode == pickAudio) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            val intent = Intent(this, AudioService::class.java).apply {
                putExtra("action", "load")
                putExtra("channel", pendingChannel)
                putExtra("uri", uri.toString())
            }
            startForegroundService(intent)
            web.evaluateJavascript("window.trackLoaded && window.trackLoaded($pendingChannel, 'Áudio selecionado')", null)
        }
    }

    private fun scanAudioFolder(treeUri: Uri): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        scanDocument(treeUri, rootId, found)
        return found.sortedBy { it.first.lowercase() }
    }

    private fun scanDocument(treeUri: Uri, documentId: String, found: MutableList<Pair<String, String>>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: "Áudio"
                val mime = cursor.getString(mimeIndex) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanDocument(treeUri, id, found)
                } else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "wma")) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    found.add(name to fileUri.toString())
                }
            }
        }
    }
}
