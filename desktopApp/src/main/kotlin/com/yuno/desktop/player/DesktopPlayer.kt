package com.yuno.desktop.player

import com.yuno.desktop.youtube.DesktopVideoDetails
import java.io.IOException

object DesktopPlayer {
    fun play(details: DesktopVideoDetails) {
        val video = details.videoUrl ?: throw IOException("YouTube did not provide a playable video stream.")
        val audio = details.audioUrl
        val command = mutableListOf<String>()
        command += if (System.getProperty("os.name").startsWith("Windows", true)) "mpv.exe" else "mpv"
        command += "--force-window=yes"
        command += "--title=Yuno - ${details.video.title.take(100)}"
        command += "--keep-open=no"
        if (audio != null) command += "--audio-file=$audio"
        command += video

        try {
            ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File(if (System.getProperty("os.name").startsWith("Windows", true)) "NUL" else "/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (error: IOException) {
            throw IOException(
                "Could not start mpv. Install mpv and make sure it is available in PATH, then try again.",
                error,
            )
        }
    }
}
