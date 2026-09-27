# MeuCFTV

Aplicativo Android próprio para câmeras IP compatíveis com RTSP e ONVIF.

## MVP v0.1

O primeiro objetivo é reproduzir no app as funções já validadas na câmera:

- vídeo ao vivo por RTSP;
- stream H.264;
- controle PTZ por ONVIF;
- configuração separada de usuário RTSP e ONVIF;
- armazenamento local criptografado da senha;
- suporte à permissão de rede local do Android 17+.

## Tecnologias

- Kotlin
- Jetpack Compose
- AndroidX Media3 / RTSP
- ONVIF via SOAP
- Android Keystore para proteção das credenciais

## Segurança

Não coloque senha da câmera, IP público, DDNS ou credenciais reais no repositório. A configuração da câmera é feita dentro do aplicativo e a senha é criptografada no aparelho.

> Observação: este repositório foi criado como público. Se a intenção for manter o projeto privado, altere a visibilidade nas configurações do GitHub antes de adicionar informações internas.

## Desenvolvimento

O projeto usa Android Gradle Plugin 9.4.0, Java 17, `compileSdk 37`, Compose BOM 2026.09.00 e Media3 1.11.1.

O wrapper binário do Gradle ainda não está versionado; o CI instala Gradle 9.6.0 diretamente. Ao abrir localmente no Android Studio, gere o wrapper uma vez com:

```bash
gradle wrapper --gradle-version 9.6.0
```

Depois:

```bash
./gradlew :app:assembleDebug
```

## Roadmap

### v0.1
Vídeo ao vivo + PTZ + configuração segura da câmera.

### v0.2
Snapshot e gravação manual no aparelho.

### v0.3
NVR local, gravação contínua/eventos, retenção e histórico.

### v0.4
Acesso remoto seguro sem expor diretamente as portas RTSP/ONVIF à internet.
