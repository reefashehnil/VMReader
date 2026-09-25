package com.asif.vmreader

/**
 * Cuts long audio into parts of at most 14 s, at the quietest point
 * (same method as training, so the model sees the same kind of input).
 */
object Splitter {
    fun split(a: FloatArray, sr: Int = 16000, maxS: Int = 14, minS: Int = 6): List<FloatArray> {
        if (a.size <= maxS * sr) return listOf(a)
        val fr = (0.03 * sr).toInt()                       // 30 ms window
        val c = DoubleArray(a.size + 1)                    // running sum of energy
        for (i in a.indices) c[i + 1] = c[i] + a[i].toDouble() * a[i]
        val nE = a.size + 1 - fr
        val parts = ArrayList<FloatArray>()
        var s = 0
        while (a.size - s > maxS * sr) {
            val lo = s + minS * sr
            val hi = minOf(s + maxS * sr, nE)
            var best = lo
            var bestE = Double.MAX_VALUE
            for (i in lo until hi) {                        // find the quietest point
                val e = (c[i + fr] - c[i]) / fr
                if (e < bestE) { bestE = e; best = i }
            }
            parts.add(a.copyOfRange(s, best))
            s = best
        }
        if (a.size - s > sr / 2) parts.add(a.copyOfRange(s, a.size))
        return parts
    }
}
