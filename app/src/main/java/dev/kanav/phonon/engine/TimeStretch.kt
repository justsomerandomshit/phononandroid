package dev.kanav.phonon.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Streaming speed and pitch change for mono speech, after Bill Cox's Sonic: the speed changes by
 * dropping or repeating whole pitch periods (found by average magnitude difference) with a
 * cross-fade, which keeps the voice's pitch; the pitch then changes by resampling, which also
 * changes the speed, so the stretch makes up for it first.
 *
 * Phonon has no rate or pitch control the C interface exposes, so this is how the system's
 * speech-rate and pitch sliders take effect.
 */
class TimeStretch(sampleRate: Int, speed: Float, pitch: Float) {
    private val pitch = pitch.coerceIn(0.25f, 4f)
    /** The stretch the resampler's speed change leaves to do. */
    private val stretch = (speed.coerceIn(0.1f, 10f) / this.pitch)

    private val minPeriod = sampleRate / MAX_PITCH_HZ
    private val maxPeriod = sampleRate / MIN_PITCH_HZ
    private val maxRequired = 2 * maxPeriod

    private var input = FloatArray(4 * maxRequired)
    private var inputLen = 0
    private var remainingToCopy = 0
    private val out = Growable()

    // Resampler state: the next output's position, where -1 is [prevSample].
    private var resamplePos = 0.0
    private var prevSample = 0f

    val isIdentity = abs(stretch - 1f) < 0.01f && abs(this.pitch - 1f) < 0.01f

    /** Take [pcm] and return what is ready of the output, possibly nothing. */
    fun process(pcm: FloatArray): FloatArray {
        if (isIdentity) return pcm
        append(pcm)
        stretchAvailable()
        return resample(out.take())
    }

    /** The rest of the output, once the input has ended. */
    fun flush(): FloatArray {
        if (isIdentity) return FloatArray(0)
        stretchAvailable()
        // Less than two pitch periods are left: pass them on unchanged.
        out.add(input, 0, inputLen)
        inputLen = 0
        return resample(out.take())
    }

    private fun append(pcm: FloatArray) {
        if (inputLen + pcm.size > input.size) input = input.copyOf(max(input.size * 2, inputLen + pcm.size))
        pcm.copyInto(input, inputLen)
        inputLen += pcm.size
    }

    private fun stretchAvailable() {
        if (abs(stretch - 1f) < 0.01f) {
            out.add(input, 0, inputLen)
            inputLen = 0
            return
        }
        if (inputLen < maxRequired) return
        var pos = 0
        do {
            if (remainingToCopy > 0) {
                val n = min(maxPeriod, remainingToCopy)
                out.add(input, pos, n)
                remainingToCopy -= n
                pos += n
            } else {
                val period = findPeriod(pos)
                pos += if (stretch > 1f) skipPeriod(pos, period) else insertPeriod(pos, period)
            }
        } while (pos + maxRequired <= inputLen)
        input.copyInto(input, 0, pos, inputLen)
        inputLen -= pos
    }

    /** The pitch period at [pos]: the lag with the lowest mean difference per sample. */
    private fun findPeriod(pos: Int): Int {
        var best = minPeriod
        var bestDiff = Float.MAX_VALUE
        // A coarse search every other sample, then a fine one around the winner.
        var period = minPeriod
        while (period <= maxPeriod) {
            val d = amdf(pos, period, 2)
            if (d < bestDiff) {
                bestDiff = d
                best = period
            }
            period += 2
        }
        val lo = max(minPeriod, best - 2)
        val hi = min(maxPeriod, best + 2)
        bestDiff = Float.MAX_VALUE
        for (p in lo..hi) {
            val d = amdf(pos, p, 1)
            if (d < bestDiff) {
                bestDiff = d
                best = p
            }
        }
        return best
    }

    private fun amdf(pos: Int, period: Int, step: Int): Float {
        var sum = 0f
        var i = 0
        while (i < period) {
            sum += abs(input[pos + i] - input[pos + i + period])
            i += step
        }
        return sum * step / period
    }

    /** Faster: cross-fade one period into the next, dropping a period. Returns the input used. */
    private fun skipPeriod(pos: Int, period: Int): Int {
        val newSamples: Int
        if (stretch >= 2f) {
            newSamples = (period / (stretch - 1f)).toInt()
        } else {
            newSamples = period
            remainingToCopy = (period * (2f - stretch) / (stretch - 1f)).toInt()
        }
        overlapAdd(newSamples, pos, pos + period)
        return period + newSamples
    }

    /** Slower: play a period, then cross-fade it with itself, repeating it. Returns the input used. */
    private fun insertPeriod(pos: Int, period: Int): Int {
        val newSamples: Int
        if (stretch < 0.5f) {
            newSamples = max(1, (period * stretch / (1f - stretch)).toInt())
        } else {
            newSamples = period
            remainingToCopy = (period * (2f * stretch - 1f) / (1f - stretch)).toInt()
        }
        out.add(input, pos, period)
        overlapAdd(newSamples, pos + period, pos)
        return newSamples
    }

    /** [n] samples fading out from [down] while fading in from [up]. */
    private fun overlapAdd(n: Int, down: Int, up: Int) {
        val buf = out.reserve(n)
        val at = out.size
        for (i in 0 until n) {
            buf[at + i] = (input[down + i] * (n - i) + input[up + i] * i) / n
        }
        out.size += n
    }

    /** Linear-interpolation resampling by [pitch], streaming across calls. */
    private fun resample(pcm: FloatArray): FloatArray {
        if (abs(pitch - 1f) < 0.01f || pcm.isEmpty()) return pcm
        val res = Growable()
        // Indexed from -1, the previous call's last sample, to pcm.size - 1.
        var p = resamplePos
        while (p < pcm.size - 1) {
            val i = floor(p).toInt()
            val frac = (p - i).toFloat()
            val a = if (i < 0) prevSample else pcm[i]
            res.add1(a + (pcm[i + 1] - a) * frac)
            p += pitch
        }
        // The last sample becomes index -1 of the next call.
        resamplePos = p - pcm.size
        prevSample = pcm[pcm.size - 1]
        return res.take()
    }

    private class Growable {
        var data = FloatArray(4096)
        var size = 0

        fun reserve(n: Int): FloatArray {
            if (size + n > data.size) data = data.copyOf(max(data.size * 2, size + n))
            return data
        }

        fun add(src: FloatArray, from: Int, n: Int) {
            if (n <= 0) return
            reserve(n)
            src.copyInto(data, size, from, from + n)
            size += n
        }

        fun add1(v: Float) {
            reserve(1)
            data[size++] = v
        }

        fun take(): FloatArray = data.copyOf(size).also { size = 0 }
    }

    private companion object {
        const val MIN_PITCH_HZ = 65
        const val MAX_PITCH_HZ = 400
    }
}
