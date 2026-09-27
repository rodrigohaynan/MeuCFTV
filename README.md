# MeuCFTV

Aplicativo Android próprio para câmeras IP compatíveis com RTSP e ONVIF.

## MVP v0.1

O primeiro objetivo é reproduzir no app as funções já validadas na câmera:

- vídeo ao vivo por RTSP;
- stream H.264;
- controle PTZ por ONVIF;
- configuração separada de usuário RTSP e ONVIF;
- armazenamento local criptografado da senha.

## Tecnologias

- Kotlin
- Jetpack Compose
- AndroidX Media3 / RTSP
- ONVIF via SOAP
- Android Keystore para proteção das credenciais

## Segurança

Não coloque senha da câmera, IP público, DDNS ou credenciais reais no repositório. A configuração da câmera é feita dentro do aplicativo e a senha é criptografada no aparelho.

> Observação: este repositório está público. Se a intenção for manter o projeto privado, altere a visibilidade nas configurações do GitHub antes de adicionar informações internas.

## Desenvolvimento

O MVP inicial usa Android Gradle Plugin 9.0.1, Java 17, `compileSdk 36`, `targetSdk 36`, Compose BOM 2026.02.01 e Media3 1.11.1.

O alvo 36 foi escolhido para a primeira versão porque o aparelho de teste usa Android 16 e, nesse alvo, o acesso à LAN continua coberto pela permissão `INTERNET`. Quando migrarmos o app para `targetSdk 37`, adicionaremos o fluxo de permissão `ACCESS_LOCAL_NETWORK` do Android 17.

O wrapper binário do Gradle ainda não está versionado; o CI instala Gradle 9.1.0 diretamente. Ao abrir localmente no Android Studio, gere o wrapper uma vez com:

```bash
gradle wrapper --gradle-version 9.1.0
```

Depois:

```bash
./gradlew :app:assembleDebug
```

## Configuração esperada da câmera

A tela do aplicativo aceita:

- IP/host local da câmera;
- porta RTSP (normalmente 554);
- porta ONVIF (no equipamento testado, 5000);
- caminho RTSP (no equipamento testado, `/onvif1`);
- usuário RTSP;
- usuário ONVIF;
- senha NVR.

Nenhuma credencial real fica no código-fonte.

## Roadmap

### v0.1
Vídeo ao vivo + PTZ + configuração segura da câmera.

### v0.2
Snapshot e gravação manual no aparelho.

### v0.3
NVR local, gravação contínua/eventos, retenção e histórico.

### v0.4
Acesso remoto seguro sem expor diretamente as portas RTSP/ONVIF à internet.
