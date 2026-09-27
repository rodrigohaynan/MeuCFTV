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

    private data class BufferedVideoFrame(
        val data: ByteArray,
        val timestampMs: Long,
        val keyFrame: Boolean
    )

    private val gopBuffer = ArrayDeque<BufferedVideoFrame>()
    private var gopBufferBytes = 0
    private var videoFramesSeen = 0L
    private var keyFramesSeen = 0L
    private var lastRecordedTimestampMs = Long.MIN_VALUE

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

                    if (gopBuffer.firstOrNull()?.keyFrame == true) {
                        firstTimestampMs = gopBuffer.first().timestampMs
                        for (frame in gopBuffer) {
                            writeVideoFrameLocked(
                                frame.data,
                                frame.timestampMs,
                                frame.keyFrame
                            )
                        }
                    }
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
                        if (writtenSamples > 0) {
                            "● REC gravando • quadro-chave em cache • " +
                                (recordingDisplayName ?: "MeuCFTV.mp4")
                        } else {
                            "REC ativo • aguardando quadro-chave H.264 • " +
                                "frames vistos: $videoFramesSeen"
                        }
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
        if (length <= 0) return

        val copied = data.copyOfRange(
            offset,
            offset + length
        )

        val keyFrame = isH264KeyFrame(
            copied
        )

        synchronized(recordLock) {
            videoFramesSeen++

            if (keyFrame) {
                keyFramesSeen++
                gopBuffer.clear()
                gopBufferBytes = 0
            }

            if (keyFrame || gopBuffer.isNotEmpty()) {
                gopBuffer.addLast(
                    BufferedVideoFrame(
                        data = copied,
                        timestampMs = timestampMs,
                        keyFrame = keyFrame
                    )
                )
                gopBufferBytes += copied.size
                trimGopBuffer()
            }

            if (!recordingRequested || !muxerReady) {
                return
            }

            if (writtenSamples == 0) {
                if (gopBuffer.isEmpty()) {
                    onMain {
                        statusCallback?.invoke(
                            "REC pronto • aguardando primeiro quadro-chave H.264"
                        )
                    }
                    return
                }

                val first = gopBuffer.firstOrNull()
                if (first?.keyFrame != true) {
                    return
                }

                firstTimestampMs = first.timestampMs

                for (frame in gopBuffer) {
                    writeVideoFrameLocked(
                        frame.data,
                        frame.timestampMs,
                        frame.keyFrame
                    )
                }

                onMain {
                    statusCallback?.invoke(
                        "● REC gravando • quadro-chave obtido • " +
                            (recordingDisplayName ?: "MeuCFTV.mp4")
                    )
                }

                return
            }

            if (timestampMs <= lastRecordedTimestampMs) {
                return
            }

            writeVideoFrameLocked(
                copied,
                timestampMs,
                keyFrame
            )
        }
    }

    private fun trimGopBuffer() {
        while (
            gopBufferBytes > MAX_GOP_BUFFER_BYTES &&
            gopBuffer.size > 1
        ) {
            val removed = gopBuffer.removeFirst()
            gopBufferBytes -= removed.data.size

            if (removed.keyFrame) {
                gopBuffer.clear()
                gopBufferBytes = 0
                break
            }
        }
    }

    private fun writeVideoFrameLocked(
        sample: ByteArray,
        timestampMs: Long,
        keyFrame: Boolean
    ) {
        val localMuxer = muxer ?: return
        if (videoTrackIndex < 0) return

        if (firstTimestampMs == Long.MIN_VALUE) {
            firstTimestampMs = timestampMs
        }

        val calculatedPtsUs =
            max(
                0L,
                (timestampMs - firstTimestampMs) * 1_000L
            )

        val ptsUs =
            if (calculatedPtsUs <= lastPtsUs) {
                lastPtsUs + 1L
            } else {
                calculatedPtsUs
            }

        lastPtsUs = ptsUs
        lastRecordedTimestampMs = timestampMs

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
                videoTrackIndex,
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

    private fun isH264KeyFrame(
        data: ByteArray
    ): Boolean {
        if (data.isEmpty()) return false

        var i = 0
        var foundStartCode = false

        while (i < data.size - 3) {
            val startCodeLength = when {
                i + 3 < data.size &&
                    data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() &&
                    data[i + 3] == 1.toByte() -> 4

                data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3

                else -> 0
            }

            if (startCodeLength > 0) {
                foundStartCode = true
                val nalIndex = i + startCodeLength

                if (nalIndex < data.size) {
                    val nalType =
                        data[nalIndex].toInt() and 0x1f

                    if (nalType == 5) {
                        return true
                    }
                }

                i = nalIndex
            } else {
                i++
            }
        }

        if (!foundStartCode) {
            return (data[0].toInt() and 0x1f) == 5
        }

        return false
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
        lastRecordedTimestampMs = Long.MIN_VALUE
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

    companion object {
        private const val MAX_GOP_BUFFER_BYTES = 24 * 1024 * 1024
    }
}
