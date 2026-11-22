package com.katanaclaude.app

/** Fixed-size ring buffer that keeps the last [cap] bytes of terminal output for redraw on reopen. */
class ByteRing(private val cap: Int) {
    private val buf = ByteArray(cap)
    private var start = 0
    private var size = 0

    @Synchronized
    fun write(src: ByteArray, off0: Int = 0, len0: Int = src.size) {
        var off = off0
        var len = len0
        if (len <= 0) return
        if (len >= cap) {
            off += len - cap
            len = cap
            start = 0
            size = 0
        }
        var end = (start + size) % cap
        var remaining = len
        while (remaining > 0) {
            val chunk = minOf(remaining, cap - end)
            System.arraycopy(src, off, buf, end, chunk)
            off += chunk
            remaining -= chunk
            end = (end + chunk) % cap
        }
        size += len
        if (size > cap) {
            start = (start + (size - cap)) % cap
            size = cap
        }
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(size)
        val first = minOf(size, cap - start)
        System.arraycopy(buf, start, out, 0, first)
        if (size > first) System.arraycopy(buf, 0, out, first, size - first)
        return out
    }

    @Synchronized
    fun clear() {
        start = 0
        size = 0
    }
}
