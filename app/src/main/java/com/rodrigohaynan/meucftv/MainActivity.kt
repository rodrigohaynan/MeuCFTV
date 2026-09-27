package com.rodrigohaynan.meucftv

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        volumeControlStream = AudioManager.STREAM_MUSIC

        setContent {
            MaterialTheme {
                MeuCftvApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeuCftvApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SecureCameraStore(context) }
    val streamController = remember { CameraStreamController() }
    val audioManager = remember {
        context.getSystemService(AudioManager::class.java)
    }

    var config by remember { mutableStateOf(store.load()) }
    var showSettings by remember { mutableStateOf(!config.isConfigured) }
    var status by remember { mutableStateOf("Pronto") }
    var audioDetected by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var muted by remember {
        mutableStateOf(audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
    }
    var volumePercent by remember {
        mutableStateOf(getVolumePercent(audioManager))
    }

    val scope = rememberCoroutineScope()
    val onvif = remember(
        config.host,
        config.onvifPort,
        config.onvifUser,
        config.password
    ) {
        OnvifClient(config)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (streamController.isRecording()) {
                streamController.stopRecording { }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MeuCFTV") },
                actions = {
                    OutlinedButton(
                        onClick = { showSettings = !showSettings },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(if (showSettings) "Fechar" else "Configurar")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (showSettings) {
                CameraSettings(
                    current = config,
                    onSave = { newConfig ->
                        if (recording) {
                            streamController.stopRecording {
                                status = it
                            }
                            recording = false
                        }

                        store.save(newConfig)
                        config = newConfig
                        showSettings = false
                        audioDetected = false
                        status = "Configuração salva"
                    }
                )
            }

            if (config.isConfigured) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        contentAlignment = Alignment.Center
                    ) {
                        RtspPlayer(
                            config = config,
                            controller = streamController,
                            onAudioDetected = {
                                audioDetected = true
                            },
                            onStatus = {
                                status = it
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Text(
                    text = config.name,
                    style = MaterialTheme.typography.titleLarge
                )

                CameraMediaControls(
                    audioDetected = audioDetected,
                    muted = muted,
                    volumePercent = volumePercent,
                    recording = recording,
                    onVolumeDown = {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI
                        )
                        muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                        volumePercent = getVolumePercent(audioManager)
                    },
                    onToggleMute = {
                        val direction = if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                            AudioManager.ADJUST_UNMUTE
                        } else {
                            AudioManager.ADJUST_MUTE
                        }

                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            direction,
                            AudioManager.FLAG_SHOW_UI
                        )

                        muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                        volumePercent = getVolumePercent(audioManager)
                    },
                    onVolumeUp = {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                        muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                        volumePercent = getVolumePercent(audioManager)
                    },
                    onSnapshot = {
                        streamController.captureSnapshot(context) {
                            status = it
                        }
                    },
                    onToggleRecording = {
                        if (recording) {
                            streamController.stopRecording {
                                status = it
                            }
                            recording = false
                        } else {
                            streamController.startRecording(context) {
                                status = it
                            }
                            recording = true
                        }
                    }
                )

                PtzControls(
                    onMove = { pan, tilt ->
                        scope.launch {
                            status = "Movendo câmera..."
                            runCatching {
                                onvif.moveFor(pan = pan, tilt = tilt)
                            }.onSuccess {
                                status = "PTZ OK"
                            }.onFailure {
                                status = "Falha PTZ: ${it.message ?: "erro desconhecido"}"
                            }
                        }
                    },
                    onTest = {
                        scope.launch {
                            status = "Testando ONVIF..."
                            runCatching {
                                onvif.testConnection()
                            }.onSuccess {
                                status = it
                            }.onFailure {
                                status = "Falha ONVIF: ${it.message ?: "erro desconhecido"}"
                            }
                        }
                    }
                )

                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            "Cadastre a câmera para iniciar",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Use o IP local da câmera, a porta RTSP, a porta ONVIF " +
                                "e a senha definida na conexão NVR."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraMediaControls(
    audioDetected: Boolean,
    muted: Boolean,
    volumePercent: Int,
    recording: Boolean,
    onVolumeDown: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeUp: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleRecording: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Áudio e mídia",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                if (audioDetected) {
                    "Áudio da câmera detectado • volume $volumePercent%"
                } else {
                    "Aguardando áudio da câmera • volume $volumePercent%"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(onClick = onVolumeDown) {
                    Text("Vol −")
                }

                FilledTonalButton(onClick = onToggleMute) {
                    Text(if (muted) "Ativar som" else "Mudo")
                }

                FilledTonalButton(onClick = onVolumeUp) {
                    Text("Vol +")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onSnapshot) {
                    Text("Foto")
                }

                Button(onClick = onToggleRecording) {
                    Text(if (recording) "Parar REC" else "Gravar")
                }
            }

            if (recording) {
                Text(
                    "● REC",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

private fun getVolumePercent(audioManager: AudioManager): Int {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        .coerceAtLeast(1)
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    return ((current.toFloat() / max.toFloat()) * 100f).toInt()
}

@Composable
private fun PtzControls(
    onMove: (Float, Float) -> Unit,
    onTest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Controle PTZ", style = MaterialTheme.typography.titleMedium)

            FilledTonalButton(onClick = { onMove(0f, 0.45f) }) {
                Text("▲")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { onMove(-0.45f, 0f) }) {
                    Text("◀")
                }
                FilledTonalButton(onClick = { onMove(0f, -0.45f) }) {
                    Text("▼")
                }
                FilledTonalButton(onClick = { onMove(0.45f, 0f) }) {
                    Text("▶")
                }
            }

            OutlinedButton(onClick = onTest) {
                Text("Testar ONVIF")
            }
        }
    }
}

@Composable
private fun CameraSettings(
    current: CameraConfig,
    onSave: (CameraConfig) -> Unit
) {
    var draft by remember(current) { mutableStateOf(current) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Configuração da câmera",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Nome") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = draft.host,
                onValueChange = { draft = draft.copy(host = it.trim()) },
                label = { Text("IP / Host") },
                placeholder = { Text("192.168.x.x") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.rtspPort.toString(),
                    onValueChange = {
                        draft = draft.copy(
                            rtspPort = it.toIntOrNull() ?: draft.rtspPort
                        )
                    },
                    label = { Text("RTSP") },
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = draft.onvifPort.toString(),
                    onValueChange = {
                        draft = draft.copy(
                            onvifPort = it.toIntOrNull() ?: draft.onvifPort
                        )
                    },
                    label = { Text("ONVIF") },
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = draft.rtspPath,
                onValueChange = { draft = draft.copy(rtspPath = it) },
                label = { Text("Caminho RTSP") },
                placeholder = { Text("/onvif1") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = draft.rtspUser,
                onValueChange = { draft = draft.copy(rtspUser = it) },
                label = { Text("Usuário RTSP") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = draft.onvifUser,
                onValueChange = { draft = draft.copy(onvifUser = it) },
                label = { Text("Usuário ONVIF") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = { Text("Senha NVR") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { onSave(draft) },
                enabled = draft.host.isNotBlank() && draft.password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Salvar câmera")
            }
        }
    }
}
