package com.rodrigohaynan.meucftv

enum class TalkbackCodec(
    val displayName: String
) {
    PCM16("PCM 16-bit"),
    G711A("G.711 A-law"),
    G711U("G.711 μ-law")
}

data class CameraConfig(
    val name: String = "Garagem",
    val host: String = "",
    val rtspPort: Int = 554,
    val onvifPort: Int = 5000,
    val rtspPath: String = "/onvif1",
    val rtspUser: String = "admin",
    val onvifUser: String = "administrator",
    val password: String = "",
    val talkbackCodec: TalkbackCodec = TalkbackCodec.PCM16
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && password.isNotBlank()
}
