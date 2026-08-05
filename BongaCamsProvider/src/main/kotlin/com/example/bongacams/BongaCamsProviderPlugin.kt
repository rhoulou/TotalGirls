package com.example.bongacams

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BongaCamsProviderPlugin : BasePlugin() {
    override fun load() {
        // Single provider listing bongacams female cams via the lemoncams API
        // (All Female + the aggregator's category / hair color / HD / age rows).
        registerMainAPI(BongaCamsProvider())
    }
}
