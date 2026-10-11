package app.risuona

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fut: ListenableFuture<MediaController>? = null
    private var ctl: MediaController? = null
    private var imgCb: ValueCallback<Array<Uri>>? = null
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            push()
            h.postDelayed(this, 500)
        }
    }
    private var vizOn = false
    private val vizTick = object : Runnable {
        override fun run() {
            val live = ctl?.isPlaying == true && android.os.SystemClock.elapsedRealtime() - Viz.stamp < 400
            if (live) {
                val b = Viz.bands
                val sb = StringBuilder("window.onViz&&onViz([")
                for (k in b.indices) {
                    if (k > 0) sb.append(',')
                    sb.append((b[k] * 100).toInt() / 100f)
                }
                web.evaluateJavascript(sb.append("])").toString(), null)
                vizOn = true
            } else if (vizOn) {
                vizOn = false
                web.evaluateJavascript("window.onViz&&onViz([])", null)
            }
            h.postDelayed(this, 50)
        }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            push()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                imgCb?.onReceiveValue(null)
                imgCb = cb
                return try {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 1)
                    true
                } catch (e: Exception) {
                    imgCb = null
                    false
                }
            }
        }
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
    }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        fut = f
        f.addListener({
            try {
                val c = f.get()
                ctl = c
                c.addListener(listener)
                push()
            } catch (e: Exception) {
            }
        }, MoreExecutors.directExecutor())
        h.post(tick)
        h.post(vizTick)
    }

    override fun onStop() {
        h.removeCallbacks(tick)
        h.removeCallbacks(vizTick)
        ctl?.removeListener(listener)
        ctl = null
        fut?.let { MediaController.releaseFuture(it) }
        fut = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun push() {
        val c = ctl ?: return
        val d = c.duration
        val o = JSONObject()
            .put("id", (c.currentMediaItem?.mediaId ?: "").substringAfterLast('|'))
            .put("playing", c.isPlaying)
            .put("pos", c.currentPosition)
            .put("dur", if (d == C.TIME_UNSET) 0L else d)
        web.evaluateJavascript("window.onNative&&onNative($o)", null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val cd = data.clipData
            if (cd != null) {
                for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
        }
        if (requestCode == 1) {
            imgCb?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            imgCb = null
        }
        if (requestCode == 2 && uris.isNotEmpty()) {
            Thread {
                var dup = 0
                for (u in uris) {
                    try {
                        if (!importOne(u)) dup++
                    } catch (e: Exception) {
                    }
                }
                Lib.save(this)
                val msg = if (dup == 1) "1 brano era già presente" else "$dup brani erano già presenti"
                val js = "window.onLibrary&&onLibrary();" + if (dup > 0) "window.toast&&toast('$msg');" else ""
                runOnUiThread { web.evaluateJavascript(js, null) }
            }.start()
        }
    }

    private fun importOne(u: Uri): Boolean {
        var name = "brano"
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val id = System.currentTimeMillis().toString(36) + (Math.random() * 1e6).toInt().toString(36)
        val ext = name.substringAfterLast('.', "mp3").take(5)
        val f = File(Lib.dir(this), "$id.$ext")
        contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        var dur = 0L
        var ta = ""
        var tal = ""
        var tt = ""
        var tn = 0
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.path)
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ta = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            tal = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            tt = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            tn = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            r.release()
        } catch (e: Exception) {
        }
        val len = f.length()
        val same = Lib.snapshot(this).any { File(Lib.dir(this), it.file).length() == len && (it.name == name || (dur > 0L && it.dur == dur)) }
        if (same) {
            f.delete()
            return false
        }
        Lib.add(this, Lib.Tr(id, f.name, name, "", "", "", 0, dur, ta, tal, tt, tn))
        return true
    }

    inner class Bridge {
        private fun ui(f: (MediaController) -> Unit) {
            runOnUiThread { ctl?.let(f) }
        }

        @JavascriptInterface
        fun add() {
            runOnUiThread {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 2)
                } catch (e: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun setPlaylists(json: String) {
            try {
                File(filesDir, "pl.json").writeText(json)
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun library(): String = Lib.json(this@MainActivity)

        @JavascriptInterface
        fun setMeta(json: String) {
            try {
                val a = JSONArray(json)
                val m = Lib.snapshot(this@MainActivity).associateBy { it.id }
                for (k in 0 until a.length()) {
                    val o = a.getJSONObject(k)
                    val t = m[o.getString("id")] ?: continue
                    t.title = o.optString("title")
                    t.artist = o.optString("artist")
                    t.album = o.optString("album")
                    t.no = o.optInt("no")
                }
                Lib.save(this@MainActivity)
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun play(ids: String, index: Int) {
            val a = JSONArray(ids)
            val l = ArrayList<MediaItem>()
            for (k in 0 until a.length()) l.add(MediaItem.Builder().setMediaId(a.getString(k)).build())
            ui {
                it.setMediaItems(l, index, 0L)
                it.prepare()
                it.play()
            }
        }

        @JavascriptInterface
        fun toggle() = ui { if (it.isPlaying) it.pause() else it.play() }

        @JavascriptInterface
        fun next() = ui { it.seekToNextMediaItem() }

        @JavascriptInterface
        fun prev() = ui { it.seekToPrevious() }

        @JavascriptInterface
        fun seek(ms: Double) = ui { it.seekTo(ms.toLong()) }

        @JavascriptInterface
        fun shuffle(on: Boolean) = ui { it.shuffleModeEnabled = on }

        @JavascriptInterface
        fun repeat(mode: Int) = ui { it.repeatMode = mode }

        @JavascriptInterface
        fun remove(id: String) {
            Lib.remove(this@MainActivity, id)
            ui { c ->
                for (k in c.mediaItemCount - 1 downTo 0) if (c.getMediaItemAt(k).mediaId == id) c.removeMediaItem(k)
            }
        }

        @JavascriptInterface
        fun art(id: String): String = try {
            val t = Lib.snapshot(this@MainActivity).find { it.id == id }
            val r = MediaMetadataRetriever()
            r.setDataSource(File(Lib.dir(this@MainActivity), t!!.file).path)
            val p = r.embeddedPicture
            r.release()
            if (p == null) "" else Base64.encodeToString(p, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }

        @JavascriptInterface
        fun setArt(artist: String, album: String, b64: String) {
            try {
                Lib.artFile(this@MainActivity, artist, album).writeBytes(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) {
            }
        }
    }
}
