package com.rodrigohaynan.meucftv

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

class OnvifClient(
    private val config: CameraConfig
) {
    @Volatile
    private var session: Session? = null

    suspend fun moveFor(
        pan: Float,
        tilt: Float,
        durationMs: Long = 350
    ) {
        val current = ensureSession()

        withContext(Dispatchers.IO) {
            postSoap(
                url = current.ptzUrl,
                action = ACTION_CONTINUOUS_MOVE,
                tolerateClosedResponse = true,
                body = """
                    <tptz:ContinuousMove>
                        <tptz:ProfileToken>${xmlEscape(current.profileToken)}</tptz:ProfileToken>
                        <tptz:Velocity>
                            <tt:PanTilt x="$pan" y="$tilt" />
                        </tptz:Velocity>
                    </tptz:ContinuousMove>
                """.trimIndent()
            )
        }

        delay(durationMs)
        stop()
    }

    suspend fun stop() {
        val current = ensureSession()

        withContext(Dispatchers.IO) {
            postSoap(
                url = current.ptzUrl,
                action = ACTION_STOP,
                tolerateClosedResponse = true,
                body = """
                    <tptz:Stop>
                        <tptz:ProfileToken>${xmlEscape(current.profileToken)}</tptz:ProfileToken>
                        <tptz:PanTilt>true</tptz:PanTilt>
                        <tptz:Zoom>true</tptz:Zoom>
                    </tptz:Stop>
                """.trimIndent()
            )
        }
    }

    suspend fun testConnection(): String {
        val current = ensureSession()
        return "ONVIF conectado • PTZ disponível • perfil ${current.profileToken}"
    }

    private suspend fun ensureSession(): Session {
        session?.let { return it }

        return withContext(Dispatchers.IO) {
            session ?: createSession().also { session = it }
        }
    }

    private fun createSession(): Session {
        val deviceUrl =
            "http://${config.host}:${config.onvifPort}/onvif/device_service"

        val capabilitiesXml = postSoap(
            url = deviceUrl,
            action = ACTION_GET_CAPABILITIES,
            body = """
                <tds:GetCapabilities>
                    <tds:Category>All</tds:Category>
                </tds:GetCapabilities>
            """.trimIndent()
        )

        val mediaUrl = normalizeServiceUrl(
            extractServiceXAddr(
                capabilitiesXml,
                "Media"
            ),
            "/onvif/media_service"
        )

        val ptzUrl = normalizeServiceUrl(
            extractServiceXAddr(
                capabilitiesXml,
                "PTZ"
            ),
            "/onvif/ptz_service"
        )

        val profilesXml = postSoap(
            url = mediaUrl,
            action = ACTION_GET_PROFILES,
            body = "<trt:GetProfiles />"
        )

        val token = PROFILE_TOKEN_REGEX
            .find(profilesXml)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IOException("A câmera não retornou um token de perfil ONVIF.")

        return Session(
            mediaUrl = mediaUrl,
            ptzUrl = ptzUrl,
            profileToken = token
        )
    }

    private fun postSoap(
        url: String,
        action: String,
        body: String,
        tolerateClosedResponse: Boolean = false
    ): String {
        val envelope = soapEnvelope(body)
        val payload = envelope.toByteArray(Charsets.UTF_8)

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 7_000
            doOutput = true
            useCaches = false
            setRequestProperty("Connection", "close")
            setRequestProperty("Accept", "application/soap+xml, text/xml, */*")
            setRequestProperty("User-Agent", "MeuCFTV/0.1.2")
            setRequestProperty(
                "Content-Type",
                "application/soap+xml; charset=utf-8; action=\"$action\""
            )
            setFixedLengthStreamingMode(payload.size)
        }

        try {
            connection.outputStream.use {
                it.write(payload)
                it.flush()
            }

            val responseCode = try {
                connection.responseCode
            } catch (error: IOException) {
                if (tolerateClosedResponse) return ""
                throw error
            }

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = try {
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (error: IOException) {
                if (tolerateClosedResponse && responseCode in 200..299) {
                    ""
                } else {
                    throw error
                }
            }

            if (responseCode !in 200..299) {
                throw IOException(
                    "ONVIF HTTP $responseCode${if (response.isBlank()) "" else ": $response"}"
                )
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun soapEnvelope(body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope
            xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
            xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
            xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
            xmlns:tt="http://www.onvif.org/ver10/schema"
            xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
            xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
            <s:Header>
                ${securityHeader()}
            </s:Header>
            <s:Body>
                $body
            </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun securityHeader(): String {
        val nonce = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val created = Instant.now().toString()

        val digestInput = nonce +
            created.toByteArray(Charsets.UTF_8) +
            config.password.toByteArray(Charsets.UTF_8)

        val digest = MessageDigest
            .getInstance("SHA-1")
            .digest(digestInput)

        val digestBase64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        val nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP)

        return """
            <wsse:Security s:mustUnderstand="1">
                <wsse:UsernameToken>
                    <wsse:Username>${xmlEscape(config.onvifUser)}</wsse:Username>
                    <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestBase64</wsse:Password>
                    <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</wsse:Nonce>
                    <wsu:Created>$created</wsu:Created>
                </wsse:UsernameToken>
            </wsse:Security>
        """.trimIndent()
    }

    private fun extractServiceXAddr(
        xml: String,
        serviceName: String
    ): String? {
        val prefix = "(?:[A-Za-z0-9_]+:)?"
        val regex = Regex(
            "<$prefix$serviceName\\b[^>]*>.*?<$prefix" +
                "XAddr>([^<]+)</$prefix" +
                "XAddr>.*?</$prefix$serviceName>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun normalizeServiceUrl(
        advertisedUrl: String?,
        fallbackPath: String
    ): String {
        val path = runCatching {
            advertisedUrl
                ?.let { URL(it).path }
                ?.takeIf {
                    it.isNotBlank()
                }
        }.getOrNull()
            ?: fallbackPath

        return "http://${config.host}:${config.onvifPort}$path"
    }

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private data class Session(
        val mediaUrl: String,
        val ptzUrl: String,
        val profileToken: String
    )

    companion object {
        private val PROFILE_TOKEN_REGEX = Regex(
            """<(?:[A-Za-z0-9_]+:)?Profiles\b[^>]*\btoken=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        private const val ACTION_GET_CAPABILITIES =
            "http://www.onvif.org/ver10/device/wsdl/GetCapabilities"
        private const val ACTION_GET_PROFILES =
            "http://www.onvif.org/ver10/media/wsdl/GetProfiles"
        private const val ACTION_CONTINUOUS_MOVE =
            "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove"
        private const val ACTION_STOP =
            "http://www.onvif.org/ver20/ptz/wsdl/Stop"
    }
}
