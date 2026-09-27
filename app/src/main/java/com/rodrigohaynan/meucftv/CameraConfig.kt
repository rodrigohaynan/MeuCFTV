package com.rodrigohaynan.meucftv

data class CameraConfig(
    val name: String = "Garagem",
    val host: String = "",
    val rtspPort: Int = 554,
    val onvifPort: Int = 5000,
    val rtspPath: String = "/onvif1",
    val rtspUser: String = "admin",
    val onvifUser: String = "administrator",
    val password: String = ""
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && password.isNotBlank()
}
