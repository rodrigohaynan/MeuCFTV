package com.rodrigohaynan.meucftv

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    private val recordLock = Any()

    @Volatile
    private var recordingRequested = false

    private var recordingContext: Context? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var recordingUri: Uri? = null
    private var fallbackRecordingFile: File? = null
    private var recordingDisplayName: String? = null
    private var muxerReady = false
    private var statusCallback: ((String) -> Unit)? = null

    private var streamConfig: RtspSdpProbe.VideoConfig? = null

    private data class AccessUnit(
        val data: ByteArray,
        val sourceTimestamp: Long,
        val arrivalUs: Long,
        val sync: Boolean
    )

    private val recentGop = ArrayDeque<AccessUnit>()
    private var recentGopBytes = 0

    private var pendingVideoTimestamp = Long.MIN_VALUE
    private var pendingVideoArrivalUs = 0L
    private val pendingVideoNals = ArrayList<ByteArray>()

    private var videoStartArrivalUs = Long.MIN_VALUE
    private var lastVideoPtsUs = -1L
    private var videoSamplesWritten = 0L
    private var videoUnitsSeen = 0L
    private var syncUnitsSeen = 0L

    private enum class RecordingAudioMode {
        NONE,
        AAC_DIRECT,
        G711_ULAW_TO_AAC,
        G711_ALAW_TO_AAC
    }

    private var audioMode = RecordingAudioMode.NONE
    private var audioEncoder: MediaCodec? = null
    private var audioSampleRate = 8_000
    private var audioChannels = 1
    private var audioSamplesQueued = 0L
    private var audioFramesWritten = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(view: RtspSurfaceView) {
        surfaceView = view
    }

    fun detach(view: RtspSurfaceView) {
        if (surfaceView === view) {
            surfaceView = null
        }
    }

    fun updateFrameSize(
        width: Int,
        height: Int
    ) {
        frameWidth = width
        frameHeight = height
    }

    fun isRecording(): Boolean =
        recordingRequested

    fun startRecording(
        context: Context,
        config: CameraConfig,
        onStatus: (String) -> Unit
    ) {
        val appContext =
            context.applicationContext

        synchronized(recordLock) {
            if (recordingRequested) {
                onMain {
                    onStatus(
                        "Gravação já está em andamento"
                    )
                }
                return
            }

            val output =
                runCatching {
                    createVideoOutput(
                        appContext
                    )
                }.getOrElse { error ->
                    onMain {
                        onStatus(
                            "Falha ao criar arquivo: " +
                                (
                                    error.message
                                        ?: "erro desconhecido"
                                    )
                        )
                    }
                    return
                }

            recordingContext = appContext
            recordingRequested = true
            recordingUri = output.uri
            fallbackRecordingFile =
                output.file
            parcelFileDescriptor =
                output.pfd
            recordingDisplayName =
                output.displayName
            statusCallback = onStatus

            videoTrackIndex = -1
            audioTrackIndex = -1
            videoStartArrivalUs =
                Long.MIN_VALUE
            lastVideoPtsUs = -1L
            videoSamplesWritten = 0L
            videoUnitsSeen = 0L
            syncUnitsSeen = 0L
            audioSamplesQueued = 0L
            audioFramesWritten = 0L
            muxerReady = false

            onMain {
                onStatus(
                    "REC preparado • arquivo criado em " +
                        "Movies/MeuCFTV/" +
                        output.displayName
                )
            }
        }

        Thread {
            val probed =
                runCatching {
                    RtspSdpProbe.probe(
                        config
                    )
                }.getOrElse { error ->
                    failRecording(
                        "Falha ao preparar gravação: " +
                            (
                                error.message
                                    ?: error.javaClass
                                        .simpleName
                                )
                    )
                    return@Thread
                }

            synchronized(recordLock) {
                if (!recordingRequested) {
                    return@Thread
                }

                streamConfig = probed

                val pfd =
                    parcelFileDescriptor
                        ?: return@Thread

                val width =
                    frameWidth
                        .takeIf { it > 0 }
                        ?: 1920
                val height =
                    frameHeight
                        .takeIf { it > 0 }
                        ?: 1080

                val localMuxer =
                    runCatching {
                        MediaMuxer(
                            pfd.fileDescriptor,
                            MediaMuxer
                                .OutputFormat
                                .MUXER_OUTPUT_MPEG_4
                        )
                    }.getOrElse { error ->
                        failRecordingLocked(
                            "Falha ao abrir MP4: " +
                                (
                                    error.message
                                        ?: error.javaClass
                                            .simpleName
                                    )
                        )
                        return@Thread
                    }

                val videoFormat =
                    MediaFormat
                        .createVideoFormat(
                            MediaFormat
                                .MIMETYPE_VIDEO_AVC,
                            width,
                            height
                        )
                        .apply {
                            setByteBuffer(
                                "csd-0",
                                ByteBuffer.wrap(
                                    probed.sps
                                )
                            )
                            setByteBuffer(
                                "csd-1",
                                ByteBuffer.wrap(
                                    probed.pps
                                )
                            )
                        }

                try {
                    videoTrackIndex =
                        localMuxer.addTrack(
                            videoFormat
                        )

                    configureAudioTrackLocked(
                        localMuxer,
                        probed.audio
                    )

                    localMuxer.start()
                    muxer = localMuxer
                    muxerReady = true

                    if (
                        recentGop
                            .firstOrNull()
                            ?.sync == true
                    ) {
                        val buffered =
                            recentGop.toList()

                        for (unit in buffered) {
                            writeVideoAccessUnitLocked(
                                unit
                            )
                        }
                    }

                    onMain {
                        statusCallback?.invoke(
                            buildRecordingReadyMessage()
                        )
                    }
                } catch (error: Exception) {
                    runCatching {
                        localMuxer.release()
                    }
                    failRecordingLocked(
                        "Falha ao iniciar MP4: " +
                            (
                                error.message
                                    ?: error.javaClass
                                        .simpleName
                                )
                    )
                }
            }
        }.apply {
            name = "MeuCFTV-Recorder-Setup"
            start()
        }
    }

    fun stopRecording(
        onStatus: (String) -> Unit
    ) {
        val message: String

        synchronized(recordLock) {
            if (!recordingRequested) {
                onMain {
                    onStatus(
                        "Nenhuma gravação em andamento"
                    )
                }
                return
            }

            flushPendingVideoLocked()

            if (
                audioMode ==
                RecordingAudioMode
                    .G711_ULAW_TO_AAC ||
                audioMode ==
                RecordingAudioMode
                    .G711_ALAW_TO_AAC
            ) {
                finishAudioEncoderLocked()
            }

            recordingRequested = false

            val success =
                videoSamplesWritten > 0L

            if (muxerReady) {
                runCatching {
                    muxer?.stop()
                }
            }

            runCatching {
                muxer?.release()
            }

            runCatching {
                parcelFileDescriptor
                    ?.close()
            }

            finalizeMediaStore(
                success
            )

            message =
                if (success) {
                    "Vídeo salvo com " +
                        "$videoSamplesWritten quadros e " +
                        "$audioFramesWritten amostras de áudio: " +
                        "Armazenamento interno > Movies > " +
                        "MeuCFTV > " +
                        (
                            recordingDisplayName
                                ?: "MeuCFTV.mp4"
                            )
                } else {
                    "Nenhum quadro de vídeo válido foi gravado; " +
                        "unidades vistas: $videoUnitsSeen, " +
                        "sincronização encontrada: $syncUnitsSeen"
                }

            resetRecordingState()
        }

        onMain {
            onStatus(message)
        }
    }

    fun captureSnapshot(
        context: Context,
        onStatus: (String) -> Unit
    ) {
        val view = surfaceView

        if (
            view == null ||
            view.width <= 0 ||
            view.height <= 0 ||
            !view.holder.surface.isValid
        ) {
            onMain {
                onStatus(
                    "Não foi possível capturar: " +
                        "vídeo ainda não está pronto"
                )
            }
            return
        }

        val width =
            if (frameWidth > 0) {
                frameWidth
            } else {
                view.width
            }

        val height =
            if (frameHeight > 0) {
                frameHeight
            } else {
                view.height
            }

        val bitmap =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        val thread =
            HandlerThread(
                "MeuCFTV-PixelCopy"
            ).apply {
                start()
            }

        val handler =
            Handler(
                thread.looper
            )

        PixelCopy.request(
            view.holder.surface,
            bitmap,
            { result ->
                thread.quitSafely()

                if (
                    result !=
                    PixelCopy.SUCCESS
                ) {
                    bitmap.recycle()
                    onMain {
                        onStatus(
                            "Falha ao capturar imagem • " +
                                "código $result"
                        )
                    }
                    return@request
                }

                Thread {
                    val saved =
                        runCatching {
                            saveSnapshot(
                                context.applicationContext,
                                bitmap
                            )
                        }.getOrNull()

                    bitmap.recycle()

                    onMain {
                        if (saved != null) {
                            onStatus(
                                "Foto salva em " +
                                    "Imagens/MeuCFTV"
                            )
                        } else {
                            onStatus(
                                "Falha ao salvar a foto"
                            )
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
        timestamp: Long
    ) {
        if (length <= 0) return

        val nal =
            data.copyOfRange(
                offset,
                offset + length
            )

        val arrivalUs =
            System.nanoTime() / 1_000L

        synchronized(recordLock) {
            if (
                pendingVideoTimestamp !=
                Long.MIN_VALUE &&
                timestamp !=
                pendingVideoTimestamp
            ) {
                flushPendingVideoLocked()
            }

            if (
                pendingVideoTimestamp ==
                Long.MIN_VALUE
            ) {
                pendingVideoTimestamp =
                    timestamp
                pendingVideoArrivalUs =
                    arrivalUs
            }

            pendingVideoNals += nal
        }
    }

    fun onAudioSample(
        data: ByteArray,
        offset: Int,
        length: Int,
        timestamp: Long
    ) {
        if (length <= 0) return

        synchronized(recordLock) {
            if (
                !recordingRequested ||
                !muxerReady ||
                audioTrackIndex < 0
            ) {
                return
            }

            when (audioMode) {
                RecordingAudioMode
                    .AAC_DIRECT -> {
                    writeDirectAacLocked(
                        data,
                        offset,
                        length
                    )
                }

                RecordingAudioMode
                    .G711_ULAW_TO_AAC -> {
                    encodeG711Locked(
                        data,
                        offset,
                        length,
                        ulaw = true
                    )
                }

                RecordingAudioMode
                    .G711_ALAW_TO_AAC -> {
                    encodeG711Locked(
                        data,
                        offset,
                        length,
                        ulaw = false
                    )
                }

                RecordingAudioMode.NONE ->
                    Unit
            }
        }
    }

    private fun flushPendingVideoLocked() {
        if (
            pendingVideoNals.isEmpty() ||
            pendingVideoTimestamp ==
            Long.MIN_VALUE
        ) {
            return
        }

        val unit =
            buildAccessUnit(
                pendingVideoNals,
                pendingVideoTimestamp,
                pendingVideoArrivalUs
            )

        pendingVideoNals.clear()
        pendingVideoTimestamp =
            Long.MIN_VALUE

        videoUnitsSeen++

        if (unit.sync) {
            syncUnitsSeen++
            recentGop.clear()
            recentGopBytes = 0
        }

        if (
            unit.sync ||
            recentGop.isNotEmpty()
        ) {
            recentGop.addLast(
                unit
            )
            recentGopBytes +=
                unit.data.size
            trimRecentGopLocked()
        }

        if (
            recordingRequested &&
            muxerReady
        ) {
            if (videoSamplesWritten == 0L) {
                if (unit.sync) {
                    writeVideoAccessUnitLocked(
                        unit
                    )
                    onMain {
                        statusCallback?.invoke(
                            "● REC gravando • " +
                                "quadro de sincronização encontrado"
                        )
                    }
                }
            } else {
                writeVideoAccessUnitLocked(
                    unit
                )
            }
        }
    }

    private fun buildAccessUnit(
        nals: List<ByteArray>,
        sourceTimestamp: Long,
        arrivalUs: Long
    ): AccessUnit {
        val out =
            ByteArrayOutputStream()

        var sync = false

        for (nal in nals) {
            out.write(nal)

            if (
                isSyncNalOrSlice(
                    nal
                )
            ) {
                sync = true
            }
        }

        return AccessUnit(
            data = out.toByteArray(),
            sourceTimestamp =
                sourceTimestamp,
            arrivalUs = arrivalUs,
            sync = sync
        )
    }

    private fun trimRecentGopLocked() {
        while (
            recentGopBytes >
            MAX_GOP_BUFFER_BYTES &&
            recentGop.size > 1
        ) {
            val removed =
                recentGop.removeFirst()

            recentGopBytes -=
                removed.data.size

            if (removed.sync) {
                recentGop.clear()
                recentGopBytes = 0
                break
            }
        }
    }

    private fun writeVideoAccessUnitLocked(
        unit: AccessUnit
    ) {
        val localMuxer =
            muxer ?: return

        if (videoTrackIndex < 0) {
            return
        }

        if (
            videoStartArrivalUs ==
            Long.MIN_VALUE
        ) {
            videoStartArrivalUs =
                unit.arrivalUs
        }

        var ptsUs =
            max(
                0L,
                unit.arrivalUs -
                    videoStartArrivalUs
            )

        if (ptsUs <= lastVideoPtsUs) {
            ptsUs =
                lastVideoPtsUs + 1L
        }

        lastVideoPtsUs = ptsUs

        val info =
            MediaCodec.BufferInfo()
                .apply {
                    set(
                        0,
                        unit.data.size,
                        ptsUs,
                        if (unit.sync) {
                            MediaCodec
                                .BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                    )
                }

        runCatching {
            localMuxer.writeSampleData(
                videoTrackIndex,
                ByteBuffer.wrap(
                    unit.data
                ),
                info
            )
            videoSamplesWritten++
        }.onFailure { error ->
            failRecordingLocked(
                "Erro ao gravar vídeo: " +
                    (
                        error.message
                            ?: error.javaClass
                                .simpleName
                        )
            )
        }
    }

    private fun configureAudioTrackLocked(
        localMuxer: MediaMuxer,
        audio: RtspSdpProbe.AudioConfig?
    ) {
        audioMode =
            RecordingAudioMode.NONE
        audioTrackIndex = -1
        audioEncoder = null

        if (audio == null) {
            return
        }

        audioSampleRate =
            audio.sampleRate
                .coerceAtLeast(8_000)
        audioChannels =
            audio.channels
                .coerceIn(1, 2)

        when (audio.codec) {
            RtspSdpProbe
                .AudioCodec.AAC -> {
                val csd =
                    audio.codecConfig
                        ?: makeAacCsd(
                            audioSampleRate,
                            audioChannels
                        )

                val format =
                    MediaFormat
                        .createAudioFormat(
                            MediaFormat
                                .MIMETYPE_AUDIO_AAC,
                            audioSampleRate,
                            audioChannels
                        )
                        .apply {
                            setInteger(
                                MediaFormat
                                    .KEY_AAC_PROFILE,
                                MediaCodecInfo
                                    .CodecProfileLevel
                                    .AACObjectLC
                            )
                            setByteBuffer(
                                "csd-0",
                                ByteBuffer.wrap(
                                    csd
                                )
                            )
                        }

                audioTrackIndex =
                    localMuxer.addTrack(
                        format
                    )
                audioMode =
                    RecordingAudioMode
                        .AAC_DIRECT
            }

            RtspSdpProbe
                .AudioCodec.PCMU,
            RtspSdpProbe
                .AudioCodec.PCMA -> {
                val aacFormat =
                    MediaFormat
                        .createAudioFormat(
                            MediaFormat
                                .MIMETYPE_AUDIO_AAC,
                            audioSampleRate,
                            audioChannels
                        )
                        .apply {
                            setInteger(
                                MediaFormat
                                    .KEY_AAC_PROFILE,
                                MediaCodecInfo
                                    .CodecProfileLevel
                                    .AACObjectLC
                            )
                            setInteger(
                                MediaFormat
                                    .KEY_BIT_RATE,
                                32_000 *
                                    audioChannels
                            )
                            setInteger(
                                MediaFormat
                                    .KEY_MAX_INPUT_SIZE,
                                16_384
                            )
                            setByteBuffer(
                                "csd-0",
                                ByteBuffer.wrap(
                                    makeAacCsd(
                                        audioSampleRate,
                                        audioChannels
                                    )
                                )
                            )
                        }

                audioTrackIndex =
                    localMuxer.addTrack(
                        aacFormat
                    )

                audioEncoder =
                    MediaCodec
                        .createEncoderByType(
                            MediaFormat
                                .MIMETYPE_AUDIO_AAC
                        )
                        .apply {
                            configure(
                                aacFormat,
                                null,
                                null,
                                MediaCodec
                                    .CONFIGURE_FLAG_ENCODE
                            )
                            start()
                        }

                audioMode =
                    if (
                        audio.codec ==
                        RtspSdpProbe
                            .AudioCodec.PCMU
                    ) {
                        RecordingAudioMode
                            .G711_ULAW_TO_AAC
                    } else {
                        RecordingAudioMode
                            .G711_ALAW_TO_AAC
                    }
            }

            RtspSdpProbe
                .AudioCodec.UNKNOWN ->
                Unit
        }
    }

    private fun writeDirectAacLocked(
        data: ByteArray,
        offset: Int,
        length: Int
    ) {
        val localMuxer =
            muxer ?: return

        if (audioTrackIndex < 0) {
            return
        }

        val ptsUs =
            (
                audioSamplesQueued *
                    1_000_000L
                ) /
                audioSampleRate

        val info =
            MediaCodec.BufferInfo()
                .apply {
                    set(
                        0,
                        length,
                        ptsUs,
                        0
                    )
                }

        localMuxer.writeSampleData(
            audioTrackIndex,
            ByteBuffer.wrap(
                data,
                offset,
                length
            ),
            info
        )

        audioSamplesQueued +=
            AAC_SAMPLES_PER_FRAME
        audioFramesWritten++
    }

    private fun encodeG711Locked(
        data: ByteArray,
        offset: Int,
        length: Int,
        ulaw: Boolean
    ) {
        val pcm =
            ByteArray(
                length * 2
            )

        var out = 0

        for (
            index in
            offset until
                offset + length
        ) {
            val sample =
                if (ulaw) {
                    decodeUlaw(
                        data[index]
                    )
                } else {
                    decodeAlaw(
                        data[index]
                    )
                }

            pcm[out++] =
                (
                    sample.toInt() and
                        0xff
                    ).toByte()

            pcm[out++] =
                (
                    (
                        sample.toInt()
                            shr 8
                        ) and 0xff
                    ).toByte()
        }

        feedPcmToAacLocked(
            pcm
        )
    }

    private fun feedPcmToAacLocked(
        pcm: ByteArray
    ) {
        val encoder =
            audioEncoder ?: return

        var offset = 0

        while (offset < pcm.size) {
            drainAudioEncoderLocked(
                endOfStream = false
            )

            val inputIndex =
                encoder.dequeueInputBuffer(
                    10_000L
                )

            if (inputIndex < 0) {
                continue
            }

            val inputBuffer =
                encoder.getInputBuffer(
                    inputIndex
                ) ?: continue

            inputBuffer.clear()

            val count =
                minOf(
                    inputBuffer.remaining(),
                    pcm.size - offset
                )

            inputBuffer.put(
                pcm,
                offset,
                count
            )

            val samples =
                count /
                    2 /
                    audioChannels

            val ptsUs =
                (
                    audioSamplesQueued *
                        1_000_000L
                    ) /
                    audioSampleRate

            encoder.queueInputBuffer(
                inputIndex,
                0,
                count,
                ptsUs,
                0
            )

            audioSamplesQueued +=
                samples
            offset += count
        }

        drainAudioEncoderLocked(
            endOfStream = false
        )
    }

    private fun finishAudioEncoderLocked() {
        val encoder =
            audioEncoder ?: return

        val inputIndex =
            encoder.dequeueInputBuffer(
                20_000L
            )

        if (inputIndex >= 0) {
            val ptsUs =
                (
                    audioSamplesQueued *
                        1_000_000L
                    ) /
                    audioSampleRate

            encoder.queueInputBuffer(
                inputIndex,
                0,
                0,
                ptsUs,
                MediaCodec
                    .BUFFER_FLAG_END_OF_STREAM
            )
        }

        drainAudioEncoderLocked(
            endOfStream = true
        )

        runCatching {
            encoder.stop()
        }
        runCatching {
            encoder.release()
        }

        audioEncoder = null
    }

    private fun drainAudioEncoderLocked(
        endOfStream: Boolean
    ) {
        val encoder =
            audioEncoder ?: return
        val localMuxer =
            muxer ?: return

        val info =
            MediaCodec.BufferInfo()

        var emptyPolls = 0

        while (true) {
            val outputIndex =
                encoder.dequeueOutputBuffer(
                    info,
                    if (endOfStream) {
                        20_000L
                    } else {
                        0L
                    }
                )

            when {
                outputIndex >= 0 -> {
                    emptyPolls = 0

                    val outputBuffer =
                        encoder.getOutputBuffer(
                            outputIndex
                        )

                    val codecConfig =
                        (
                            info.flags and
                                MediaCodec
                                    .BUFFER_FLAG_CODEC_CONFIG
                            ) != 0

                    if (
                        !codecConfig &&
                        info.size > 0 &&
                        outputBuffer != null &&
                        audioTrackIndex >= 0
                    ) {
                        outputBuffer.position(
                            info.offset
                        )
                        outputBuffer.limit(
                            info.offset +
                                info.size
                        )

                        localMuxer
                            .writeSampleData(
                                audioTrackIndex,
                                outputBuffer,
                                info
                            )

                        audioFramesWritten++
                    }

                    val eos =
                        (
                            info.flags and
                                MediaCodec
                                    .BUFFER_FLAG_END_OF_STREAM
                            ) != 0

                    encoder
                        .releaseOutputBuffer(
                            outputIndex,
                            false
                        )

                    if (eos) {
                        return
                    }
                }

                outputIndex ==
                    MediaCodec
                        .INFO_OUTPUT_FORMAT_CHANGED -> {
                    Unit
                }

                outputIndex ==
                    MediaCodec
                        .INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        return
                    }

                    emptyPolls++

                    if (emptyPolls >= 8) {
                        return
                    }
                }

                else -> Unit
            }
        }
    }

    private fun makeAacCsd(
        sampleRate: Int,
        channels: Int
    ): ByteArray {
        val frequencies =
            intArrayOf(
                96_000,
                88_200,
                64_000,
                48_000,
                44_100,
                32_000,
                24_000,
                22_050,
                16_000,
                12_000,
                11_025,
                8_000,
                7_350
            )

        val frequencyIndex =
            frequencies
                .indexOf(
                    sampleRate
                )
                .takeIf {
                    it >= 0
                }
                ?: 11

        val audioObjectType = 2
        val channelConfig =
            channels.coerceIn(
                1,
                7
            )

        val value =
            (
                audioObjectType shl 11
                ) or
                (
                    frequencyIndex shl 7
                    ) or
                (
                    channelConfig shl 3
                    )

        return byteArrayOf(
            (
                value shr 8
                ).toByte(),
            value.toByte()
        )
    }

    private fun decodeUlaw(
        value: Byte
    ): Short {
        val u =
            value.toInt()
                .inv() and 0xff

        val sign =
            u and 0x80
        val exponent =
            (u shr 4) and 0x07
        val mantissa =
            u and 0x0f

        var sample =
            (
                mantissa shl 3
                ) + 0x84

        sample =
            sample shl exponent
        sample -= 0x84

        return if (sign != 0) {
            (-sample).toShort()
        } else {
            sample.toShort()
        }
    }

    private fun decodeAlaw(
        value: Byte
    ): Short {
        val a =
            (
                value.toInt() and
                    0xff
                ) xor 0x55

        var sample =
            (
                a and 0x0f
                ) shl 4

        val segment =
            (a and 0x70) shr 4

        sample += 8

        if (segment >= 1) {
            sample += 0x100
        }

        if (segment > 1) {
            sample =
                sample shl
                    (segment - 1)
        }

        return if (
            (a and 0x80) != 0
        ) {
            sample.toShort()
        } else {
            (-sample).toShort()
        }
    }

    private fun isSyncNalOrSlice(
        data: ByteArray
    ): Boolean {
        val nals =
            splitAnnexBNals(
                data
            )

        for (nal in nals) {
            if (nal.isEmpty()) continue

            val type =
                nal[0].toInt() and 0x1f

            if (type == 5) {
                return true
            }

            if (
                type == 1 &&
                isIntraSlice(
                    nal
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun splitAnnexBNals(
        data: ByteArray
    ): List<ByteArray> {
        val starts =
            ArrayList<Pair<Int, Int>>()

        var index = 0

        while (
            index <
            data.size - 2
        ) {
            val length =
                when {
                    index + 3 <
                        data.size &&
                        data[index] ==
                        0.toByte() &&
                        data[index + 1] ==
                        0.toByte() &&
                        data[index + 2] ==
                        0.toByte() &&
                        data[index + 3] ==
                        1.toByte() -> 4

                    data[index] ==
                        0.toByte() &&
                        data[index + 1] ==
                        0.toByte() &&
                        data[index + 2] ==
                        1.toByte() -> 3

                    else -> 0
                }

            if (length > 0) {
                starts +=
                    index to length
                index += length
            } else {
                index++
            }
        }

        if (starts.isEmpty()) {
            return listOf(data)
        }

        val result =
            ArrayList<ByteArray>()

        starts.forEachIndexed {
            position,
            start ->

            val nalStart =
                start.first +
                    start.second

            val nalEnd =
                if (
                    position + 1 <
                    starts.size
                ) {
                    starts[
                        position + 1
                    ].first
                } else {
                    data.size
                }

            if (nalEnd > nalStart) {
                result +=
                    data.copyOfRange(
                        nalStart,
                        nalEnd
                    )
            }
        }

        return result
    }

    private fun isIntraSlice(
        nal: ByteArray
    ): Boolean {
        if (nal.size < 3) {
            return false
        }

        val rbsp =
            removeEmulationPrevention(
                nal.copyOfRange(
                    1,
                    nal.size
                )
            )

        val reader =
            BitReader(
                rbsp
            )

        return runCatching {
            reader.readUnsignedExpGolomb()
            val sliceType =
                reader.readUnsignedExpGolomb()

            when (sliceType % 5) {
                2, 4 -> true
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun removeEmulationPrevention(
        data: ByteArray
    ): ByteArray {
        val out =
            ByteArrayOutputStream(
                data.size
            )

        var zeroCount = 0
        var index = 0

        while (index < data.size) {
            val value =
                data[index]

            if (
                zeroCount >= 2 &&
                value ==
                0x03.toByte()
            ) {
                zeroCount = 0
                index++
                continue
            }

            out.write(
                value.toInt() and
                    0xff
            )

            zeroCount =
                if (
                    value ==
                    0.toByte()
                ) {
                    zeroCount + 1
                } else {
                    0
                }

            index++
        }

        return out.toByteArray()
    }

    private class BitReader(
        private val data: ByteArray
    ) {
        private var bitOffset = 0

        private fun readBit(): Int {
            if (
                bitOffset >=
                data.size * 8
            ) {
                throw IllegalStateException(
                    "fim do RBSP"
                )
            }

            val byteIndex =
                bitOffset / 8
            val shift =
                7 - (
                    bitOffset % 8
                    )

            bitOffset++

            return (
                data[byteIndex]
                    .toInt() shr shift
                ) and 1
        }

        fun readUnsignedExpGolomb(): Int {
            var leadingZeros = 0

            while (
                readBit() == 0
            ) {
                leadingZeros++

                if (leadingZeros > 31) {
                    throw IllegalStateException(
                        "Exp-Golomb inválido"
                    )
                }
            }

            var suffix = 0

            repeat(
                leadingZeros
            ) {
                suffix =
                    (suffix shl 1) or
                        readBit()
            }

            return (
                (1 shl leadingZeros) -
                    1
                ) + suffix
        }
    }

    private fun buildRecordingReadyMessage(): String {
        val audioText =
            when (audioMode) {
                RecordingAudioMode.NONE ->
                    "sem faixa de áudio gravável"

                RecordingAudioMode
                    .AAC_DIRECT ->
                    "áudio AAC"

                RecordingAudioMode
                    .G711_ULAW_TO_AAC ->
                    "áudio G.711 μ-law → AAC"

                RecordingAudioMode
                    .G711_ALAW_TO_AAC ->
                    "áudio G.711 A-law → AAC"
            }

        return if (
            videoSamplesWritten > 0
        ) {
            "● REC gravando • $audioText"
        } else {
            "REC ativo • aguardando quadro I/IDR • $audioText"
        }
    }

    private fun failRecording(
        message: String
    ) {
        synchronized(recordLock) {
            failRecordingLocked(
                message
            )
        }
    }

    private fun failRecordingLocked(
        message: String
    ) {
        if (!recordingRequested) {
            return
        }

        recordingRequested = false

        runCatching {
            if (muxerReady) {
                muxer?.stop()
            }
        }

        runCatching {
            muxer?.release()
        }

        runCatching {
            audioEncoder?.stop()
        }

        runCatching {
            audioEncoder?.release()
        }

        runCatching {
            parcelFileDescriptor
                ?.close()
        }

        val callback =
            statusCallback

        finalizeMediaStore(
            false
        )

        resetRecordingState()

        onMain {
            callback?.invoke(
                message
            )
        }
    }

    private fun finalizeMediaStore(
        success: Boolean
    ) {
        val context =
            recordingContext
                ?: return

        val uri =
            recordingUri

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            if (uri != null) {
                if (success) {
                    val values =
                        ContentValues()
                            .apply {
                                put(
                                    MediaStore
                                        .Video
                                        .Media
                                        .IS_PENDING,
                                    0
                                )
                            }

                    runCatching {
                        context
                            .contentResolver
                            .update(
                                uri,
                                values,
                                null,
                                null
                            )
                    }
                } else {
                    runCatching {
                        context
                            .contentResolver
                            .delete(
                                uri,
                                null,
                                null
                            )
                    }
                }
            }
        } else if (!success) {
            runCatching {
                fallbackRecordingFile
                    ?.delete()
            }
        }
    }

    private fun resetRecordingState() {
        muxer = null
        videoTrackIndex = -1
        audioTrackIndex = -1
        parcelFileDescriptor = null
        recordingUri = null
        fallbackRecordingFile = null
        recordingDisplayName = null
        muxerReady = false
        recordingContext = null
        statusCallback = null
        streamConfig = null

        videoStartArrivalUs =
            Long.MIN_VALUE
        lastVideoPtsUs = -1L
        videoSamplesWritten = 0L
        videoUnitsSeen = 0L
        syncUnitsSeen = 0L

        runCatching {
            audioEncoder?.stop()
        }
        runCatching {
            audioEncoder?.release()
        }

        audioEncoder = null
        audioMode =
            RecordingAudioMode.NONE
        audioSamplesQueued = 0L
        audioFramesWritten = 0L
    }

    private data class OutputTarget(
        val uri: Uri?,
        val file: File?,
        val pfd: ParcelFileDescriptor,
        val displayName: String
    )

    private fun createVideoOutput(
        context: Context
    ): OutputTarget {
        val fileName =
            "MeuCFTV_${timestampForFile()}.mp4"

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            val values =
                ContentValues()
                    .apply {
                        put(
                            MediaStore
                                .Video
                                .Media
                                .DISPLAY_NAME,
                            fileName
                        )
                        put(
                            MediaStore
                                .Video
                                .Media
                                .MIME_TYPE,
                            "video/mp4"
                        )
                        put(
                            MediaStore
                                .Video
                                .Media
                                .RELATIVE_PATH,
                            Environment
                                .DIRECTORY_MOVIES +
                                "/MeuCFTV"
                        )
                        put(
                            MediaStore
                                .Video
                                .Media
                                .IS_PENDING,
                            1
                        )
                    }

            val uri =
                context
                    .contentResolver
                    .insert(
                        MediaStore
                            .Video
                            .Media
                            .EXTERNAL_CONTENT_URI,
                        values
                    )
                    ?: error(
                        "Não foi possível criar " +
                            "o arquivo de vídeo"
                    )

            val pfd =
                context
                    .contentResolver
                    .openFileDescriptor(
                        uri,
                        "rw"
                    )
                    ?: error(
                        "Não foi possível abrir " +
                            "o arquivo de vídeo"
                    )

            return OutputTarget(
                uri,
                null,
                pfd,
                fileName
            )
        }

        val directory =
            File(
                context.getExternalFilesDir(
                    Environment
                        .DIRECTORY_MOVIES
                ),
                "MeuCFTV"
            ).apply {
                mkdirs()
            }

        val file =
            File(
                directory,
                fileName
            )

        val pfd =
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor
                    .MODE_CREATE or
                    ParcelFileDescriptor
                        .MODE_READ_WRITE or
                    ParcelFileDescriptor
                        .MODE_TRUNCATE
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
        val fileName =
            "MeuCFTV_${timestampForFile()}.jpg"

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            val values =
                ContentValues()
                    .apply {
                        put(
                            MediaStore
                                .Images
                                .Media
                                .DISPLAY_NAME,
                            fileName
                        )
                        put(
                            MediaStore
                                .Images
                                .Media
                                .MIME_TYPE,
                            "image/jpeg"
                        )
                        put(
                            MediaStore
                                .Images
                                .Media
                                .RELATIVE_PATH,
                            Environment
                                .DIRECTORY_PICTURES +
                                "/MeuCFTV"
                        )
                        put(
                            MediaStore
                                .Images
                                .Media
                                .IS_PENDING,
                            1
                        )
                    }

            val uri =
                context
                    .contentResolver
                    .insert(
                        MediaStore
                            .Images
                            .Media
                            .EXTERNAL_CONTENT_URI,
                        values
                    )
                    ?: return null

            val success =
                context
                    .contentResolver
                    .openOutputStream(
                        uri
                    )
                    ?.use { stream ->
                        bitmap.compress(
                            Bitmap
                                .CompressFormat
                                .JPEG,
                            95,
                            stream
                        )
                    }
                    ?: false

            if (!success) {
                context
                    .contentResolver
                    .delete(
                        uri,
                        null,
                        null
                    )
                return null
            }

            val ready =
                ContentValues()
                    .apply {
                        put(
                            MediaStore
                                .Images
                                .Media
                                .IS_PENDING,
                            0
                        )
                    }

            context
                .contentResolver
                .update(
                    uri,
                    ready,
                    null,
                    null
                )

            return uri
        }

        val directory =
            File(
                context.getExternalFilesDir(
                    Environment
                        .DIRECTORY_PICTURES
                ),
                "MeuCFTV"
            ).apply {
                mkdirs()
            }

        val file =
            File(
                directory,
                fileName
            )

        FileOutputStream(
            file
        ).use { stream ->
            if (
                !bitmap.compress(
                    Bitmap
                        .CompressFormat
                        .JPEG,
                    95,
                    stream
                )
            ) {
                return null
            }
        }

        return Uri.fromFile(
            file
        )
    }

    private fun timestampForFile(): String =
        SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(
            Date()
        )

    private fun onMain(
        block: () -> Unit
    ) {
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            block()
        } else {
            mainHandler.post(
                block
            )
        }
    }

    companion object {
        private const val MAX_GOP_BUFFER_BYTES =
            24 * 1024 * 1024

        private const val AAC_SAMPLES_PER_FRAME =
            1024L
    }
}
