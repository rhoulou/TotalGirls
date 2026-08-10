package com.example.stripchatgirlsstremio

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StripchatGirlsStremioProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StripchatStremioProvider())
    }
}
