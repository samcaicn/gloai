package com.wangzan101.mika.wechat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wangzan101.mika.core.cap.wechat.WechatDownloadedImage
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Keep inputs within read_file's existing 5 MiB image budget without allocating a full-size remote bitmap. */
internal fun prepareWechatImage(image: WechatDownloadedImage): PreparedWechatImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height > 64L * 1024 * 1024)
        throw IOException("Invalid image dimensions")
    var sample = 1
    // Round up: an odd dimension such as 8193 must not decode to 4097 at sample=2.
    while ((width + sample - 1) / sample > 4096 || (height + sample - 1) / sample > 4096 ||
        ((width + sample - 1) / sample).toLong() * ((height + sample - 1) / sample) > 8L * 1024 * 1024) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    var bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
        ?: throw IOException("Cannot decode image")
    try {
        if (sample == 1 && image.bytes.size <= MODEL_IMAGE_BYTES && image.extension in setOf("png", "jpg", "jpeg", "webp"))
            return PreparedWechatImage(image.bytes, image.extension)
        val png = bitmap.hasAlpha()
        while (true) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, output))
                throw IOException("Cannot prepare image")
            if (output.size() <= MODEL_IMAGE_BYTES)
                return PreparedWechatImage(output.toByteArray(), if (png) "png" else "jpg")
            val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width / 2).coerceAtLeast(1),
                (bitmap.height / 2).coerceAtLeast(1), true)
            if (smaller === bitmap) throw IOException("Cannot fit image into task input")
            bitmap.recycle()
            bitmap = smaller
        }
    } finally { bitmap.recycle() }
}

private const val MODEL_IMAGE_BYTES = 5 * 1024 * 1024
