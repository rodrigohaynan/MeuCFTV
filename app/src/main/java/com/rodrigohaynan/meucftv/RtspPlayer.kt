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
import com.alexvas.rtsp.widget.RtspDataListener
import com.alexvas.rtsp.widget.RtspStatusListener
import com.alexvas.rtsp.widget.RtspSurfaceView

@Composable
fun RtspPlayer(
    config: CameraConfig,
    controller: CameraStreamController,
    onAudioDetected: () -> Unit,
    onStatus: (String) -> Unit,
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
        var audioReported by remember { mutableStateOf(false) }

        val uri = remember(
            config.host,
            config.rtspPort,
            config.rtspPath
        ) {
            buildRtspUri(config)
        }

        fun startStream(target: RtspSurfaceView) {
            runCatching { target.stop() }

            target.setStatusListener(object : RtspStatusListener {
                override fun onRtspStatusConnecting() {
                    target.post { onStatus("Conectando vídeo e áudio...") }
                }

                override fun onRtspStatusConnected() {
                    target.post { onStatus("RTSP conectado") }
                }

                override fun onRtspStatusFailedUnauthorized() {
                    target.post { onStatus("Falha RTSP: usuário ou senha inválidos") }
                }

                override fun onRtspStatusFailed(message: String?) {
                    target.post {
                        onStatus("Falha RTSP: ${message ?: "erro desconhecido"}")
                    }
                }

                override fun onRtspFirstFrameRendered() {
                    target.post { onStatus("Vídeo ao vivo") }
                }

                override fun onRtspFrameSizeChanged(width: Int, height: Int) {
                    controller.updateFrameSize(width, height)
                }
            })

            target.setDataListener(object : RtspDataListener {
                override fun onRtspDataVideoNalUnitReceived(
                    data: ByteArray,
                    offset: Int,
                    length: Int,
                    timestamp: Long
                ) {
                    controller.onVideoNalUnit(data, offset, length, timestamp)
                }

                override fun onRtspDataAudioSampleReceived(
                    data: ByteArray,
                    offset: Int,
                    length: Int,
                    timestamp: Long
                ) {
                    if (length > 0 && !audioReported) {
                        audioReported = true
                        target.post { onAudioDetected() }
                    }
                }
            })

            target.init(
                uri = uri,
                username = config.rtspUser.ifBlank { null },
                password = config.password.ifBlank { null },
                userAgent = "MeuCFTV/0.1.3",
                socketTimeout = 7_000
            )
            target.debug = true
            controller.attach(target)
            target.start(
                requestVideo = true,
                requestAudio = true,
                requestApplication = false
            )
        }

        DisposableEffect(Unit) {
            onDispose {
                view?.let { current ->
                    controller.detach(current)
                    runCatching { current.stop() }
                }
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
                Text("▶ Reconectar")
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
