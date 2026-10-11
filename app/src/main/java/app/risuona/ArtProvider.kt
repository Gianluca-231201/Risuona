package app.risuona

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

class ArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = "image/*"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        val a = uri.getQueryParameter("a") ?: ""
        val b = uri.getQueryParameter("b") ?: ""
        var f = Lib.artFile(c, a, b)
        if (!f.exists()) {
            val h = ("$a|$b").hashCode()
            f = File(c.cacheDir, "b$h.png")
            if (!f.exists()) grad(f, h)
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun grad(f: File, h: Int) {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val k = ((h % 1000) + 1000) % 1000
        val hue = 205 + k % 34
        val sat = if (k % 4 == 1) 0.12f else 0.85f
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 256f, 256f,
            Color.HSVToColor(floatArrayOf(hue.toFloat(), sat, 0.95f)),
            Color.HSVToColor(floatArrayOf((hue + 12).toFloat(), sat, 0.45f)),
            Shader.TileMode.CLAMP)
        Canvas(bmp).drawRect(0f, 0f, 256f, 256f, p)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
