package com.rodrigohaynan.meucftv

import android.content.Context

class HubPreferences(
    context: Context
) {
    private val prefs =
        context.getSharedPreferences(
            "meucftv_hub",
            Context.MODE_PRIVATE
        )

    fun isEnabled(): Boolean =
        prefs.getBoolean(
            KEY_ENABLED,
            false
        )

    fun setEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(
                KEY_ENABLED,
                enabled
            )
            .apply()
    }

    companion object {
        private const val KEY_ENABLED =
            "enabled"
    }
}
