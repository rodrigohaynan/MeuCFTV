package com.rodrigohaynan.meucftv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HubService : Service() {

    private val running =
        AtomicBoolean(false)

    private var rtspRelay:
        TcpRelayServer? = null

    private var onvifRelay:
        TcpRelayServer? = null

    private var wakeLock:
        PowerManager.WakeLock? = null

    private var wifiLock:
        WifiManager.WifiLock? = null

    private var intentionalStop =
        false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (
            intent?.action ==
            ACTION_STOP
        ) {
            intentionalStop = true

            HubPreferences(this)
                .setEnabled(false)

            stopHub()
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
            stopSelf()

            return START_NOT_STICKY
        }

        intentionalStop = false

        startForegroundCompat()

        if (
            running.compareAndSet(
                false,
                true
            )
        ) {
            startHub()
        }

        HubPreferences(this)
            .setEnabled(true)

        return START_STICKY
    }

    override fun onDestroy() {
        stopHub()

        if (!intentionalStop) {
            // Keep the preference enabled so Android can restart a sticky Hub.
            HubPreferences(this)
                .setEnabled(true)
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    private fun startHub() {
        acquireLocks()

        rtspRelay = TcpRelayServer(
            listenPort =
                RTSP_PROXY_PORT,
            targetProvider = {
                val config =
                    SecureCameraStore(this)
                        .load()

                config.host to
                    config.rtspPort
            }
        ).also {
            it.start()
        }

        onvifRelay = TcpRelayServer(
            listenPort =
                ONVIF_PROXY_PORT,
            targetProvider = {
                val config =
                    SecureCameraStore(this)
                        .load()

                config.host to
                    config.onvifPort
            }
        ).also {
            it.start()
        }

        updateNotification()
    }

    private fun stopHub() {
        running.set(false)

        rtspRelay?.close()
        rtspRelay = null

        onvifRelay?.close()
        onvifRelay = null

        runCatching {
            if (
                wakeLock?.isHeld == true
            ) {
                wakeLock?.release()
            }
        }

        wakeLock = null

        runCatching {
            if (
                wifiLock?.isHeld == true
            ) {
                wifiLock?.release()
            }
        }

        wifiLock = null
    }

    private fun acquireLocks() {
        runCatching {
            val power =
                getSystemService(
                    POWER_SERVICE
                ) as PowerManager

            wakeLock =
                power.newWakeLock(
                    PowerManager
                        .PARTIAL_WAKE_LOCK,
                    "MeuCFTV:HubWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }

        runCatching {
            val wifi =
                applicationContext
                    .getSystemService(
                        WIFI_SERVICE
                    ) as WifiManager

            wifiLock =
                wifi.createWifiLock(
                    WifiManager
                        .WIFI_MODE_FULL_HIGH_PERF,
                    "MeuCFTV:HubWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun startForegroundCompat() {
        val notification =
            buildNotification()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun updateNotification() {
        val manager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            buildNotification()
        )
    }

    private fun buildNotification():
        Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                1,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val stopIntent =
            PendingIntent.getService(
                this,
                2,
                Intent(
                    this,
                    HubService::class.java
                ).setAction(
                    ACTION_STOP
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val remote =
            HubNetworkInfo
                .bestRemoteAddress()

        val text =
            if (remote != null) {
                "Ativo • $remote • RTSP $RTSP_PROXY_PORT • ONVIF $ONVIF_PROXY_PORT"
            } else {
                "Ativo • aguardando rede privada/Tailscale"
            }

        return Notification
            .Builder(
                this,
                CHANNEL_ID
            )
            .setSmallIcon(
                android.R.drawable
                    .presence_video_online
            )
            .setContentTitle(
                "MeuCFTV Hub"
            )
            .setContentText(text)
            .setContentIntent(
                openIntent
            )
            .setOngoing(true)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        "Parar Hub",
                        stopIntent
                    )
                    .build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "MeuCFTV Hub",
                    NotificationManager
                        .IMPORTANCE_LOW
                ).apply {
                    description =
                        "Gateway remoto da câmera"
                    setShowBadge(false)
                }

            val manager =
                getSystemService(
                    NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private class TcpRelayServer(
        private val listenPort: Int,
        private val targetProvider:
            () -> Pair<String, Int>
    ) : Closeable {

        private val active =
            AtomicBoolean(false)

        private val executor =
            Executors.newCachedThreadPool()

        @Volatile
        private var server:
            ServerSocket? = null

        fun start() {
            if (
                !active.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            executor.execute {
                val localServer =
                    ServerSocket()

                server = localServer

                try {
                    localServer.reuseAddress =
                        true

                    localServer.bind(
                        InetSocketAddress(
                            "0.0.0.0",
                            listenPort
                        )
                    )

                    while (
                        active.get()
                    ) {
                        val client =
                            localServer.accept()

                        executor.execute {
                            bridge(client)
                        }
                    }
                } catch (_: Exception) {
                    // Closing the ServerSocket is the normal stop path.
                } finally {
                    runCatching {
                        localServer.close()
                    }
                }
            }
        }

        private fun bridge(
            client: Socket
        ) {
            val target =
                targetProvider()

            if (
                target.first.isBlank()
            ) {
                runCatching {
                    client.close()
                }
                return
            }

            val upstream =
                Socket()

            try {
                client.tcpNoDelay = true
                client.keepAlive = true

                upstream.tcpNoDelay = true
                upstream.keepAlive = true

                upstream.connect(
                    InetSocketAddress(
                        target.first,
                        target.second
                    ),
                    7_000
                )

                val closed =
                    AtomicBoolean(false)

                fun closeBoth() {
                    if (
                        closed.compareAndSet(
                            false,
                            true
                        )
                    ) {
                        runCatching {
                            client.close()
                        }

                        runCatching {
                            upstream.close()
                        }
                    }
                }

                executor.execute {
                    try {
                        client
                            .getInputStream()
                            .copyTo(
                                upstream
                                    .getOutputStream(),
                                64 * 1024
                            )

                        upstream
                            .getOutputStream()
                            .flush()
                    } catch (_: Exception) {
                    } finally {
                        closeBoth()
                    }
                }

                try {
                    upstream
                        .getInputStream()
                        .copyTo(
                            client
                                .getOutputStream(),
                            64 * 1024
                        )

                    client
                        .getOutputStream()
                        .flush()
                } catch (_: Exception) {
                } finally {
                    closeBoth()
                }
            } catch (_: Exception) {
                runCatching {
                    client.close()
                }
                runCatching {
                    upstream.close()
                }
            }
        }

        override fun close() {
            active.set(false)

            runCatching {
                server?.close()
            }

            server = null

            executor.shutdownNow()
        }
    }

    companion object {
        const val ACTION_START =
            "com.rodrigohaynan.meucftv.HUB_START"

        const val ACTION_STOP =
            "com.rodrigohaynan.meucftv.HUB_STOP"

        const val RTSP_PROXY_PORT =
            8554

        const val ONVIF_PROXY_PORT =
            8500

        private const val CHANNEL_ID =
            "meucftv_hub"

        private const val NOTIFICATION_ID =
            4040
    }
}
