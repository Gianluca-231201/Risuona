package app.risuona

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
object Viz : TeeAudioProcessor.AudioBufferSink {
    const val N = 32

    @Volatile
    var bands = FloatArray(N)

    @Volatile
    var stamp = 0L
    private var sr = 44100
    private var ch = 2
    private var enc = C.ENCODING_PCM_16BIT
    private val re = FloatArray(1024)
    private val im = FloatArray(1024)
    private var last = 0L

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sr = sampleRateHz
        ch = channelCount
        enc = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val now = SystemClock.elapsedRealtime()
        if (now - last < 45 || enc != C.ENCODING_PCM_16BIT || ch < 1 || sr < 8000) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = b.remaining() / (2 * ch)
        if (frames < 256) return
        last = now
        val n = if (frames >= 1024) 1024 else if (frames >= 512) 512 else 256
        val p0 = b.position()
        for (i in 0 until n) {
            var s = 0f
            for (c in 0 until ch) s += b.getShort(p0 + (i * ch + c) * 2)
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat()
            re[i] = s / ch / 32768f * w
            im[i] = 0f
        }
        fft(n)
        val out = FloatArray(N)
        val half = n / 2
        val fMin = 50.0
        val fMax = min(16000.0, sr / 2.0)
        for (k in 0 until N) {
            val f0 = fMin * (fMax / fMin).pow(k.toDouble() / N)
            val f1 = fMin * (fMax / fMin).pow((k + 1.0) / N)
            val i0 = (f0 * n / sr).toInt().coerceIn(1, half - 1)
            val i1 = (f1 * n / sr).toInt().coerceIn(i0 + 1, half)
            var m = 0f
            for (i in i0 until i1) {
                val v = sqrt(re[i] * re[i] + im[i] * im[i])
                if (v > m) m = v
            }
            val db = 20f * log10(m / (n / 4f) + 1e-6f)
            out[k] = ((db + 62f) / 55f).coerceIn(0f, 1f)
        }
        bands = out
        stamp = now
    }

    private fun fft(n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val c = a + len / 2
                    val tr = re[c] * cr - im[c] * ci
                    val ti = re[c] * ci + im[c] * cr
                    re[c] = re[a] - tr
                    im[c] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
