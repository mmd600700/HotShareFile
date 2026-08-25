package com.hotsharefile.hotsharefile
data class ProgressUpdate(
    val type: Char,
    val fileName: String,
    val fileIndex: String,
    val percent: Int
)