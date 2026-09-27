package com.rodrigohaynan.meucftv

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Experimental ONVIF RTSP audio backchannel client.
 *
 * It probes the camera with:
 * Require: www.onvif.org/ver20/backchannel
 *
 * and supports G.711 PCMU/PCMA at 8 kHz over UDP, with RTSP/TCP
 * interleaving as fallback.
 */
class TalkbackClient(
    private val config: CameraConfig
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

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
                runTalkback(onStatus)
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
                onStopped()
            }
        }.apply {
            name = "MeuCFTV-Talkback"
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { worker?.interrupt() }
    }

    private fun runTalkback(onStatus: (String) -> Unit) {
        val baseUri = buildRtspUri()
        val socket = Socket().apply {
            connect(
                java.net.InetSocketAddress(config.host, config.rtspPort),
                5_000
            )
            soTimeout = 7_000
            tcpNoDelay = true
        }

        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val session = RtspSession(baseUri)

        try {
            onStatus("Verificando áudio bidirecional...")

            var response = request(
                input = input,
                output = output,
                session = session,
                method = "DESCRIBE",
                uri = baseUri,
                extraHeaders = listOf(
                    "Accept" to "application/sdp",
                    "Require" to BACKCHANNEL_REQUIRE
                )
            )

            if (response.code == 401) {
                session.auth = RtspAuth.fromHeader(
                    response.headers["www-authenticate"],
                    config.rtspUser,
                    config.password
                ) ?: throw IOException("Autenticação RTSP não reconhecida")

                response = request(
                    input = input,
                    output = output,
                    session = session,
                    method = "DESCRIBE",
                    uri = baseUri,
                    extraHeaders = listOf(
                        "Accept" to "application/sdp",
                        "Require" to BACKCHANNEL_REQUIRE
                    )
                )
            }

            if (response.code == 551) {
                throw IOException("câmera não oferece ONVIF Audio Backchannel")
            }

            if (response.code !in 200..299) {
                throw IOException("DESCRIBE RTSP retornou ${response.code}")
            }

            val backchannel = parseBackchannel(response.body, baseUri)
                ?: throw IOException(
                    "câmera não anunciou canal de áudio de saída ONVIF"
                )

            val udpSocket = DatagramSocket(0)
            val clientRtpPort = udpSocket.localPort
            val clientRtcpPort = clientRtpPort + 1

            var usingTcpInterleaved = false

            response = request(
                input = input,
                output = output,
                session = session,
                method = "SETUP",
                uri = backchannel.controlUri,
                extraHeaders = listOf(
                    "Transport" to
                        "RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort",
                    "Require" to BACKCHANNEL_REQUIRE
                )
            )

            var serverRtpPort = parseServerRtpPort(response.headers["transport"])

            if (response.code !in 200..299 || serverRtpPort == null) {
                udpSocket.close()

                response = request(
                    input = input,
                    output = output,
                    session = session,
                    method = "SETUP",
                    uri = backchannel.controlUri,
                    extraHeaders = listOf(
                        "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1",
                        "Require" to BACKCHANNEL_REQUIRE
                    )
                )

                if (response.code !in 200..299) {
                    throw IOException("SETUP do microfone retornou ${response.code}")
                }

                usingTcpInterleaved = true
            }

            session.sessionId = parseSessionId(response.headers["session"])
                ?: throw IOException("câmera não retornou sessão RTSP")

            response = request(
                input = input,
                output = output,
                session = session,
                method = "PLAY",
                uri = baseUri,
                extraHeaders = listOf(
                    "Require" to BACKCHANNEL_REQUIRE
                )
            )

            if (response.code !in 200..299) {
                throw IOException("PLAY do microfone retornou ${response.code}")
            }

            onStatus(
                "Microfone ativo • ${backchannel.codecLabel} • solte/parar para encerrar"
            )

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, PCM_FRAME_BYTES * 8)
            )

            audioRecord = recorder
            recorder.startRecording()

            val pcm = ByteArray(PCM_FRAME_BYTES)
            var sequence = SecureRandom().nextInt(0x10000)
            var rtpTimestamp = SecureRandom().nextInt().toLong() and 0xffffffffL
            val ssrc = SecureRandom().nextInt()
            val cameraAddress = InetAddress.getByName(config.host)

            while (running.get()) {
                val bytesRead = recorder.read(
                    pcm,
                    0,
                    pcm.size,
                    AudioRecord.READ_BLOCKING
                )

                if (bytesRead <= 0) continue

                val sampleCount = bytesRead / 2
                val encoded = ByteArray(sampleCount)

                var sampleIndex = 0
                var byteIndex = 0

                while (byteIndex + 1 < bytesRead) {
                    val sample =
                        ((pcm[byteIndex + 1].toInt() shl 8) or
                            (pcm[byteIndex].toInt() and 0xff)).toShort()

                    encoded[sampleIndex] =
                        if (backchannel.codec == Codec.PCMA) {
                            linearToAlaw(sample)
                        } else {
                            linearToUlaw(sample)
                        }

                    sampleIndex++
                    byteIndex += 2
                }

                val rtp = buildRtpPacket(
                    payloadType = backchannel.payloadType,
                    sequence = sequence,
                    timestamp = rtpTimestamp,
                    ssrc = ssrc,
                    payload = encoded
                )

                if (usingTcpInterleaved) {
                    synchronized(output) {
                        output.write('$'.code)
                        output.write(0)
                        output.write((rtp.size shr 8) and 0xff)
                        output.write(rtp.size and 0xff)
                        output.write(rtp)
                        output.flush()
                    }
                } else {
                    val port = serverRtpPort
                        ?: throw IOException("porta RTP da câmera ausente")
                    udpSocket.send(
                        DatagramPacket(
                            rtp,
                            rtp.size,
                            cameraAddress,
                            port
                        )
                    )
                }

                sequence = (sequence + 1) and 0xffff
                rtpTimestamp = (rtpTimestamp + sampleCount) and 0xffffffffL
            }

            runCatching {
                request(
                    input = input,
                    output = output,
                    session = session,
                    method = "TEARDOWN",
                    uri = baseUri,
                    extraHeaders = listOf(
                        "Require" to BACKCHANNEL_REQUIRE
                    )
                )
            }

            if (!usingTcpInterleaved) {
                udpSocket.close()
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun request(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        session: RtspSession,
        method: String,
        uri: String,
        extraHeaders: List<Pair<String, String>>
    ): RtspResponse {
        val cseq = session.nextCseq++
        val headers = mutableListOf<Pair<String, String>>(
            "CSeq" to cseq.toString(),
            "User-Agent" to "MeuCFTV/0.1.4"
        )

        session.sessionId?.let {
            headers += "Session" to it
        }

        session.auth?.authorization(method, uri)?.let {
            headers += "Authorization" to it
        }

        headers += extraHeaders

        val request = buildString {
            append(method)
            append(' ')
            append(uri)
            append(" RTSP/1.0\r\n")
            headers.forEach { (name, value) ->
                append(name)
                append(": ")
                append(value)
                append("\r\n")
            }
            append("\r\n")
        }

        synchronized(output) {
            output.write(request.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        return readResponse(input)
    }

    private fun readResponse(input: BufferedInputStream): RtspResponse {
        val statusLine = readLine(input)
            ?: throw IOException("RTSP encerrou a conexão")

        if (!statusLine.startsWith("RTSP/")) {
            throw IOException("resposta RTSP inválida")
        }

        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("status RTSP inválido")

        val headers = linkedMapOf<String, String>()

        while (true) {
            val line = readLine(input)
                ?: throw IOException("fim inesperado da resposta RTSP")

            if (line.isEmpty()) break

            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator)
                    .trim()
                    .lowercase(Locale.US)
                val value = line.substring(separator + 1).trim()
                headers[name] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var total = 0
            while (total < contentLength) {
                val read = input.read(bytes, total, contentLength - total)
                if (read < 0) {
                    throw IOException("SDP RTSP incompleto")
                }
                total += read
            }
            String(bytes, Charsets.UTF_8)
        } else {
            ""
        }

        return RtspResponse(code, headers, body)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var previous = -1

        while (true) {
            val current = input.read()
            if (current < 0) {
                return if (out.size() == 0) null
                else out.toString(Charsets.UTF_8.name())
            }

            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = out.toByteArray()
                val size = if (bytes.isNotEmpty() &&
                    bytes.last() == '\r'.code.toByte()
                ) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, size, Charsets.UTF_8)
            }

            out.write(current)
            previous = current
        }
    }

    private fun parseBackchannel(
        sdp: String,
        baseUri: String
    ): BackchannelInfo? {
        val sections = sdp
            .replace("\r\n", "\n")
            .split("\nm=")
            .mapIndexed { index, block ->
                if (index == 0) block else "m=$block"
            }

        for (section in sections) {
            if (!section.startsWith("m=audio")) continue
            if (!section.contains("a=sendonly", ignoreCase = true)) continue

            val mediaLine = section.lineSequence()
                .firstOrNull { it.startsWith("m=audio") }
                ?: continue

            val mediaParts = mediaLine.trim().split(Regex("\\s+"))
            val defaultPayload = mediaParts.getOrNull(3)?.toIntOrNull()

            val rtpMap = section.lineSequence()
                .firstOrNull { it.startsWith("a=rtpmap:", ignoreCase = true) }

            val mapped = rtpMap?.let {
                Regex(
                    """a=rtpmap:(\d+)\s+(PCMU|PCMA)/(\d+)""",
                    RegexOption.IGNORE_CASE
                ).find(it)
            }

            val payload = mapped
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: defaultPayload
                ?: continue

            val codecName = mapped
                ?.groupValues
                ?.getOrNull(2)
                ?.uppercase(Locale.US)
                ?: when (payload) {
                    0 -> "PCMU"
                    8 -> "PCMA"
                    else -> ""
                }

            val codec = when (codecName) {
                "PCMU" -> Codec.PCMU
                "PCMA" -> Codec.PCMA
                else -> continue
            }

            val rate = mapped
                ?.groupValues
                ?.getOrNull(3)
                ?.toIntOrNull()
                ?: SAMPLE_RATE

            if (rate != SAMPLE_RATE) continue

            val control = section.lineSequence()
                .firstOrNull { it.startsWith("a=control:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?: continue

            return BackchannelInfo(
                controlUri = resolveControlUri(baseUri, control),
                payloadType = payload,
                codec = codec
            )
        }

        return null
    }

    private fun resolveControlUri(baseUri: String, control: String): String {
        if (control.startsWith("rtsp://", ignoreCase = true)) {
            return control
        }

        if (control == "*") return baseUri

        return if (control.startsWith("/")) {
            "rtsp://${config.host}:${config.rtspPort}$control"
        } else {
            baseUri.trimEnd('/') + "/" + control
        }
    }

    private fun parseServerRtpPort(transport: String?): Int? {
        if (transport.isNullOrBlank()) return null

        return Regex(
            """server_port=(\d+)(?:-(\d+))?""",
            RegexOption.IGNORE_CASE
        )
            .find(transport)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseSessionId(session: String?): String? =
        session
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun buildRtpPacket(
        payloadType: Int,
        sequence: Int,
        timestamp: Long,
        ssrc: Int,
        payload: ByteArray
    ): ByteArray {
        val packet = ByteArray(12 + payload.size)

        packet[0] = 0x80.toByte()
        packet[1] = (payloadType and 0x7f).toByte()

        packet[2] = ((sequence shr 8) and 0xff).toByte()
        packet[3] = (sequence and 0xff).toByte()

        packet[4] = ((timestamp shr 24) and 0xff).toByte()
        packet[5] = ((timestamp shr 16) and 0xff).toByte()
        packet[6] = ((timestamp shr 8) and 0xff).toByte()
        packet[7] = (timestamp and 0xff).toByte()

        packet[8] = ((ssrc shr 24) and 0xff).toByte()
        packet[9] = ((ssrc shr 16) and 0xff).toByte()
        packet[10] = ((ssrc shr 8) and 0xff).toByte()
        packet[11] = (ssrc and 0xff).toByte()

        payload.copyInto(packet, 12)
        return packet
    }

    private fun linearToUlaw(sample: Short): Byte {
        val bias = 0x84
        val clip = 32635

        var pcm = sample.toInt()
        var mask: Int

        if (pcm < 0) {
            pcm = -pcm
            mask = 0x7f
        } else {
            mask = 0xff
        }

        pcm = pcm.coerceAtMost(clip) + bias

        var segment = 7
        var value = pcm shr 7

        while (value > 1 && segment > 0) {
            value = value shr 1
            segment--
        }

        segment = 7 - segment

        val mantissa = (pcm shr (segment + 3)) and 0x0f
        return ((segment shl 4) or mantissa xor mask).toByte()
    }

    private fun linearToAlaw(sample: Short): Byte {
        var pcm = sample.toInt()
        val mask: Int

        if (pcm >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            pcm = -pcm - 1
        }

        pcm = pcm.coerceAtMost(32635)

        val compressed = if (pcm >= 256) {
            var exponent = 7
            var expMask = 0x4000

            while ((pcm and expMask) == 0 && exponent > 0) {
                exponent--
                expMask = expMask shr 1
            }

            val mantissa = (pcm shr (exponent + 3)) and 0x0f
            (exponent shl 4) or mantissa
        } else {
            pcm shr 4
        }

        return (compressed xor mask).toByte()
    }

    private fun buildRtspUri(): String {
        val path = if (config.rtspPath.startsWith("/")) {
            config.rtspPath
        } else {
            "/${config.rtspPath}"
        }

        return "rtsp://${config.host}:${config.rtspPort}$path"
    }

    private data class RtspResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: String
    )

    private data class RtspSession(
        val baseUri: String,
        var nextCseq: Int = 1,
        var sessionId: String? = null,
        var auth: RtspAuth? = null
    )

    private enum class Codec {
        PCMU,
        PCMA
    }

    private data class BackchannelInfo(
        val controlUri: String,
        val payloadType: Int,
        val codec: Codec
    ) {
        val codecLabel: String
            get() = if (codec == Codec.PCMU) "G.711 μ-law" else "G.711 A-law"
    }

    private sealed class RtspAuth {
        abstract fun authorization(method: String, uri: String): String

        data class Basic(
            val username: String,
            val password: String
        ) : RtspAuth() {
            override fun authorization(method: String, uri: String): String {
                val token = Base64.encodeToString(
                    "$username:$password".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                return "Basic $token"
            }
        }

        data class Digest(
            val username: String,
            val password: String,
            val realm: String,
            val nonce: String,
            val qop: String?
        ) : RtspAuth() {
            private var nonceCount = 0

            override fun authorization(method: String, uri: String): String {
                nonceCount++

                val ha1 = md5("$username:$realm:$password")
                val ha2 = md5("$method:$uri")

                if (qop.equals("auth", ignoreCase = true)) {
                    val nc = nonceCount.toString(16).padStart(8, '0')
                    val cnonce = SecureRandom()
                        .nextLong()
                        .toString(16)
                        .replace("-", "")

                    val response = md5(
                        "$ha1:$nonce:$nc:$cnonce:auth:$ha2"
                    )

                    return "Digest username=\"$username\", " +
                        "realm=\"$realm\", " +
                        "nonce=\"$nonce\", " +
                        "uri=\"$uri\", " +
                        "response=\"$response\", " +
                        "qop=auth, nc=$nc, cnonce=\"$cnonce\""
                }

                val response = md5("$ha1:$nonce:$ha2")

                return "Digest username=\"$username\", " +
                    "realm=\"$realm\", " +
                    "nonce=\"$nonce\", " +
                    "uri=\"$uri\", " +
                    "response=\"$response\""
            }

            private fun md5(value: String): String =
                MessageDigest
                    .getInstance("MD5")
                    .digest(value.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
        }

        companion object {
            fun fromHeader(
                header: String?,
                username: String,
                password: String
            ): RtspAuth? {
                if (header.isNullOrBlank()) return null

                if (header.startsWith("Basic", ignoreCase = true)) {
                    return Basic(username, password)
                }

                if (!header.startsWith("Digest", ignoreCase = true)) {
                    return null
                }

                fun value(name: String): String? =
                    Regex(
                        """(?:^|,|\s)$name\s*=\s*(?:"([^"]*)"|([^,\s]+))""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(header.substringAfter("Digest"))
                        ?.let { match ->
                            match.groupValues[1]
                                .ifBlank { match.groupValues[2] }
                        }

                val realm = value("realm") ?: return null
                val nonce = value("nonce") ?: return null
                val qop = value("qop")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.firstOrNull {
                        it.equals("auth", ignoreCase = true)
                    }

                return Digest(
                    username = username,
                    password = password,
                    realm = realm,
                    nonce = nonce,
                    qop = qop
                )
            }
        }
    }

    companion object {
        private const val BACKCHANNEL_REQUIRE =
            "www.onvif.org/ver20/backchannel"

        private const val SAMPLE_RATE = 8_000
        private const val SAMPLES_PER_FRAME = 160
        private const val PCM_FRAME_BYTES = SAMPLES_PER_FRAME * 2
    }
}
