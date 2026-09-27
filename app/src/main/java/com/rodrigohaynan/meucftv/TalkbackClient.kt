package com.rodrigohaynan.meucftv

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Yoosee-compatible two-way audio.
 *
 * The official Yoosee player exposes separate talk/start, audio encoder and
 * send-audio stages and supports multiple audio codecs. For the proprietary
 * RTSP AudioCtlCmd transport used by this camera we keep PCM 8 kHz support and
 * also expose G711A/G711U test modes so the exact camera-side expectation can
 * be validated without changing the rest of the app.
 */
class TalkbackClient(
    private val config: CameraConfig
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var worker: Thread? = null

    fun isTalking(): Boolean = running.get()

    fun start(
        onStatus: (String) -> Unit,
        onStopped: () -> Unit
    ) {
        if (!running.compareAndSet(false, true)) {
            onStatus("Microfone já está ativo")
            return
        }

        worker = Thread {
            try {
                runYooseeTalkback(onStatus)
            } catch (error: Exception) {
                onStatus(
                    "Falha no microfone: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            } finally {
                running.set(false)

                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                audioRecord = null

                sendCloseCommand(socket)
                runCatching { socket?.close() }
                socket = null

                onStopped()
            }
        }.apply {
            name = "MeuCFTV-Yoosee-Talkback"
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        runCatching { audioRecord?.stop() }
        sendCloseCommand(socket)
        runCatching { socket?.close() }
        runCatching { worker?.interrupt() }
    }

    private fun runYooseeTalkback(
        onStatus: (String) -> Unit
    ) {
        onStatus("Preparando canal de fala Yoosee...")

        // Clear a possible stale session left by a previous app/process.
        resetYooseeAudioSession()

        if (!running.get()) return

        Thread.sleep(100L)

        val localSocket = Socket().apply {
            keepAlive = true
            tcpNoDelay = true
            connect(
                InetSocketAddress(
                    config.host,
                    config.rtspPort
                ),
                CONNECT_TIMEOUT_MS
            )
            soTimeout = RESPONSE_TIMEOUT_MS
        }

        socket = localSocket

        val input = BufferedInputStream(
            localSocket.getInputStream()
        )
        val output = BufferedOutputStream(
            localSocket.getOutputStream()
        )

        sendOpenCommand(output)

        val response = readUntilOpenAccepted(input)

        if (
            !response.contains(
                "CSeq: 8",
                ignoreCase = true
            )
        ) {
            val compact = response
                .replace("\r", " ")
                .replace("\n", " ")
                .trim()
                .take(120)

            throw IOException(
                if (compact.isBlank()) {
                    "a câmera não respondeu ao AudioCtlCmd:OPEN"
                } else {
                    "AudioCtlCmd recusado: $compact"
                }
            )
        }

        // Mirror the official/reference implementation: keep draining camera
        // responses while audio is being sent on the same TCP connection.
        localSocket.soTimeout = 0
        startResponseDrainThread(
            input,
            localSocket
        )

        val (recorder, sourceName) =
            createAudioRecord()

        audioRecord = recorder
        recorder.startRecording()

        val mode = config.talkbackCodec
        val pcmChunk = ByteArray(PCM_CHUNK_SIZE)
        val queue = ArrayList<ByteArray>(
            PREBUFFER_PACKETS
        )

        onStatus(
            "🎙 Canal aberto • ${mode.displayName} • fonte $sourceName"
        )

        while (
            running.get() &&
            queue.size < PREBUFFER_PACKETS
        ) {
            val pcm = readPcmChunk(
                recorder,
                pcmChunk
            ) ?: continue

            queue += encodeForCamera(
                pcm,
                mode
            )
        }

        var packetsSent = 0L
        var bytesSent = 0L
        var lastLevel = 0

        for (packet in queue) {
            if (!running.get()) break

            sendYooseeAudioFrame(
                output,
                packet
            )

            packetsSent++
            bytesSent += packet.size
        }

        while (running.get()) {
            val pcm = readPcmChunk(
                recorder,
                pcmChunk
            ) ?: continue

            lastLevel = pcmLevelPercent(pcm)

            val packet = encodeForCamera(
                pcm,
                mode
            )

            sendYooseeAudioFrame(
                output,
                packet
            )

            packetsSent++
            bytesSent += packet.size

            if (
                packetsSent %
                    STATUS_EVERY_PACKETS == 0L
            ) {
                onStatus(
                    "🎙 ${mode.displayName} • " +
                        "$packetsSent pacotes • " +
                        "${bytesSent / 1024} KB • " +
                        "nível $lastLevel%"
                )
            }
        }
    }

    private fun createAudioRecord(): Pair<AudioRecord, String> {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimum <= 0) {
            throw IOException(
                "Android não disponibilizou PCM 8 kHz"
            )
        }

        val bufferSize = max(
            minimum,
            PCM_CHUNK_SIZE * 20
        )

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"
        )

        for ((source, label) in sources) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: continue

            if (
                recorder.state ==
                AudioRecord.STATE_INITIALIZED
            ) {
                return recorder to label
            }

            runCatching { recorder.release() }
        }

        throw IOException(
            "nenhuma fonte de microfone inicializou em 8 kHz"
        )
    }

    private fun encodeForCamera(
        pcm: ByteArray,
        codec: TalkbackCodec
    ): ByteArray =
        when (codec) {
            TalkbackCodec.PCM16 ->
                pcm.copyOf()

            TalkbackCodec.G711A ->
                ByteArray(
                    pcm.size / 2
                ) { index ->
                    encodeAlaw(
                        littleEndianSample(
                            pcm,
                            index * 2
                        )
                    )
                }

            TalkbackCodec.G711U ->
                ByteArray(
                    pcm.size / 2
                ) { index ->
                    encodeUlaw(
                        littleEndianSample(
                            pcm,
                            index * 2
                        )
                    )
                }
        }

    private fun littleEndianSample(
        pcm: ByteArray,
        offset: Int
    ): Short {
        val low =
            pcm[offset].toInt() and 0xff
        val high =
            pcm[offset + 1].toInt()

        return (
            (high shl 8) or low
            ).toShort()
    }

    private fun encodeUlaw(
        input: Short
    ): Byte {
        var sample = input.toInt()
        val sign =
            if (sample < 0) 0x80 else 0x00

        if (sample < 0) {
            sample = -sample
        }

        sample =
            min(sample, 32635) +
                0x84

        var exponent = 7
        var mask = 0x4000

        while (
            exponent > 0 &&
            sample and mask == 0
        ) {
            exponent--
            mask = mask shr 1
        }

        val mantissa =
            (sample shr (exponent + 3)) and
                0x0f

        val value =
            (
                sign or
                    (exponent shl 4) or
                    mantissa
                ).inv() and 0xff

        return value.toByte()
    }

    private fun encodeAlaw(
        input: Short
    ): Byte {
        var sample = input.toInt()
        var sign = 0x00

        if (sample < 0) {
            sign = 0x80
            sample = -sample
        }

        sample = min(sample, 32767)

        val compressed =
            if (sample < 256) {
                sample shr 4
            } else {
                var exponent = 1
                var value = sample shr 8

                while (
                    value > 1 &&
                    exponent < 7
                ) {
                    value = value shr 1
                    exponent++
                }

                (
                    exponent shl 4
                    ) or
                    (
                        sample shr
                            (exponent + 3)
                        ) and 0x0f
            }

        return (
            compressed xor
                (sign xor 0x55)
            ).toByte()
    }

    private fun pcmLevelPercent(
        pcm: ByteArray
    ): Int {
        var peak = 0
        var index = 0

        while (index + 1 < pcm.size) {
            val sample =
                abs(
                    littleEndianSample(
                        pcm,
                        index
                    ).toInt()
                )

            if (sample > peak) {
                peak = sample
            }

            index += 2
        }

        return (
            peak * 100L /
                32767L
            ).toInt()
                .coerceIn(
                    0,
                    100
                )
    }

    private fun sendOpenCommand(
        output: BufferedOutputStream
    ) {
        val command = buildString {
            append("USER_CMD_SET rtsp://")
            append(config.host)
            append("/onvif0 RTSP/1.0\r\n")
            append("CSeq: 8\r\n")
            append("Content-length: strlen(Content-type)\r\n")
            append("Content-type: AudioCtlCmd:OPEN\r\n\r\n")
        }

        output.write(
            command.toByteArray(
                Charsets.UTF_8
            )
        )
        output.flush()
    }

    private fun sendCloseCommand(
        target: Socket?
    ) {
        if (
            target == null ||
            !target.isConnected ||
            target.isClosed
        ) {
            return
        }

        runCatching {
            val command = buildString {
                append("USER_CMD_SET rtsp://")
                append(config.host)
                append("/onvif1 RTSP/1.0\r\n")
                append("CSeq: 10\r\n")
                append("Content-length: strlen(Content-type)\r\n")
                append("Content-type: AudioCtlCmd:CLOSE\r\n\r\n")
            }

            target.getOutputStream().write(
                command.toByteArray(
                    Charsets.UTF_8
                )
            )
            target.getOutputStream().flush()
        }
    }

    private fun resetYooseeAudioSession() {
        val resetSocket = Socket()

        try {
            resetSocket.connect(
                InetSocketAddress(
                    config.host,
                    config.rtspPort
                ),
                CONNECT_TIMEOUT_MS
            )
            resetSocket.tcpNoDelay = true
            sendCloseCommand(resetSocket)
            Thread.sleep(60L)
        } catch (_: Exception) {
            // A previous session may already be gone.
        } finally {
            runCatching {
                resetSocket.close()
            }
        }
    }

    private fun readUntilOpenAccepted(
        input: BufferedInputStream
    ): String {
        val all = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline =
            System.nanoTime() +
                RESPONSE_TIMEOUT_MS *
                    1_000_000L

        while (
            running.get() &&
            System.nanoTime() <
                deadline
        ) {
            try {
                val read = input.read(buffer)

                if (read <= 0) break

                all.append(
                    String(
                        buffer,
                        0,
                        read,
                        Charsets.UTF_8
                    )
                )

                if (
                    all.contains(
                        "CSeq: 8",
                        ignoreCase = true
                    )
                ) {
                    return all.toString()
                }
            } catch (
                _: SocketTimeoutException
            ) {
                break
            }
        }

        return all.toString()
    }

    private fun startResponseDrainThread(
        input: BufferedInputStream,
        localSocket: Socket
    ) {
        Thread {
            val buffer = ByteArray(4096)

            try {
                while (
                    running.get() &&
                    !localSocket.isClosed
                ) {
                    val read = input.read(buffer)

                    if (read < 0) break
                }
            } catch (_: Exception) {
                // Closing the socket while releasing push-to-talk is expected.
            }
        }.apply {
            name = "MeuCFTV-Yoosee-Talkback-RX"
            isDaemon = true
            start()
        }
    }

    private fun readPcmChunk(
        recorder: AudioRecord,
        reusable: ByteArray
    ): ByteArray? {
        var total = 0

        while (
            running.get() &&
            total < reusable.size
        ) {
            val read = recorder.read(
                reusable,
                total,
                reusable.size - total,
                AudioRecord.READ_BLOCKING
            )

            if (read < 0) {
                throw IOException(
                    "erro AudioRecord $read"
                )
            }

            if (read == 0) continue
            total += read
        }

        if (total != reusable.size) {
            return null
        }

        return reusable.copyOf()
    }

    private fun sendYooseeAudioFrame(
        output: BufferedOutputStream,
        payload: ByteArray
    ) {
        val payloadLength =
            PROPRIETARY_PADDING_SIZE +
                payload.size

        val header = byteArrayOf(
            0x24,
            0x02,
            (payloadLength and 0xff)
                .toByte(),
            (
                (payloadLength ushr 8) and
                    0xff
                ).toByte()
        )

        output.write(header)
        output.write(
            ByteArray(
                PROPRIETARY_PADDING_SIZE
            )
        )
        output.write(payload)
        output.flush()
    }

    companion object {
        private const val SAMPLE_RATE = 8_000

        // 20 ms of PCM S16LE at 8 kHz mono.
        private const val PCM_CHUNK_SIZE = 320

        private const val PROPRIETARY_PADDING_SIZE = 12

        // The version that produced audible output used a short initial queue.
        private const val PREBUFFER_PACKETS = 10

        private const val STATUS_EVERY_PACKETS = 25L

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val RESPONSE_TIMEOUT_MS = 3_000
    }
}
