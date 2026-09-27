package com.rodrigohaynan.meucftv

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

object RtspSdpProbe {

    enum class AudioCodec {
        AAC,
        PCMU,
        PCMA,
        UNKNOWN
    }

    data class AudioConfig(
        val codec: AudioCodec,
        val sampleRate: Int,
        val channels: Int,
        val codecConfig: ByteArray?
    )

    data class VideoConfig(
        val sps: ByteArray,
        val pps: ByteArray,
        val audio: AudioConfig?
    )

    fun probe(config: CameraConfig): VideoConfig {
        val uri = buildRtspUri(config)
        val socket = Socket().apply {
            connect(
                InetSocketAddress(config.host, config.rtspPort),
                5_000
            )
            soTimeout = 7_000
            tcpNoDelay = true
        }

        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        try {
            var cseq = 1
            var auth: RtspAuth? = null

            var response = request(
                input = input,
                output = output,
                method = "DESCRIBE",
                uri = uri,
                cseq = cseq++,
                auth = auth,
                extraHeaders = listOf(
                    "Accept" to "application/sdp"
                )
            )

            if (response.code == 401) {
                auth = RtspAuth.fromHeader(
                    response.headers["www-authenticate"],
                    config.rtspUser,
                    config.password
                ) ?: throw IOException(
                    "Autenticação RTSP não reconhecida"
                )

                response = request(
                    input = input,
                    output = output,
                    method = "DESCRIBE",
                    uri = uri,
                    cseq = cseq,
                    auth = auth,
                    extraHeaders = listOf(
                        "Accept" to "application/sdp"
                    )
                )
            }

            if (response.code !in 200..299) {
                throw IOException(
                    "DESCRIBE RTSP retornou ${response.code}"
                )
            }

            return parseStreamConfig(response.body)
                ?: throw IOException(
                    "SPS/PPS não encontrados no SDP da câmera"
                )
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseStreamConfig(
        sdp: String
    ): VideoConfig? {
        val normalized = sdp.replace("\r\n", "\n")

        val fmtpLine = normalized
            .lineSequence()
            .firstOrNull {
                it.startsWith(
                    "a=fmtp:",
                    ignoreCase = true
                ) &&
                    it.contains(
                        "sprop-parameter-sets",
                        ignoreCase = true
                    )
            }
            ?: return null

        val raw = fmtpLine
            .substringAfter(
                "sprop-parameter-sets=",
                ""
            )
            .substringBefore(';')
            .trim()

        if (raw.isBlank()) return null

        val parts = raw.split(',')
        if (parts.size < 2) return null

        val sps = Base64.decode(
            parts[0].trim(),
            Base64.DEFAULT
        )
        val pps = Base64.decode(
            parts[1].trim(),
            Base64.DEFAULT
        )

        if (sps.isEmpty() || pps.isEmpty()) {
            return null
        }

        return VideoConfig(
            sps = withStartCode(sps),
            pps = withStartCode(pps),
            audio = parseAudioConfig(normalized)
        )
    }

    private fun parseAudioConfig(
        sdp: String
    ): AudioConfig? {
        val lines = sdp.lines()
        var insideAudio = false
        var staticPayloadType: Int? = null
        var rtpMap: String? = null
        var fmtp: String? = null

        for (line in lines) {
            when {
                line.startsWith(
                    "m=audio ",
                    ignoreCase = true
                ) -> {
                    insideAudio = true
                    staticPayloadType = line
                        .trim()
                        .split(Regex("\\s+"))
                        .getOrNull(3)
                        ?.toIntOrNull()
                    rtpMap = null
                    fmtp = null
                }

                line.startsWith(
                    "m=",
                    ignoreCase = true
                ) && insideAudio -> {
                    break
                }

                insideAudio &&
                    line.startsWith(
                        "a=rtpmap:",
                        ignoreCase = true
                    ) -> {
                    rtpMap = line
                }

                insideAudio &&
                    line.startsWith(
                        "a=fmtp:",
                        ignoreCase = true
                    ) -> {
                    fmtp = line
                }
            }
        }

        val mapped = rtpMap?.let {
            Regex(
                """a=rtpmap:(\d+)\s+([^/]+)/([0-9]+)(?:/([0-9]+))?""",
                RegexOption.IGNORE_CASE
            ).find(it)
        }

        val codecName = mapped
            ?.groupValues
            ?.getOrNull(2)
            ?.uppercase(Locale.US)
            .orEmpty()

        val sampleRate = mapped
            ?.groupValues
            ?.getOrNull(3)
            ?.toIntOrNull()
            ?: when (staticPayloadType) {
                0, 8 -> 8_000
                else -> 8_000
            }

        val channels = mapped
            ?.groupValues
            ?.getOrNull(4)
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1

        val codec = when {
            codecName.contains("MPEG4-GENERIC") ||
                codecName == "AAC" -> {
                AudioCodec.AAC
            }

            codecName == "PCMU" ||
                staticPayloadType == 0 -> {
                AudioCodec.PCMU
            }

            codecName == "PCMA" ||
                staticPayloadType == 8 -> {
                AudioCodec.PCMA
            }

            else -> {
                AudioCodec.UNKNOWN
            }
        }

        val codecConfig =
            if (codec == AudioCodec.AAC) {
                fmtp
                    ?.substringAfter(
                        "config=",
                        ""
                    )
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.length >= 4 }
                    ?.let { hexToBytes(it) }
            } else {
                null
            }

        return AudioConfig(
            codec = codec,
            sampleRate = sampleRate,
            channels = channels,
            codecConfig = codecConfig
        )
    }

    private fun hexToBytes(
        hex: String
    ): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)

        for (i in out.indices) {
            out[i] = clean
                .substring(i * 2, i * 2 + 2)
                .toInt(16)
                .toByte()
        }

        return out
    }

    private fun withStartCode(
        payload: ByteArray
    ): ByteArray {
        if (
            payload.size >= 4 &&
            payload[0] == 0.toByte() &&
            payload[1] == 0.toByte() &&
            payload[2] == 0.toByte() &&
            payload[3] == 1.toByte()
        ) {
            return payload
        }

        val result = ByteArray(
            payload.size + 4
        )
        result[3] = 1
        payload.copyInto(
            result,
            4
        )
        return result
    }

    private fun request(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        method: String,
        uri: String,
        cseq: Int,
        auth: RtspAuth?,
        extraHeaders: List<Pair<String, String>>
    ): RtspResponse {
        val headers =
            mutableListOf<Pair<String, String>>(
                "CSeq" to cseq.toString(),
                "User-Agent" to "MeuCFTV/0.2.0"
            )

        auth
            ?.authorization(method, uri)
            ?.let {
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

        output.write(
            request.toByteArray(
                Charsets.UTF_8
            )
        )
        output.flush()

        return readResponse(input)
    }

    private fun readResponse(
        input: BufferedInputStream
    ): RtspResponse {
        val statusLine =
            readLine(input)
                ?: throw IOException(
                    "RTSP encerrou a conexão"
                )

        val code = statusLine
            .split(' ')
            .getOrNull(1)
            ?.toIntOrNull()
            ?: throw IOException(
                "Status RTSP inválido"
            )

        val headers =
            linkedMapOf<String, String>()

        while (true) {
            val line =
                readLine(input)
                    ?: throw IOException(
                        "Fim inesperado da resposta RTSP"
                    )

            if (line.isEmpty()) break

            val separator =
                line.indexOf(':')

            if (separator > 0) {
                headers[
                    line
                        .substring(
                            0,
                            separator
                        )
                        .trim()
                        .lowercase(
                            Locale.US
                        )
                ] = line
                    .substring(
                        separator + 1
                    )
                    .trim()
            }
        }

        val contentLength =
            headers["content-length"]
                ?.toIntOrNull()
                ?: 0

        val body =
            if (contentLength > 0) {
                val bytes =
                    ByteArray(
                        contentLength
                    )
                var total = 0

                while (
                    total < contentLength
                ) {
                    val read =
                        input.read(
                            bytes,
                            total,
                            contentLength - total
                        )

                    if (read < 0) {
                        throw IOException(
                            "SDP RTSP incompleto"
                        )
                    }

                    total += read
                }

                String(
                    bytes,
                    Charsets.UTF_8
                )
            } else {
                ""
            }

        return RtspResponse(
            code,
            headers,
            body
        )
    }

    private fun readLine(
        input: BufferedInputStream
    ): String? {
        val out =
            ByteArrayOutputStream()
        var previous = -1

        while (true) {
            val current = input.read()

            if (current < 0) {
                return if (
                    out.size() == 0
                ) {
                    null
                } else {
                    out.toString(
                        Charsets.UTF_8.name()
                    )
                }
            }

            if (
                previous == '\r'.code &&
                current == '\n'.code
            ) {
                val bytes =
                    out.toByteArray()

                val size =
                    if (
                        bytes.isNotEmpty() &&
                        bytes.last() ==
                        '\r'.code.toByte()
                    ) {
                        bytes.size - 1
                    } else {
                        bytes.size
                    }

                return String(
                    bytes,
                    0,
                    size,
                    Charsets.UTF_8
                )
            }

            out.write(current)
            previous = current
        }
    }

    private fun buildRtspUri(
        config: CameraConfig
    ): String {
        val path =
            if (
                config.rtspPath
                    .startsWith("/")
            ) {
                config.rtspPath
            } else {
                "/${config.rtspPath}"
            }

        return "rtsp://${config.host}:" +
            "${config.rtspPort}$path"
    }

    private data class RtspResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: String
    )

    private sealed class RtspAuth {
        abstract fun authorization(
            method: String,
            uri: String
        ): String

        data class Basic(
            val username: String,
            val password: String
        ) : RtspAuth() {
            override fun authorization(
                method: String,
                uri: String
            ): String {
                val token =
                    Base64.encodeToString(
                        "$username:$password"
                            .toByteArray(
                                Charsets.UTF_8
                            ),
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

            override fun authorization(
                method: String,
                uri: String
            ): String {
                nonceCount++

                val ha1 =
                    md5(
                        "$username:$realm:$password"
                    )
                val ha2 =
                    md5(
                        "$method:$uri"
                    )

                if (
                    qop.equals(
                        "auth",
                        ignoreCase = true
                    )
                ) {
                    val nc =
                        nonceCount
                            .toString(16)
                            .padStart(
                                8,
                                '0'
                            )

                    val cnonce =
                        SecureRandom()
                            .nextLong()
                            .toString(16)
                            .replace(
                                "-",
                                ""
                            )

                    val response =
                        md5(
                            "$ha1:$nonce:$nc:" +
                                "$cnonce:auth:$ha2"
                        )

                    return "Digest " +
                        "username=\"$username\", " +
                        "realm=\"$realm\", " +
                        "nonce=\"$nonce\", " +
                        "uri=\"$uri\", " +
                        "response=\"$response\", " +
                        "qop=auth, nc=$nc, " +
                        "cnonce=\"$cnonce\""
                }

                val response =
                    md5(
                        "$ha1:$nonce:$ha2"
                    )

                return "Digest " +
                    "username=\"$username\", " +
                    "realm=\"$realm\", " +
                    "nonce=\"$nonce\", " +
                    "uri=\"$uri\", " +
                    "response=\"$response\""
            }

            private fun md5(
                value: String
            ): String =
                MessageDigest
                    .getInstance("MD5")
                    .digest(
                        value.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                    .joinToString("") {
                        "%02x".format(it)
                    }
        }

        companion object {
            fun fromHeader(
                header: String?,
                username: String,
                password: String
            ): RtspAuth? {
                if (
                    header.isNullOrBlank()
                ) {
                    return null
                }

                if (
                    header.startsWith(
                        "Basic",
                        ignoreCase = true
                    )
                ) {
                    return Basic(
                        username,
                        password
                    )
                }

                if (
                    !header.startsWith(
                        "Digest",
                        ignoreCase = true
                    )
                ) {
                    return null
                }

                fun value(
                    name: String
                ): String? =
                    Regex(
                        """(?:^|,|\s)$name\s*=\s*(?:"([^"]*)"|([^,\s]+))""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            header.substringAfter(
                                "Digest"
                            )
                        )
                        ?.let { match ->
                            match
                                .groupValues[1]
                                .ifBlank {
                                    match.groupValues[2]
                                }
                        }

                val realm =
                    value("realm")
                        ?: return null
                val nonce =
                    value("nonce")
                        ?: return null

                val qop =
                    value("qop")
                        ?.split(',')
                        ?.map {
                            it.trim()
                        }
                        ?.firstOrNull {
                            it.equals(
                                "auth",
                                ignoreCase = true
                            )
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
}
