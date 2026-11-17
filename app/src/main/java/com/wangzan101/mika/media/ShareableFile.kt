package com.wangzan101.mika.media

import android.net.Uri

/** 一个可以交给别的 App 读取的文件:content URI 与 MIME。 */
class ShareableFile(val uri: Uri, val mime: String)
