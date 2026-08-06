package com.example.stripchat

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StripchatProviderPlugin : Plugin() {
    companion object {
        @Volatile var appContext: Context? = null
            private set
    }

    init {
        openSettings = { ctx -> Settings.showSettings(ctx) }
    }

    override fun load(context: Context) {
        appContext = context
        // One provider entry per enabled gender (Girls / Guys / Trans); the
        // Couples row lives inside every entry. Gender changes need a plugin
        // reload to re-register the entries.
        val labels = mapOf("f" to "Girls", "m" to "Guys", "t" to "Trans")
        Settings.genders().forEach { g ->
            labels[g]?.let { label -> registerMainAPI(StripchatProvider(g, "Stripchat $label")) }
        }
    }
}
