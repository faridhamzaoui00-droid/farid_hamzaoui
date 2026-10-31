package com.example.stackbot

import android.media.Image
import kotlin.math.abs

object Detector {
    // يرجع أقصى يسار وأقصى يمين للأجسام المختلفة عن خلفية السطر
    fun span(img: Image, y: Int): Pair<Int, Int>? {
        val p = img.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val w = img.width
        val base = y * rs
        val n = (w + 3) / 4
        val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        for (i in 0 until n) {
            val o = base + i * 4 * ps
            r[i] = buf.get(o).toInt() and 255
            g[i] = buf.get(o + 1).toInt() and 255
            b[i] = buf.get(o + 2).toInt() and 255
        }
        r.sort(); g.sort(); b.sort()
        val mr = r[n / 2]; val mg = g[n / 2]; val mb = b[n / 2]
        var left = -1; var right = -1; var run = 0
        for (x in 0 until w) {
            val o = base + x * ps
            val d = abs((buf.get(o).toInt() and 255) - mr) +
                    abs((buf.get(o + 1).toInt() and 255) - mg) +
                    abs((buf.get(o + 2).toInt() and 255) - mb)
            if (d > Config.COLOR_DIFF) {
                run++
                if (run >= Config.MIN_RUN) {
                    if (left < 0) left = x - run + 1
                    right = x
                }
            } else run = 0
        }
        return if (left >= 0) left to right else null
    }
}
