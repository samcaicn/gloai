package com.wangzan101.mika.ui

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * 任务报告分享用的临时文件（cache/report-share/，见 res/xml/report_files.xml）。
 * 只读：分享面板里的接收方拿到的是一次性的读授权，不允许改写或删除。
 */
class ReportFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
