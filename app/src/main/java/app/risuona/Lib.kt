package app.risuona

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Lib {
    class Tr(val id: String, val file: String, var name: String, var title: String, var artist: String, var album: String, var no: Int, var dur: Long, val ta: String = "", val tal: String = "", val tt: String = "", val tn: Int = 0, var st: Boolean = false)

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
                l.add(Tr(o.getString("id"), o.getString("file"), o.optString("name"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("no"), o.optLong("dur"), o.optString("ta"), o.optString("tal"), o.optString("tt"), o.optInt("tn"), o.optInt("st") == 1))
            }
        } catch (e: Exception) {
        }
        cache = l
        return l
    }

    @Synchronized
    fun json(c: Context): String {
        val a = JSONArray()
        for (t in all(c)) a.put(JSONObject().put("id", t.id).put("file", t.file).put("name", t.name).put("title", t.title).put("artist", t.artist).put("album", t.album).put("no", t.no).put("dur", t.dur).put("ta", t.ta).put("tal", t.tal).put("tt", t.tt).put("tn", t.tn).put("st", if (t.st) 1 else 0))
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
    fun merge(c: Context, json: String): Int {
        var n = 0
        try {
            val a = JSONArray(json)
            val l = all(c)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val id = o.getString("id")
                if (l.none { it.id == id } && File(dir(c), o.getString("file")).exists()) {
                    l.add(Tr(id, o.getString("file"), o.optString("name"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("no"), o.optLong("dur"), o.optString("ta"), o.optString("tal"), o.optString("tt"), o.optInt("tn"), o.optInt("st") == 1))
                    n++
                }
            }
            save(c)
        } catch (e: Exception) {
        }
        return n
    }

    @Synchronized
    fun snapshot(c: Context): List<Tr> = ArrayList(all(c))

    class Pl(val id: String, val name: String, val ids: List<String>)

    fun favs(c: Context): MutableList<String> =
        (c.getSharedPreferences("fav", Context.MODE_PRIVATE).getString("ids", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()

    fun setFavs(c: Context, l: List<String>) {
        c.getSharedPreferences("fav", Context.MODE_PRIVATE).edit().putString("ids", l.distinct().joinToString("\n")).apply()
    }

    fun plays(c: Context): String = c.getSharedPreferences("plays", Context.MODE_PRIVATE).getString("j", "{}") ?: "{}"

    @Synchronized
    fun incPlay(c: Context, id: String) {
        try {
            val o = JSONObject(plays(c))
            o.put(id, o.optInt(id) + 1)
            c.getSharedPreferences("plays", Context.MODE_PRIVATE).edit().putString("j", o.toString()).apply()
        } catch (e: Exception) {
        }
    }

    fun isFav(c: Context, id: String): Boolean = favs(c).contains(id)

    fun toggleFav(c: Context, id: String): Boolean {
        val l = favs(c)
        val on = !l.remove(id)
        if (on) l.add(id)
        setFavs(c, l)
        return on
    }

    fun playlists(c: Context): List<Pl> {
        val l = ArrayList<Pl>()
        try {
            val f = File(c.filesDir, "pl.json")
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val ids = ArrayList<String>()
                    val ja = o.getJSONArray("ids")
                    for (k in 0 until ja.length()) ids.add(ja.getString(k))
                    l.add(Pl(o.getString("id"), o.optString("name"), ids))
                }
            }
        } catch (e: Exception) {
        }
        return l
    }

    fun artUri(artist: String, album: String): Uri =
        Uri.parse("content://app.risuona.art/a").buildUpon().appendQueryParameter("a", artist).appendQueryParameter("b", album).build()

    fun folder(id: String, title: String, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, art: Uri? = null): MediaItem {
        val md = MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(type)
        if (art != null) md.setArtworkUri(art)
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(md.build()).build()
    }

    fun item(c: Context, t: Tr, mid: String = t.id): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(t.title.ifEmpty { t.name })
            .setArtist(t.artist.ifEmpty { null })
            .setAlbumTitle(t.album.ifEmpty { null })
            .setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (t.artist.isNotEmpty()) {
            if (t.album.isNotEmpty() && artFile(c, t.artist, t.album).exists()) md.setArtworkUri(artUri(t.artist, t.album))
            else if (artFile(c, t.artist, "").exists()) md.setArtworkUri(artUri(t.artist, ""))
        }
        return MediaItem.Builder().setMediaId(mid).setUri(Uri.fromFile(File(dir(c), t.file))).setMediaMetadata(md.build()).build()
    }

    fun track(c: Context, id: String): MediaItem? {
        val real = id.substringAfterLast('|')
        return snapshot(c).find { it.id == real }?.let { item(c, it, id) }
    }

    private fun sorted(l: List<Tr>): List<Tr> = l.sortedWith(compareBy({ it.artist.ifEmpty { "~" } }, { it.album }, { it.no }, { it.name }))

    fun children(c: Context, parent: String): List<MediaItem> {
        val l = snapshot(c)
        return when {
            parent == "root" -> listOf(folder("artists", "Home", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS), folder("fav", "Preferiti", MediaMetadata.MEDIA_TYPE_PLAYLIST, artUri("playlist", "Preferiti")), folder("playlists", "Playlist", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS), folder("all", "Brani"))
            parent == "fav" -> favs(c).mapNotNull { id -> l.find { it.id == id } }.map { item(c, it, "f|" + it.id) }
            parent == "playlists" -> playlists(c).map { folder("pl|" + it.id, it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, artUri("playlist", it.name)) }
            parent.startsWith("pl|") -> {
                val pl = playlists(c).find { it.id == parent.substring(3) }
                pl?.ids?.mapNotNull { id -> l.find { it.id == id } }?.map { item(c, it, "p|" + pl.id + "|" + it.id) } ?: emptyList()
            }
            parent == "artists" -> l.map { it.artist }.filter { it.isNotEmpty() }.distinct().sorted().map { folder("ar|$it", it, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, artUri(it, "")) }
            parent == "all" -> sorted(l).map { item(c, it) }
            parent.startsWith("ar|") -> {
                val ar = parent.substring(3)
                val mine = l.filter { it.artist == ar }
                val albums = mine.map { it.album }.distinct().sorted()
                if (albums.size <= 2) mine.sortedWith(compareBy({ it.album }, { it.no })).map { item(c, it) }
                else albums.map { folder("al|$ar|$it", it, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, artUri(ar, it)) }
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
        if (id.startsWith("f|")) {
            val q = favs(c).mapNotNull { x -> l.find { it.id == x } }
            return Pair(q.map { item(c, it, "f|" + it.id) }, q.indexOfFirst { it.id == id.substring(2) }.coerceAtLeast(0))
        }
        if (id.startsWith("p|")) {
            val parts = id.split('|')
            val pl = playlists(c).find { it.id == parts.getOrNull(1) } ?: return null
            val q = pl.ids.mapNotNull { x -> l.find { it.id == x } }
            return Pair(q.map { item(c, it, "p|" + pl.id + "|" + it.id) }, q.indexOfFirst { it.id == parts.last() }.coerceAtLeast(0))
        }
        val t = l.find { it.id == id } ?: return null
        val mine = l.filter { it.artist == t.artist }
        val q = if (t.album.isEmpty()) sorted(l)
        else if (mine.map { it.album }.distinct().size <= 2) mine.sortedWith(compareBy({ it.album }, { it.no }))
        else mine.filter { it.album == t.album }.sortedBy { it.no }
        return Pair(q.map { item(c, it) }, q.indexOfFirst { it.id == id }.coerceAtLeast(0))
    }
}
