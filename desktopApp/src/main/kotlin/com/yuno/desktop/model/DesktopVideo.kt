package com.yuno.desktop.model

data class DesktopVideo(
    val id: String,
    val title: String,
    val channel: String,
    val thumbnail: String?,
    val durationSeconds: Long,
    val views: Long,
)
