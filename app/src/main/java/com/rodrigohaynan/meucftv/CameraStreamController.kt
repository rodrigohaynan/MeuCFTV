package com.rodrigohaynan.meucftv

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
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
import java.io.ByteArrayOutputStream
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

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

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

    fun startRecording(context: Context, onStatus: (String) -> Unit) {
        synchronized(recordLock) {
            if (recordingRequested) {
                onMain { onStatus("Gravação já está em andamento") }
                return
            }

            recordingContext = context.applicationContext
            recordingRequested = true
            firstTimestampMs = Long.MIN_VALUE
            lastPtsUs = -1L
            writtenSamples = 0
        }

        onMain {
            onStatus("REC iniciado • aguardando quadro-chave")
        }
    }

    fun stopRecording(onStatus: (String) -> Unit) {
        val message: String

        synchronized(recordLock) {
            recordingRequested = false

            val currentMuxer = muxer
            val currentUri = recordingUri
            val currentContext = recordingContext
            val sampleCount = writtenSamples

            if (currentMuxer == null) {
                resetRecordingState()
                message = "Gravação cancelada antes do primeiro quadro-chave"
            } else {
                runCatching {
                    if (sampleCount > 0) {
                        currentMuxer.stop()
                    }
                }
                runCatching { currentMuxer.release() }
                runCatching { parcelFileDescriptor?.close() }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentUri != null && currentContext != null) {
                    if (sampleCount > 0) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        runCatching {
                            currentContext.contentResolver.update(currentUri, values, null, null)
                        }
                    } else {
                        runCatching {
                            currentContext.contentResolver.delete(currentUri, null, null)
                        }
                    }
                }

                message = if (sampleCount > 0) {
                    "Vídeo salvo no armazenamento interno: Movies/MeuCFTV/${recordingDisplayName ?: "MeuCFTV.mp4"}"
                } else {
                    "Nenhum quadro foi gravado"
                }

                resetRecordingState()
            }
        }

        onMain { onStatus(message) }
    }

    fun captureSnapshot(context: Context, onStatus: (String) -> Unit) {
        val view = surfaceView

        if (view == null || view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) {
            onMain { onStatus("Não foi possível capturar: vídeo ainda não está pronto") }
            return
        }

        val width = if (frameWidth > 0) frameWidth else view.width
        val height = if (frameHeight > 0) frameHeight else view.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val thread = HandlerThread("MeuCFTV-PixelCopy").apply { start() }
        val handler = Handler(thread.looper)

        PixelCopy.request(
            view.holder.surface,
            bitmap,
            { result ->
                thread.quitSafely()

                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    onMain { onStatus("Falha ao capturar imagem • código $result") }
                    return@request
                }

                Thread {
                    val saved = runCatching {
                        saveSnapshot(context.applicationContext, bitmap)
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
        val nals = splitAnnexB(data, offset, length)
        if (nals.isEmpty()) return

        nals.forEach { nal ->
            when (nal.type) {
                7 -> sps = withStartCode(nal.payload)
                8 -> pps = withStartCode(nal.payload)
            }
        }

        if (!recordingRequested) return

        synchronized(recordLock) {
            if (!recordingRequested) return

            val isKeyFrame = nals.any { it.type == 5 }

            if (muxer == null) {
                if (!isKeyFrame) return
                if (sps == null || pps == null || frameWidth <= 0 || frameHeight <= 0) return

                val context = recordingContext ?: return
                if (!createMuxer(context)) return
            }

            if (firstTimestampMs == Long.MIN_VALUE) {
                if (!isKeyFrame) return
                firstTimestampMs = timestampMs
            }

            val sample = buildVideoSample(nals)
            if (sample.isEmpty()) return

            val calculatedPts = max(0L, (timestampMs - firstTimestampMs) * 1_000L)
            val ptsUs = if (calculatedPts <= lastPtsUs) lastPtsUs + 1L else calculatedPts
            lastPtsUs = ptsUs

            val info = MediaCodec.BufferInfo().apply {
                set(
                    0,
                    sample.size,
                    ptsUs,
                    if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
            }

            runCatching {
                muxer?.writeSampleData(
                    videoTrackIndex,
                    ByteBuffer.wrap(sample),
                    info
                )
                writtenSamples++
            }
        }
    }

    private fun createMuxer(context: Context): Boolean {
        return runCatching {
            val output = createVideoOutput(context)
            recordingDisplayName = output.displayName
            recordingUri = output.uri
            fallbackRecordingFile = output.file
            parcelFileDescriptor = output.pfd

            val localMuxer = MediaMuxer(
                output.pfd.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val format = android.media.MediaFormat.createVideoFormat(
                android.media.MediaFormat.MIMETYPE_VIDEO_AVC,
                frameWidth,
                frameHeight
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
            }

            videoTrackIndex = localMuxer.addTrack(format)
            localMuxer.start()
            muxer = localMuxer
            true
        }.getOrElse {
            cleanupFailedRecording(context)
            false
        }
    }

    private fun cleanupFailedRecording(context: Context) {
        runCatching { muxer?.release() }
        runCatching { parcelFileDescriptor?.close() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recordingUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        } else {
            runCatching { fallbackRecordingFile?.delete() }
        }

        muxer = null
        parcelFileDescriptor = null
        recordingUri = null
        fallbackRecordingFile = null
        videoTrackIndex = -1
    }

    private fun resetRecordingState() {
        muxer = null
        parcelFileDescriptor = null
        recordingUri = null
        fallbackRecordingFile = null
        videoTrackIndex = -1
        firstTimestampMs = Long.MIN_VALUE
        lastPtsUs = -1L
        writtenSamples = 0
        recordingContext = null
        recordingDisplayName = null
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

        return OutputTarget(Uri.fromFile(file), file, pfd, fileName)
    }

    private fun saveSnapshot(context: Context, bitmap: Bitmap): Uri? {
        val fileName = "MeuCFTV_${timestampForFile()}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/MeuCFTV"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            val success = context.contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            } ?: false

            if (!success) {
                context.contentResolver.delete(uri, null, null)
                return null
            }

            val ready = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, ready, null, null)
            return uri
        }

        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "MeuCFTV"
        ).apply { mkdirs() }

        val file = File(directory, fileName)
        FileOutputStream(file).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                return null
            }
        }

        return Uri.fromFile(file)
    }

    private data class NalUnit(
        val type: Int,
        val payload: ByteArray
    )

    private fun splitAnnexB(
        data: ByteArray,
        offset: Int,
        length: Int
    ): List<NalUnit> {
        val end = offset + length
        val starts = mutableListOf<Pair<Int, Int>>()
        var index = offset

        while (index < end - 3) {
            val startCodeLength = when {
                index + 3 < end &&
                    data[index] == 0.toByte() &&
                    data[index + 1] == 0.toByte() &&
                    data[index + 2] == 0.toByte() &&
                    data[index + 3] == 1.toByte() -> 4

                data[index] == 0.toByte() &&
                    data[index + 1] == 0.toByte() &&
                    data[index + 2] == 1.toByte() -> 3

                else -> 0
            }

            if (startCodeLength > 0) {
                starts += index to startCodeLength
                index += startCodeLength
            } else {
                index++
            }
        }

        if (starts.isEmpty()) {
            if (length <= 0) return emptyList()
            val payload = data.copyOfRange(offset, end)
            return listOf(
                NalUnit(
                    type = payload[0].toInt() and 0x1F,
                    payload = payload
                )
            )
        }

        val result = mutableListOf<NalUnit>()

        starts.forEachIndexed { position, start ->
            val nalStart = start.first + start.second
            val nalEnd = if (position + 1 < starts.size) {
                starts[position + 1].first
            } else {
                end
            }

            if (nalEnd > nalStart) {
                val payload = data.copyOfRange(nalStart, nalEnd)
                result += NalUnit(
                    type = payload[0].toInt() and 0x1F,
                    payload = payload
                )
            }
        }

        return result
    }

    private fun buildVideoSample(nals: List<NalUnit>): ByteArray {
        val out = ByteArrayOutputStream()

        nals.forEach { nal ->
            if (nal.type == 7 || nal.type == 8 || nal.type == 9) return@forEach
            out.write(START_CODE)
            out.write(nal.payload)
        }

        return out.toByteArray()
    }

    private fun withStartCode(payload: ByteArray): ByteArray {
        val result = ByteArray(START_CODE.size + payload.size)
        START_CODE.copyInto(result, 0)
        payload.copyInto(result, START_CODE.size)
        return result
    }

    private fun timestampForFile(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
