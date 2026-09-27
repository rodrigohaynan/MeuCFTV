package com.rodrigohaynan.meucftv

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun RtspPlayer(
    config: CameraConfig,
    modifier: Modifier = Modifier
) {
    key(
        config.host,
        config.rtspPort,
        config.rtspPath,
        config.rtspUser,
        config.password
    ) {
        val context = LocalContext.current

        val libVlc = remember {
            LibVLC(
                context.applicationContext,
                arrayListOf(
                    "--rtsp-tcp",
                    "--network-caching=700",
                    "--clock-jitter=0",
                    "--clock-synchro=0"
                )
            )
        }

        val mediaPlayer = remember(libVlc) {
            MediaPlayer(libVlc)
        }

        DisposableEffect(mediaPlayer, libVlc) {
            onDispose {
                runCatching { mediaPlayer.stop() }
                runCatching { mediaPlayer.detachViews() }
                mediaPlayer.release()
                libVlc.release()
            }
        }

        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                VLCVideoLayout(viewContext).also { videoLayout ->
                    mediaPlayer.attachViews(
                        videoLayout,
                        null,
                        false,
                        false
                    )

                    val media = Media(libVlc, buildRtspUri(config)).apply {
                        setHWDecoderEnabled(true, false)
                        addOption(":rtsp-tcp")
                        addOption(":network-caching=700")
                    }

                    mediaPlayer.media = media
                    media.release()
                    mediaPlayer.play()
                }
            }
        )
    }
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
