package app.risuona

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Lib {
    class Tr(val id: String, val file: String, var name: String, var title: String, var artist: String, var album: String, var no: Int, var dur: Long, val ta: String = "", val tal: String = "", val tt: String = "", val tn: Int = 0)

    private var cache: MutableList<Tr>? = null

    fun dir(c: Context): File = File(c.filesDir, "music").apply { mkdirs() }
    fun artFile(c: Context, artist: String, album: String): File =
        File(File(c.filesDir, "art").apply { mkdirs() }, "a" + ("$artist|$album").hashCode() + ".jpg")

    @Synchronized
    fun all(c: Context): MutableList<Tr> {
        cache?.let { return it }
        val l = mutableListOf<Tr>()
        val f = File(c.filesDir, "lib.json")
        if (f.exists()) try {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                l.add(Tr(o.getString("id"), o.getString("file"), o.optString("name"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("no"), o.optLong("dur"), o.optString("ta"), o.optString("tal"), o.optString("tt"), o.optInt("tn")))
            }
        } catch (e: Exception) {
        }
        cache = l
        return l
    }

    @Synchronized
    fun json(c: Context): String {
        val a = JSONArray()
        for (t in all(c)) a.put(JSONObject().put("id", t.id).put("file", t.file).put("name", t.name).put("title", t.title).put("artist", t.artist).put("album", t.album).put("no", t.no).put("dur", t.dur).put("ta", t.ta).put("tal", t.tal).put("tt", t.tt).put("tn", t.tn))
        return a.toString()
    }

    @Synchronized
    fun save(c: Context) {
        File(c.filesDir, "lib.json").writeText(json(c))
    }

    @Synchronized
    fun add(c: Context, t: Tr) {
        all(c).add(t)
    }

    @Synchronized
    fun remove(c: Context, id: String) {
        val l = all(c)
        val t = l.find { it.id == id } ?: return
        File(dir(c), t.file).delete()
        l.remove(t)
        save(c)
    }

    @Synchronized
    fun snapshot(c: Context): List<Tr> = ArrayList(all(c))

    fun folder(id: String, title: String, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build()
        ).build()

    fun item(c: Context, t: Tr): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(t.title.ifEmpty { t.name })
            .setArtist(t.artist.ifEmpty { null })
            .setAlbumTitle(t.album.ifEmpty { null })
            .setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (t.album.isNotEmpty()) {
            val af = artFile(c, t.artist, t.album)
            if (af.exists()) md.setArtworkUri(Uri.fromFile(af))
        }
        return MediaItem.Builder().setMediaId(t.id).setUri(Uri.fromFile(File(dir(c), t.file))).setMediaMetadata(md.build()).build()
    }

    fun track(c: Context, id: String): MediaItem? = snapshot(c).find { it.id == id }?.let { item(c, it) }

    private fun sorted(l: List<Tr>): List<Tr> = l.sortedWith(compareBy({ it.artist.ifEmpty { "~" } }, { it.album }, { it.no }, { it.name }))

    fun children(c: Context, parent: String): List<MediaItem> {
        val l = snapshot(c)
        return when {
            parent == "root" -> listOf(folder("artists", "Artisti", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS), folder("all", "Brani"))
            parent == "artists" -> l.map { it.artist }.filter { it.isNotEmpty() }.distinct().sorted().map { folder("ar|$it", it, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS) }
            parent == "all" -> sorted(l).map { item(c, it) }
            parent.startsWith("ar|") -> {
                val ar = parent.substring(3)
                l.filter { it.artist == ar }.map { it.album }.distinct().sorted().map { folder("al|$ar|$it", it) }
            }
            parent.startsWith("al|") -> {
                val p = parent.substring(3)
                l.filter { it.artist + "|" + it.album == p }.sortedBy { it.no }.map { item(c, it) }
            }
            else -> emptyList()
        }
    }

    fun queueFor(c: Context, id: String): Pair<List<MediaItem>, Int>? {
        val l = snapshot(c)
        val t = l.find { it.id == id } ?: return null
        val q = if (t.album.isNotEmpty()) l.filter { it.artist == t.artist && it.album == t.album }.sortedBy { it.no } else sorted(l)
        return Pair(q.map { item(c, it) }, q.indexOfFirst { it.id == id }.coerceAtLeast(0))
    }
}
