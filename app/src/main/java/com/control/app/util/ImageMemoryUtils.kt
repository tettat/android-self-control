package com.control.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageMemoryUtils {

    data class EncodedImage(
        val base64: String,
        val mimeType: String
    )

    fun decodeBase64ToBitmap(
        base64: String,
        maxLongEdge: Int? = null
    ): Bitmap? {
        val bytes = decodeBase64(base64) ?: return null
        return if (maxLongEdge != null && maxLongEdge > 0) {
            decodeBitmap(bytes, maxLongEdge = maxLongEdge)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    fun decodeBase64ToMutableBitmap(base64: String): Bitmap? {
        val bytes = decodeBase64(base64) ?: return null
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null

        if (decoded.isMutable) return decoded

        return decoded.copy(Bitmap.Config.ARGB_8888, true)?.also { mutableCopy ->
            if (mutableCopy !== decoded) {
                decoded.recycle()
            }
        } ?: decoded
    }

    fun decodeBitmapAtScale(bytes: ByteArray, scale: Float): Bitmap? {
        val safeScale = scale.coerceIn(0.01f, 1f)
        return decodeBitmap(
            bytes = bytes,
            requestedSize = { width, height ->
                TargetSize(
                    width = (width * safeScale).roundToInt().coerceAtLeast(1),
                    height = (height * safeScale).roundToInt().coerceAtLeast(1)
                )
            }
        )
    }

    fun encodeBitmapToBase64(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int
    ): String = ByteArrayOutputStream().use { outputStream ->
        bitmap.compress(format, quality, outputStream)
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    fun transcodeBase64Image(
        base64: String,
        maxLongEdge: Int,
        quality: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        mimeType: String = "image/jpeg"
    ): EncodedImage? {
        val bitmap = decodeBase64ToBitmap(base64, maxLongEdge = maxLongEdge) ?: return null
        return try {
            EncodedImage(
                base64 = encodeBitmapToBase64(bitmap, format, quality),
                mimeType = mimeType
            )
        } finally {
            bitmap.recycle()
        }
    }

    private data class TargetSize(
        val width: Int,
        val height: Int
    )

    private fun decodeBitmap(
        bytes: ByteArray,
        maxLongEdge: Int? = null,
        requestedSize: ((width: Int, height: Int) -> TargetSize)? = null
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        val target = requestedSize?.invoke(sourceWidth, sourceHeight)
            ?: buildTargetSize(sourceWidth, sourceHeight, maxLongEdge)

        if (target == null) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        val requestedWidth = target.width.coerceIn(1, sourceWidth)
        val requestedHeight = target.height.coerceIn(1, sourceHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight
            )
        }
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        if (sampled.width == requestedWidth && sampled.height == requestedHeight) {
            return sampled
        }

        val scaled = Bitmap.createScaledBitmap(sampled, requestedWidth, requestedHeight, true)
        if (scaled !== sampled) {
            sampled.recycle()
        }
        return scaled
    }

    private fun buildTargetSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxLongEdge: Int?
    ): TargetSize? {
        if (maxLongEdge == null || maxLongEdge <= 0) return null
        val sourceLongEdge = max(sourceWidth, sourceHeight)
        if (sourceLongEdge <= maxLongEdge) {
            return TargetSize(sourceWidth, sourceHeight)
        }

        val scale = maxLongEdge.toFloat() / sourceLongEdge.toFloat()
        return TargetSize(
            width = (sourceWidth * scale).roundToInt().coerceAtLeast(1),
            height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        )
    }

    private fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int
    ): Int {
        var inSampleSize = 1
        if (sourceHeight <= requestedHeight && sourceWidth <= requestedWidth) {
            return inSampleSize
        }

        var halfHeight = sourceHeight / 2
        var halfWidth = sourceWidth / 2
        while (
            halfHeight / inSampleSize >= requestedHeight &&
            halfWidth / inSampleSize >= requestedWidth
        ) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun decodeBase64(base64: String): ByteArray? {
        return runCatching { Base64.decode(base64, Base64.NO_WRAP) }
            .recoverCatching { Base64.decode(base64, Base64.DEFAULT) }
            .getOrNull()
    }
}
