package com.wangzan101.mika.files.ui

import com.wangzan101.mika.R

/** The library indexes names, so categories remain available for offline and missing files. */
internal enum class LibraryFileType(val label: Int) {
    Image(R.string.file_library_type_image),
    Pdf(R.string.file_library_type_pdf),
    Audio(R.string.file_library_type_audio),
    Video(R.string.file_library_type_video),
    Other(R.string.file_library_type_other);

    companion object {
        fun of(name: String): LibraryFileType {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (name.lastIndexOf('.') <= 0) return Other
            return when (extension) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif", "ico", "tif", "tiff" -> Image
                "pdf" -> Pdf
                "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "aif", "aiff", "amr", "mka" -> Audio
                "mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp", "3g2", "wmv", "mpeg", "mpg", "ogv", "flv" -> Video
                else -> Other
            }
        }
    }
}
