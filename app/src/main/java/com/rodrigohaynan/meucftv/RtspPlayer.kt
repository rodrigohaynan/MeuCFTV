package com.rodrigohaynan.meucftv

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

@Composable
fun RtspPlayer(
    config: CameraConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val player = remember(
        config.host,
        config.rtspPort,
        config.rtspPath,
        config.rtspUser,
        config.password
    ) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(buildRtspUri(config))
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(mediaItem)

            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = true
                this.player = player
            }
        },
        update = { it.player = player }
    )
}

private fun buildRtspUri(config: CameraConfig): Uri {
    val user = Uri.encode(config.rtspUser)
    val password = Uri.encode(config.password)
    val credentials = if (user.isNotBlank()) "$user:$password@" else ""
    val path = if (config.rtspPath.startsWith("/")) {
        config.rtspPath
    } else {
        "/${config.rtspPath}"
    }

    return Uri.parse(
        "rtsp://$credentials${config.host}:${config.rtspPort}$path"
    )
}
