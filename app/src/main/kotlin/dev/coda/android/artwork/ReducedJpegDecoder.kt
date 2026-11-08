package dev.coda.android.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Looper
import dev.coda.core.accent.ReducedDecodePlan
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ReducedArtwork(val width: Int, val height: Int, val rgba: ByteArray)

/** Platform decode only; the CG resampling and selection stay in :core. */
class ReducedJpegDecoder {
    suspend fun decode(encoded: ByteArray): ReducedArtwork = withContext(Dispatchers.Default) {
        // Keep this assertion at the actual decode boundary, including the bounds decode.
        check(Looper.myLooper() != Looper.getMainLooper()) { "Artwork decode requires a worker thread" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid artwork image" }
        // Non-JPEG sources above 1024 px are sampled down (PO decision 2026-10-25:
        // deliberate divergence from macOS for those covers only); JPEG is unchanged.
        val plan = ReducedDecodePlan.forSourceCapped(bounds.outWidth, bounds.outHeight, bounds.outMimeType.orEmpty())
        coroutineContext.ensureActive()
        // Match CGImageSourceCreateImageAtIndex: both dimensions and pixels ignore
        // EXIF orientation. ImageDecoder rotates pixels but BitmapFactory does not.
        val options = BitmapFactory.Options().apply {
            inSampleSize = plan.factor
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)) {
            "Invalid artwork image"
        }
        try {
            check(bitmap.config == Bitmap.Config.ARGB_8888)
            check(bitmap.width >= plan.width && bitmap.height >= plan.height)
            coroutineContext.ensureActive()
            // Raw ARGB_8888 storage on Android is premultiplied RGBA bytes. getPixels()
            // would un-premultiply and lose the integer values needed by the CG model.
            val storage = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(storage)
            val raw = storage.array()
            val rgba = ByteArray(plan.width * plan.height * 4)
            // libjpeg rounds odd reduced dimensions up; ImageIO floors them. Keep
            // the top-left pixels, dropping only the extra final row/column.
            for (y in 0 until plan.height) {
                raw.copyInto(rgba, y * plan.width * 4, y * bitmap.rowBytes, y * bitmap.rowBytes + plan.width * 4)
            }
            ReducedArtwork(plan.width, plan.height, rgba)
        } finally {
            bitmap.recycle()
        }
    }
}
