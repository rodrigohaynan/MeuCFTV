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
import kotlin.math.max

/**
 * Yoosee-specific two-way audio implementation.
 *
 * These cameras use a proprietary RTSP command (USER_CMD_SET) instead of the
 * standard ONVIF Audio Backchannel on several firmware families.
 *
 * Audio format:
 * - PCM signed 16-bit little-endian
 * - mono
 * - 8 kHz
 * - 320 PCM bytes per packet
 * - proprietary interleaved frame: '$', channel 2, 16-bit LITTLE-ENDIAN length,
 *   12 zero bytes, then PCM.
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

        val currentSocket = socket
        if (currentSocket != null && currentSocket.isConnected) {
            runCatching {
                val closeCommand = buildString {
                    append("USER_CMD_SET rtsp://")
                    append(config.host)
                    append("/onvif1 RTSP/1.0\r\n")
                    append("CSeq: 10\r\n")
                    append("Content-length: strlen(Content-type)\r\n")
                    append("Content-type: AudioCtlCmd:CLOSE\r\n\r\n")
                }

                currentSocket.getOutputStream().write(
                    closeCommand.toByteArray(Charsets.UTF_8)
                )
                currentSocket.getOutputStream().flush()
            }
        }

        runCatching { currentSocket?.close() }
        runCatching { worker?.interrupt() }
    }

    private fun runYooseeTalkback(
        onStatus: (String) -> Unit
    ) {
        onStatus("Reiniciando canal de áudio da câmera...")

        resetYooseeAudioSession()

        if (!running.get()) {
            return
        }

        Thread.sleep(120L)

        onStatus("Abrindo áudio da câmera...")

        val localSocket = Socket().apply {
            connect(
                InetSocketAddress(
                    config.host,
                    config.rtspPort
                ),
                CONNECT_TIMEOUT_MS
            )
            soTimeout = RESPONSE_TIMEOUT_MS
            tcpNoDelay = true
        }

        socket = localSocket

        val input = BufferedInputStream(
            localSocket.getInputStream()
        )
        val output = BufferedOutputStream(
            localSocket.getOutputStream()
        )

        val openCommand = buildString {
            append("USER_CMD_SET rtsp://")
            append(config.host)
            append("/onvif0 RTSP/1.0\r\n")
            append("CSeq: 8\r\n")
            append("Content-length: strlen(Content-type)\r\n")
            append("Content-type: AudioCtlCmd:OPEN\r\n\r\n")
        }

        output.write(
            openCommand.toByteArray(Charsets.UTF_8)
        )
        output.flush()

        val response = readUntilOpenAccepted(input)

        if (
            !response.contains(
                "CSeq: 8",
                ignoreCase = true
            )
        ) {
            throw IOException(
                "a câmera não confirmou AudioCtlCmd:OPEN"
            )
        }

        localSocket.soTimeout = 0

        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimum <= 0) {
            throw IOException(
                "Android não disponibilizou captura PCM 8 kHz"
            )
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(
                minimum,
                CHUNK_SIZE * 16
            )
        )

        if (
            recorder.state != AudioRecord.STATE_INITIALIZED
        ) {
            recorder.release()
            throw IOException(
                "não foi possível inicializar o microfone"
            )
        }

        audioRecord = recorder
        recorder.startRecording()

        onStatus(
            "🎙 Falando na câmera • Yoosee AudioCtlCmd ativo"
        )

        val chunk = ByteArray(CHUNK_SIZE)
        val prebuffer = ArrayList<ByteArray>(
            PREBUFFER_PACKETS
        )

        while (
            running.get() &&
            prebuffer.size < PREBUFFER_PACKETS
        ) {
            val complete = readPcmChunk(
                recorder,
                chunk
            ) ?: continue

            prebuffer += complete
        }

        for (buffered in prebuffer) {
            if (!running.get()) break
            sendYooseeAudioFrame(
                output,
                buffered
            )
        }

        while (running.get()) {
            val complete = readPcmChunk(
                recorder,
                chunk
            ) ?: continue

            sendYooseeAudioFrame(
                output,
                complete
            )
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

            resetSocket.soTimeout = 500

            val command = buildString {
                append("USER_CMD_SET rtsp://")
                append(config.host)
                append("/onvif1 RTSP/1.0\r\n")
                append("CSeq: 10\r\n")
                append("Content-length: strlen(Content-type)\r\n")
                append("Content-type: AudioCtlCmd:CLOSE\r\n\r\n")
            }

            resetSocket
                .getOutputStream()
                .write(
                    command.toByteArray(
                        Charsets.UTF_8
                    )
                )

            resetSocket
                .getOutputStream()
                .flush()

            Thread.sleep(60L)
        } catch (_: Exception) {
            // A stale session may already be gone. Continue with OPEN.
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
                val read =
                    input.read(buffer)

                if (read <= 0) {
                    break
                }

                val part = String(
                    buffer,
                    0,
                    read,
                    Charsets.UTF_8
                )

                all.append(part)

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

        if (total != reusable.size) return null

        return reusable.copyOf()
    }

    private fun sendYooseeAudioFrame(
        output: BufferedOutputStream,
        pcm: ByteArray
    ) {
        val payloadLength =
            PROPRIETARY_PADDING_SIZE + pcm.size

        val header = byteArrayOf(
            0x24,
            0x02,
            (payloadLength and 0xff).toByte(),
            ((payloadLength ushr 8) and 0xff).toByte()
        )

        output.write(header)
        output.write(
            ByteArray(PROPRIETARY_PADDING_SIZE)
        )
        output.write(pcm)
        output.flush()
    }

    companion object {
        private const val SAMPLE_RATE = 8_000
        private const val CHUNK_SIZE = 320
        private const val PROPRIETARY_PADDING_SIZE = 12

        // 50 x 20 ms = about one second, matching the Yoosee
        // reference intercom implementation before the initial burst.
        private const val PREBUFFER_PACKETS = 50

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val RESPONSE_TIMEOUT_MS = 3_000
    }
}
