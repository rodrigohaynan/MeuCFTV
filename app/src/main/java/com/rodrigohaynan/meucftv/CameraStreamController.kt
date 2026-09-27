package com.rodrigohaynan.meucftv

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.PixelCopy
import com.alexvas.rtsp.widget.RtspSurfaceView
import com.alexvas.utils.VideoCodecUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class CameraStreamController {

    @Volatile
    private var surfaceView: RtspSurfaceView? = null

    @Volatile
    private var frameWidth: Int = 0

    @Volatile
    private var frameHeight: Int = 0

    private val recordLock = Any()

    @Volatile
    private var recordingRequested = false

    private var recordingContext: Context? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var recordingUri: Uri? = null
    private var fallbackRecordingFile: File? = null
    private var recordingDisplayName: String? = null
    private var firstTimestampMs = Long.MIN_VALUE
    private var lastPtsUs = -1L
    private var writtenSamples = 0
    private var muxerReady = false
    private var waitingForKeyFrame = true
    private var statusCallback: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(view: RtspSurfaceView) {
        surfaceView = view
    }

    fun detach(view: RtspSurfaceView) {
        if (surfaceView === view) {
            surfaceView = null
        }
    }

    fun updateFrameSize(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
    }

    fun isRecording(): Boolean = recordingRequested

    fun startRecording(
        context: Context,
        config: CameraConfig,
        onStatus: (String) -> Unit
    ) {
        val appContext = context.applicationContext

        synchronized(recordLock) {
            if (recordingRequested) {
                onMain { onStatus("Gravação já está em andamento") }
                return
            }

            val output = runCatching {
                createVideoOutput(appContext)
            }.getOrElse { error ->
                onMain {
                    onStatus(
                        "Falha ao criar arquivo: " +
                            (error.message ?: "erro desconhecido")
                    )
                }
                return
            }

            recordingContext = appContext
            recordingRequested = true
            recordingUri = output.uri
            fallbackRecordingFile = output.file
            parcelFileDescriptor = output.pfd
            recordingDisplayName = output.displayName
            firstTimestampMs = Long.MIN_VALUE
            lastPtsUs = -1L
            writtenSamples = 0
            muxerReady = false
            waitingForKeyFrame = true
            statusCallback = onStatus

            onMain {
                onStatus(
                    "REC preparado • arquivo criado em Movies/MeuCFTV/" +
                        output.displayName
                )
            }
        }

        Thread {
            val videoConfig = runCatching {
                RtspSdpProbe.probe(config)
            }.getOrElse { error ->
                failRecording(
                    "Falha ao preparar gravação: " +
                        (error.message ?: error.javaClass.simpleName)
                )
                return@Thread
            }

            synchronized(recordLock) {
                if (!recordingRequested) return@Thread

                val width = frameWidth.takeIf { it > 0 } ?: 1920
                val height = frameHeight.takeIf { it > 0 } ?: 1080
                val pfd = parcelFileDescriptor ?: return@Thread

                val localMuxer = runCatching {
                    MediaMuxer(
                        pfd.fileDescriptor,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    )
                }.getOrElse { error ->
                    failRecordingLocked(
                        "Falha ao abrir MP4: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                    return@Thread
                }

                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    width,
                    height
                ).apply {
                    setByteBuffer(
                        "csd-0",
                        ByteBuffer.wrap(videoConfig.sps)
                    )
                    setByteBuffer(
                        "csd-1",
                        ByteBuffer.wrap(videoConfig.pps)
                    )
                }

                runCatching {
                    videoTrackIndex = localMuxer.addTrack(format)
                    localMuxer.start()
                    muxer = localMuxer
                    muxerReady = true
                }.onFailure { error ->
                    runCatching { localMuxer.release() }
                    failRecordingLocked(
                        "Falha ao iniciar MP4: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                    return@Thread
                }

                onMain {
                    statusCallback?.invoke(
                        "REC ativo • aguardando próximo quadro-chave H.264"
                    )
                }
            }
        }.apply {
            name = "MeuCFTV-SDP-Probe"
            start()
        }
    }

    fun stopRecording(onStatus: (String) -> Unit) {
        val message: String

        synchronized(recordLock) {
            if (!recordingRequested) {
                onMain { onStatus("Nenhuma gravação em andamento") }
                return
            }

            recordingRequested = false
            val sampleCount = writtenSamples
            val currentMuxer = muxer

            if (currentMuxer != null && muxerReady) {
                runCatching { currentMuxer.stop() }
                runCatching { currentMuxer.release() }
            }

            runCatching { parcelFileDescriptor?.close() }

            val success = sampleCount > 0

            finalizeMediaStore(success)

            message = if (success) {
                "Vídeo salvo: Armazenamento interno > Movies > MeuCFTV > " +
                    (recordingDisplayName ?: "MeuCFTV.mp4")
            } else {
                "Nenhum quadro H.264 foi gravado; arquivo incompleto removido"
            }

            resetRecordingState()
        }

        onMain { onStatus(message) }
    }

    fun captureSnapshot(context: Context, onStatus: (String) -> Unit) {
        val view = surfaceView

        if (
            view == null ||
            view.width <= 0 ||
            view.height <= 0 ||
            !view.holder.surface.isValid
        ) {
            onMain {
                onStatus(
                    "Não foi possível capturar: vídeo ainda não está pronto"
                )
            }
            return
        }

        val width = if (frameWidth > 0) frameWidth else view.width
        val height = if (frameHeight > 0) frameHeight else view.height
        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        val thread = HandlerThread("MeuCFTV-PixelCopy").apply { start() }
        val handler = Handler(thread.looper)

        PixelCopy.request(
            view.holder.surface,
            bitmap,
            { result ->
                thread.quitSafely()

                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    onMain {
                        onStatus("Falha ao capturar imagem • código $result")
                    }
                    return@request
                }

                Thread {
                    val saved = runCatching {
                        saveSnapshot(
                            context.applicationContext,
                            bitmap
                        )
                    }.getOrNull()

                    bitmap.recycle()

                    onMain {
                        if (saved != null) {
                            onStatus("Foto salva em Imagens/MeuCFTV")
                        } else {
                            onStatus("Falha ao salvar a foto")
                        }
                    }
                }.start()
            },
            handler
        )
    }

    fun onVideoNalUnit(
        data: ByteArray,
        offset: Int,
        length: Int,
        timestampMs: Long
    ) {
        if (!recordingRequested || length <= 0) return

        synchronized(recordLock) {
            if (!recordingRequested || !muxerReady) return

            val localMuxer = muxer ?: return
            val trackIndex = videoTrackIndex
            if (trackIndex < 0) return

            val keyFrame = VideoCodecUtils.isAnyKeyFrame(
                data,
                offset,
                minOf(length, 1024),
                false
            )

            if (waitingForKeyFrame) {
                if (!keyFrame) return

                waitingForKeyFrame = false
                firstTimestampMs = timestampMs

                onMain {
                    statusCallback?.invoke(
                        "● REC gravando • " +
                            (recordingDisplayName ?: "MeuCFTV.mp4")
                    )
                }
            }

            val sample = data.copyOfRange(
                offset,
                offset + length
            )

            val calculatedPtsUs =
                max(0L, (timestampMs - firstTimestampMs) * 1_000L)

            val ptsUs =
                if (calculatedPtsUs <= lastPtsUs) {
                    lastPtsUs + 1L
                } else {
                    calculatedPtsUs
                }

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

            runCatching {
                localMuxer.writeSampleData(
                    trackIndex,
                    ByteBuffer.wrap(sample),
                    info
                )
                writtenSamples++
            }.onFailure { error ->
                failRecordingLocked(
                    "Erro ao gravar quadro: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    private fun failRecording(message: String) {
        synchronized(recordLock) {
            failRecordingLocked(message)
        }
    }

    private fun failRecordingLocked(message: String) {
        if (!recordingRequested) return

        recordingRequested = false

        runCatching {
            if (muxerReady) {
                muxer?.stop()
            }
        }
        runCatching { muxer?.release() }
        runCatching { parcelFileDescriptor?.close() }

        val callback = statusCallback
        finalizeMediaStore(false)
        resetRecordingState()

        onMain {
            callback?.invoke(message)
        }
    }

    private fun finalizeMediaStore(success: Boolean) {
        val context = recordingContext ?: return
        val uri = recordingUri

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (uri != null) {
                if (success) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }

                    runCatching {
                        context.contentResolver.update(
                            uri,
                            values,
                            null,
                            null
                        )
                    }
                } else {
                    runCatching {
                        context.contentResolver.delete(
                            uri,
                            null,
                            null
                        )
                    }
                }
            }
        } else if (!success) {
            runCatching {
                fallbackRecordingFile?.delete()
            }
        }
    }

    private fun resetRecordingState() {
        muxer = null
        videoTrackIndex = -1
        parcelFileDescriptor = null
        recordingUri = null
        fallbackRecordingFile = null
        recordingDisplayName = null
        firstTimestampMs = Long.MIN_VALUE
        lastPtsUs = -1L
        writtenSamples = 0
        muxerReady = false
        waitingForKeyFrame = true
        recordingContext = null
        statusCallback = null
    }

    private data class OutputTarget(
        val uri: Uri?,
        val file: File?,
        val pfd: ParcelFileDescriptor,
        val displayName: String
    )

    private fun createVideoOutput(context: Context): OutputTarget {
        val fileName = "MeuCFTV_${timestampForFile()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    fileName
                )
                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
                )
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/MeuCFTV"
                )
                put(
                    MediaStore.Video.Media.IS_PENDING,
                    1
                )
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Não foi possível criar o arquivo de vídeo")

            val pfd = context.contentResolver.openFileDescriptor(
                uri,
                "rw"
            ) ?: error("Não foi possível abrir o arquivo de vídeo")

            return OutputTarget(
                uri,
                null,
                pfd,
                fileName
            )
        }

        val directory = File(
            context.getExternalFilesDir(
                Environment.DIRECTORY_MOVIES
            ),
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
            Uri.fromFile(file),
            file,
            pfd,
            fileName
        )
    }

    private fun saveSnapshot(
        context: Context,
        bitmap: Bitmap
    ): Uri? {
        val fileName = "MeuCFTV_${timestampForFile()}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    fileName
                )
                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
                )
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/MeuCFTV"
                )
                put(
                    MediaStore.Images.Media.IS_PENDING,
                    1
                )
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            val success =
                context.contentResolver
                    .openOutputStream(uri)
                    ?.use { stream ->
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            95,
                            stream
                        )
                    }
                    ?: false

            if (!success) {
                context.contentResolver.delete(
                    uri,
                    null,
                    null
                )
                return null
            }

            val ready = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }

            context.contentResolver.update(
                uri,
                ready,
                null,
                null
            )

            return uri
        }

        val directory = File(
            context.getExternalFilesDir(
                Environment.DIRECTORY_PICTURES
            ),
            "MeuCFTV"
        ).apply { mkdirs() }

        val file = File(directory, fileName)

        FileOutputStream(file).use { stream ->
            if (
                !bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    stream
                )
            ) {
                return null
            }
        }

        return Uri.fromFile(file)
    }

    private fun timestampForFile(): String =
        SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
