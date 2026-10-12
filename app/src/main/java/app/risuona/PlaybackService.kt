package app.risuona

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(Viz))).build()
        }
        player = ExoPlayer.Builder(this, rf)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, Cb()).setSessionActivity(pi).build()
        hnd.postDelayed(saver, 5000)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED)) saveState()
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        hnd.removeCallbacks(saver)
        try {
            saveState()
        } catch (e: Exception) {
        }
        session.release()
        player.release()
        super.onDestroy()
    }

    private var lastAuto = 0L
    private val hnd = Handler(Looper.getMainLooper())
    private val saver = object : Runnable {
        override fun run() {
            try {
                if (player.isPlaying) saveState()
            } catch (e: Exception) {
            }
            hnd.postDelayed(this, 5000)
        }
    }

    private fun saveState() {
        val n = player.mediaItemCount
        if (n == 0) return
        val sb = StringBuilder()
        for (k in 0 until n) {
            if (k > 0) sb.append('\n')
            sb.append(player.getMediaItemAt(k).mediaId)
        }
        getSharedPreferences("st", MODE_PRIVATE).edit()
            .putString("ids", sb.toString())
            .putInt("idx", player.currentMediaItemIndex)
            .putLong("pos", player.currentPosition)
            .apply()
    }

    private fun resume(): MediaSession.MediaItemsWithStartPosition? {
        val sp = getSharedPreferences("st", MODE_PRIVATE)
        val ids = (sp.getString("ids", "") ?: "").split('\n').filter { it.isNotEmpty() }
        val lastId = ids.getOrNull(sp.getInt("idx", 0))?.substringAfterLast('|')
        val all = Lib.snapshot(this)
        if (all.isEmpty()) return null
        val stars = all.filter { it.st }
        val pool = (if (stars.isNotEmpty()) stars else all).toMutableList()
        val last = all.find { it.id == lastId }
        if (last != null && pool.none { it.id == last.id }) pool.add(0, last)
        val start = pool.indexOfFirst { it.id == lastId }
        return MediaSession.MediaItemsWithStartPosition(
            pool.map { Lib.item(this, it) },
            if (start >= 0) start else (Math.random() * pool.size).toInt(),
            if (start >= 0) sp.getLong("pos", 0L) else 0L
        )
    }

    private fun autoStart() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAuto < 10000) return
        lastAuto = now
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (!player.isPlaying) {
                    if (player.mediaItemCount == 0) {
                        val r = resume()
                        if (r != null) {
                            player.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs)
                            player.prepare()
                        }
                    }
                    player.shuffleModeEnabled = true
                    if (player.mediaItemCount > 0) player.play()
                }
            } catch (e: Exception) {
            }
        }, 1000)
    }

    private inner class Cb : MediaLibrarySession.Callback {
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) autoStart()
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val r = resume()
            player.shuffleModeEnabled = true
            return if (r != null) Futures.immediateFuture(r) else Futures.immediateFailedFuture(UnsupportedOperationException())
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Lib.folder("root", "Risuona"), LibraryParams.Builder().setExtras(Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            }).build()))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(Lib.children(this@PlaybackService, parentId)), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val t = Lib.track(this@PlaybackService, mediaId)
            return Futures.immediateFuture(if (t != null) LibraryResult.ofItem(t, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull { Lib.track(this@PlaybackService, it.mediaId) }.toMutableList())

        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val q = Lib.queueFor(this@PlaybackService, mediaItems[0].mediaId)
                if (q != null) return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(q.first, q.second, C.TIME_UNSET))
            }
            val l = mediaItems.mapNotNull { Lib.track(this@PlaybackService, it.mediaId) }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(l, if (startIndex in l.indices) startIndex else 0, startPositionMs))
        }
    }
}
