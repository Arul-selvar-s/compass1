package com.compass.diary.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object PhotoCompressor {

    private const val MAX_DIMENSION = 1280
    private const val QUALITY = 80

    fun compress(sourceFile: File, outputFile: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_DIMENSION * 2 || bounds.outHeight / sampleSize > MAX_DIMENSION * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return false

            val scale = MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
            val finalBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap

            FileOutputStream(outputFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            if (finalBitmap !== bitmap) bitmap.recycle()
            finalBitmap.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }
}
