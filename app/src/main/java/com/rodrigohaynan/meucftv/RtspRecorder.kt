package com.rodrigohaynan.meucftv

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.alexvas.rtsp.RtspClient
import com.alexvas.utils.NetUtils
import com.alexvas.utils.VideoCodecUtils
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class RtspRecorder {

    private val stopFlag = AtomicBoolean(true)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var recording = false

    fun isRecording(): Boolean = recording

    fun start(
        context: Context,
        config: CameraConfig,
        onStatus: (String) -> Unit,
        onStopped: () -> Unit
    ) {
        if (recording) {
            onStatus("Gravação já está em andamento")
            return
        }

        recording = true
        stopFlag.set(false)

        val appContext = context.applicationContext
        val target = try {
            createVideoOutput(appContext)
        } catch (error: Exception) {
            recording = false
            onStatus("Falha ao criar arquivo: ${error.message ?: "erro desconhecido"}")
            onStopped()
            return
        }

        onStatus(
            "REC iniciado • arquivo criado em Movies/MeuCFTV/${target.displayName}"
        )

        worker = Thread {
            var socket: Socket? = null
            var muxer: MediaMuxer? = null
            var trackIndex = -1
            var muxerStarted = false
            var firstTimestampMs = Long.MIN_VALUE
            var lastPtsUs = -1L
            var samplesWritten = 0
            var readyForSamples = false

            fun finalizeFile(success: Boolean) {
                runCatching {
                    if (muxerStarted) {
                        muxer?.stop()
                    }
                }
                runCatching { muxer?.release() }
                runCatching { target.pfd.close() }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (success) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        runCatching {
                            appContext.contentResolver.update(
                                target.uri!!,
                                values,
                                null,
                                null
                            )
                        }
                    } else {
                        runCatching {
                            target.uri?.let {
                                appContext.contentResolver.delete(it, null, null)
                            }
                        }
                    }
                } else if (!success) {
                    runCatching { target.file?.delete() }
                }
            }

            try {
                val uri = buildRtspUri(config)
                val port = config.rtspPort

                socket = NetUtils.createSocketAndConnect(
                    config.host,
                    port,
                    7_000
                )

                val listener = object : RtspClient.RtspClientListener {
                    override fun onRtspConnecting() = Unit

                    override fun onRtspConnected(sdpInfo: RtspClient.SdpInfo) {
                        val video = sdpInfo.videoTrack
                            ?: throw IOException("A câmera não retornou trilha de vídeo")

                        if (video.videoCodec != RtspClient.VIDEO_CODEC_H264) {
                            throw IOException("Gravação suporta H.264 nesta versão")
                        }

                        val sps = video.sps
                            ?: throw IOException("SPS ausente no SDP")
                        val pps = video.pps
                            ?: throw IOException("PPS ausente no SDP")

                        val size = VideoCodecUtils.getWidthHeightFromArray(
                            sps,
                            0,
                            sps.size,
                            false
                        ) ?: Pair(1920, 1080)

                        muxer = MediaMuxer(
                            target.pfd.fileDescriptor,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        )

                        val format = MediaFormat.createVideoFormat(
                            MediaFormat.MIMETYPE_VIDEO_AVC,
                            size.first,
                            size.second
                        ).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                        }

                        trackIndex = muxer!!.addTrack(format)
                        muxer!!.start()
                        muxerStarted = true
                        readyForSamples = true

                        onStatus(
                            "REC gravando • ${size.first}x${size.second} • " +
                                "Movies/MeuCFTV/${target.displayName}"
                        )
                    }

                    override fun onRtspVideoNalUnitReceived(
                        data: ByteArray,
                        offset: Int,
                        length: Int,
                        timestamp: Long
                    ) {
                        if (!readyForSamples || !recording || length <= 0) return

                        val keyFrame = VideoCodecUtils.isAnyKeyFrame(
                            data,
                            offset,
                            length,
                            false
                        )

                        if (firstTimestampMs == Long.MIN_VALUE) {
                            if (!keyFrame) return
                            firstTimestampMs = timestamp
                        }

                        val sample = data.copyOfRange(offset, offset + length)
                        val calculatedPtsUs =
                            max(0L, (timestamp - firstTimestampMs) * 1_000L)
                        val ptsUs =
                            if (calculatedPtsUs <= lastPtsUs) lastPtsUs + 1L
                            else calculatedPtsUs
                        lastPtsUs = ptsUs

                        val info = MediaCodec.BufferInfo().apply {
                            set(
                                0,
                                sample.size,
                                ptsUs,
                                if (keyFrame) {
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                                } else {
                                    0
                                }
                            )
                        }

                        muxer?.writeSampleData(
                            trackIndex,
                            ByteBuffer.wrap(sample),
                            info
                        )
                        samplesWritten++
                    }

                    override fun onRtspAudioSampleReceived(
                        data: ByteArray,
                        offset: Int,
                        length: Int,
                        timestamp: Long
                    ) = Unit

                    override fun onRtspApplicationDataReceived(
                        data: ByteArray,
                        offset: Int,
                        length: Int,
                        timestamp: Long
                    ) = Unit

                    override fun onRtspDisconnecting() = Unit
                    override fun onRtspDisconnected() = Unit

                    override fun onRtspFailedUnauthorized() {
                        onStatus("Falha REC: usuário ou senha RTSP inválidos")
                        stopFlag.set(true)
                    }

                    override fun onRtspFailed(message: String?) {
                        onStatus(
                            "Falha REC: ${message ?: "erro RTSP desconhecido"}"
                        )
                        stopFlag.set(true)
                    }
                }

                val client = RtspClient.Builder(
                    socket,
                    uri,
                    stopFlag,
                    listener
                )
                    .requestVideo(true)
                    .requestAudio(false)
                    .requestApplication(false)
                    .withDebug(false)
                    .withUserAgent("MeuCFTV/0.1.5")
                    .withCredentials(config.rtspUser, config.password)
                    .build()

                client.execute()

                val success = samplesWritten > 0
                finalizeFile(success)

                if (success) {
                    onStatus(
                        "Vídeo salvo: Armazenamento interno > Movies > " +
                            "MeuCFTV > ${target.displayName}"
                    )
                } else {
                    onStatus(
                        "Gravação sem quadros válidos; arquivo incompleto removido"
                    )
                }
            } catch (error: Exception) {
                finalizeFile(false)
                onStatus(
                    "Falha ao gravar: ${error.message ?: error.javaClass.simpleName}"
                )
            } finally {
                NetUtils.closeSocket(socket)
                recording = false
                stopFlag.set(true)
                onStopped()
            }
        }.apply {
            name = "MeuCFTV-Recorder"
            start()
        }
    }

    fun stop(onStatus: (String) -> Unit) {
        if (!recording) {
            onStatus("Nenhuma gravação em andamento")
            return
        }

        onStatus("Finalizando gravação...")
        stopFlag.set(true)
        runCatching { worker?.interrupt() }
    }

    private data class OutputTarget(
        val uri: android.net.Uri?,
        val file: File?,
        val pfd: ParcelFileDescriptor,
        val displayName: String
    )

    private fun createVideoOutput(context: Context): OutputTarget {
        val fileName = "MeuCFTV_${timestampForFile()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/MeuCFTV"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Não foi possível criar o arquivo de vídeo")

            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Não foi possível abrir o arquivo de vídeo")

            return OutputTarget(uri, null, pfd, fileName)
        }

        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "MeuCFTV"
        ).apply { mkdirs() }

        val file = File(directory, fileName)
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE
        )

        return OutputTarget(
            android.net.Uri.fromFile(file),
            file,
            pfd,
            fileName
        )
    }

    private fun buildRtspUri(config: CameraConfig): String {
        val path = if (config.rtspPath.startsWith("/")) {
            config.rtspPath
        } else {
            "/${config.rtspPath}"
        }

        return "rtsp://${config.host}:${config.rtspPort}$path"
    }

    private fun timestampForFile(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
