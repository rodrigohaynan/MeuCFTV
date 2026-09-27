package com.rodrigohaynan.meucftv

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alexvas.rtsp.widget.RtspSurfaceView

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
        var view by remember { mutableStateOf<RtspSurfaceView?>(null) }

        val uri = remember(
            config.host,
            config.rtspPort,
            config.rtspPath
        ) {
            buildRtspUri(config)
        }

        fun startStream(target: RtspSurfaceView) {
            runCatching { target.stop() }

            target.init(
                uri = uri,
                username = config.rtspUser.ifBlank { null },
                password = config.password.ifBlank { null },
                userAgent = "MeuCFTV/0.1.2",
                socketTimeout = 7_000
            )
            target.debug = true
            target.start(
                requestVideo = true,
                requestAudio = false,
                requestApplication = false
            )
        }

        DisposableEffect(Unit) {
            onDispose {
                runCatching { view?.stop() }
            }
        }

        Box(modifier = modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RtspSurfaceView(context).also { rtspView ->
                        view = rtspView
                        startStream(rtspView)
                    }
                }
            )

            FilledTonalButton(
                onClick = {
                    view?.let { startStream(it) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp)
            ) {
                Text("▶ Reconectar vídeo")
            }
        }
    }
}

private fun buildRtspUri(config: CameraConfig): Uri {
    val path = if (config.rtspPath.startsWith("/")) {
        config.rtspPath
    } else {
        "/${config.rtspPath}"
    }

    return Uri.parse(
        "rtsp://${config.host}:${config.rtspPort}$path"
    )
}
