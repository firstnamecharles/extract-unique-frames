package com.extractuniqueframes

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Perceptual hash (pHash) using 2D DCT on a 32×32 grayscale thumbnail.
 * The 8×8 top-left DCT coefficients (64 bits) form the hash.
 */
object PHashCalculator {

    private const val DCT_SIZE = 32
    private const val HASH_SIZE = 8

    // Precompute cosine table for the separable DCT
    private val cosTable: Array<DoubleArray> = Array(DCT_SIZE) { u ->
        DoubleArray(DCT_SIZE) { x ->
            cos((2 * x + 1) * u * PI / (2.0 * DCT_SIZE))
        }
    }

    fun compute(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, DCT_SIZE, DCT_SIZE, true)
        val gray = DoubleArray(DCT_SIZE * DCT_SIZE)

        for (y in 0 until DCT_SIZE) {
            for (x in 0 until DCT_SIZE) {
                val pixel = scaled.getPixel(x, y)
                gray[y * DCT_SIZE + x] =
                    0.299 * Color.red(pixel) +
                    0.587 * Color.green(pixel) +
                    0.114 * Color.blue(pixel)
            }
        }
        if (scaled != bitmap) scaled.recycle()

        val dct = dct2D(gray)

        // Top-left 8×8 block
        val block = DoubleArray(HASH_SIZE * HASH_SIZE) { i ->
            val row = i / HASH_SIZE
            val col = i % HASH_SIZE
            dct[row * DCT_SIZE + col]
        }

        val mean = block.average()

        var hash = 0L
        for (i in block.indices) {
            if (block[i] > mean) hash = hash or (1L shl i)
        }
        return hash
    }

    // Separable 2D DCT: apply 1D DCT to rows then columns
    private fun dct2D(input: DoubleArray): DoubleArray {
        val n = DCT_SIZE
        val temp = DoubleArray(n * n)

        // Rows
        for (y in 0 until n) {
            for (u in 0 until n) {
                var sum = 0.0
                for (x in 0 until n) sum += input[y * n + x] * cosTable[u][x]
                val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                temp[y * n + u] = (2.0 / n) * cu * sum
            }
        }

        // Columns
        val result = DoubleArray(n * n)
        for (x in 0 until n) {
            for (v in 0 until n) {
                var sum = 0.0
                for (y in 0 until n) sum += temp[y * n + x] * cosTable[v][y]
                val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                result[v * n + x] = (2.0 / n) * cv * sum
            }
        }
        return result
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Average RGB as a FloatArray(3) computed on a 32×32 thumbnail. */
    fun averageColor(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        var r = 0L; var g = 0L; var b = 0L
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled != bitmap) scaled.recycle()
        for (px in pixels) {
            r += Color.red(px)
            g += Color.green(px)
            b += Color.blue(px)
        }
        val n = pixels.size.toFloat()
        return floatArrayOf(r / n, g / n, b / n)
    }

    fun colorDistance(a: FloatArray, b: FloatArray): Float {
        val dr = a[0] - b[0]
        val dg = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
