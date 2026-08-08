package com.example.mestrip

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MestripProviderPlugin : Plugin() {
    companion object {
        @Volatile var appContext: Context? = null
            private set
    }

    init {
        openSettings = { ctx -> Settings.showSettings(ctx) }
    }

    override fun load(context: Context) {
        appContext = context
        registerMainAPI(MestripProvider())
    }
}
