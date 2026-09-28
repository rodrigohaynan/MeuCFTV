package com.rodrigohaynan.meucftv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HubBootReceiver :
    BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (
            intent.action !=
            Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        val enabled =
            HubPreferences(context)
                .isEnabled()

        val configured =
            SecureCameraStore(context)
                .load()
                .isConfigured

        if (
            enabled &&
            configured
        ) {
            runCatching {
                context
                    .startForegroundService(
                        Intent(
                            context,
                            HubService::class.java
                        ).setAction(
                            HubService
                                .ACTION_START
                        )
                    )
            }
        }
    }
}
