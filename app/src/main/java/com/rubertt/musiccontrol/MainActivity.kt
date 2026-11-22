package com.rubertt.musiccontrol

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                restoreSavedState()
            }
        }
        web.addJavascriptInterface(Bridge(), "NativeAudio")
        setContentView(web)
        web.loadUrl("file:///android_asset/www/index.html")
    }

    inner class Bridge {
        @JavascriptInterface fun chooseFolder() {
            runOnUiThread { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), pickFolder) }
        }
        @JavascriptInterface fun choose(channel: Int) {
            pendingChannel = channel.coerceIn(0, 9)
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
                startActivityForResult(intent, pickAudio)
            }
        }
        @JavascriptInterface fun assign(uri: String, channel: Int, title: String) {
            val intent = Intent(this@MainActivity, AudioService::class.java).apply {
                putExtra("action", "load"); putExtra("channel", channel.coerceIn(0, 9)); putExtra("uri", uri); putExtra("title", title); putExtra("autoPlay", false)
            }
            getSharedPreferences("music_control", MODE_PRIVATE).edit()
                .putString("channel_${channel}_uri", uri)
                .putString("channel_${channel}_title", title)
                .apply()
            startForegroundService(intent)
            val safeTitle = JSONObject.quote(title)
            runOnUiThread { web.evaluateJavascript("window.trackLoaded && window.trackLoaded($channel, $safeTitle)", null) }
        }
        @JavascriptInterface fun setPlaylist(json: String, fadeSeconds: Float) {
            val intent = Intent(this@MainActivity, AudioService::class.java).apply {
                putExtra("action", "playlist"); putExtra("playlist", json); putExtra("fadeSeconds", fadeSeconds.coerceIn(1f, 30f))
            }
            startForegroundService(intent)
        }
        @JavascriptInterface fun command(action: String, channel: Int, value: Float, durationSeconds: Float) {
            val intent = Intent(this@MainActivity, AudioService::class.java).apply {
                putExtra("action", action); putExtra("channel", channel.coerceIn(0, 9))
                putExtra("value", value.coerceIn(0f, 1f)); putExtra("durationMs", (durationSeconds.coerceIn(0.2f, 60f) * 1000).toLong())
            }
            if (action == "play" || action == "load" || action == "playlist") startForegroundService(intent) else startService(intent)
        }
        @JavascriptInterface fun stopAll() { startService(Intent(this@MainActivity, AudioService::class.java).setAction("STOP_ALL")) }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == pickFolder) {
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags)
                getSharedPreferences("music_control", MODE_PRIVATE).edit().putString("music_folder_uri", uri.toString()).apply()
                val songs = scanAudioFolder(uri)
                val payload = JSONArray()
                songs.forEach { payload.put(JSONObject().put("name", it.first).put("uri", it.second)) }
                web.evaluateJavascript("window.libraryLoaded && window.libraryLoaded($payload)", null)
            } catch (_: Exception) {
                web.evaluateJavascript("window.libraryError && window.libraryError('Não foi possível ler esta pasta.')", null)
            }
        } else if (requestCode == pickAudio) {
            try {
                val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            val title = uri.lastPathSegment?.substringAfterLast('/') ?: "Áudio selecionado"
            getSharedPreferences("music_control", MODE_PRIVATE).edit()
                .putString("channel_${pendingChannel}_uri", uri.toString())
                .putString("channel_${pendingChannel}_title", title)
                .apply()
            val intent = Intent(this, AudioService::class.java).apply {
                putExtra("action", "load"); putExtra("channel", pendingChannel); putExtra("uri", uri.toString())
                putExtra("title", title); putExtra("autoPlay", false)
            }
            startForegroundService(intent)
            val safeTitle = JSONObject.quote(title)
            web.evaluateJavascript("window.trackLoaded && window.trackLoaded($pendingChannel, $safeTitle)", null)
        }
    }

    private fun restoreSavedState() {
        val prefs = getSharedPreferences("music_control", MODE_PRIVATE)
        val folderValue = prefs.getString("music_folder_uri", null)
        if (folderValue != null) {
            try {
                val folder = Uri.parse(folderValue)
                val songs = scanAudioFolder(folder)
                val payload = JSONArray()
                songs.forEach { payload.put(JSONObject().put("name", it.first).put("uri", it.second)) }
                web.evaluateJavascript("window.libraryLoaded && window.libraryLoaded($payload)", null)
            } catch (_: Exception) {
                web.evaluateJavascript("window.libraryError && window.libraryError('A pasta salva não está mais acessível. Selecione-a novamente.')", null)
            }
        }
        for (channel in 0 until 10) {
            val uri = prefs.getString("channel_${channel}_uri", null) ?: continue
            val title = prefs.getString("channel_${channel}_title", "Áudio selecionado") ?: "Áudio selecionado"
            val intent = Intent(this, AudioService::class.java).apply {
                putExtra("action", "restore"); putExtra("channel", channel)
                putExtra("uri", uri); putExtra("title", title); putExtra("autoPlay", false)
            }
            startForegroundService(intent)
            val safeTitle = JSONObject.quote(title)
            web.evaluateJavascript("window.trackLoaded && window.trackLoaded($channel, $safeTitle)", null)
        }
    }

    private fun scanAudioFolder(treeUri: Uri): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        scanDocument(treeUri, DocumentsContract.getTreeDocumentId(treeUri), found)
        return found.sortedBy { it.first.lowercase() }
    }

    private fun scanDocument(treeUri: Uri, documentId: String, found: MutableList<Pair<String, String>>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: "Áudio"
                val mime = cursor.getString(mimeIndex) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) scanDocument(treeUri, id, found)
                else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "wma")) {
                    found.add(name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString())
                }
            }
        }
    }
}
