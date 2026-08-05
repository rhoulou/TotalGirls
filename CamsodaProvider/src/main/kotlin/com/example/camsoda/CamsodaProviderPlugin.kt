package com.example.camsoda

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CamsodaProviderPlugin : BasePlugin() {
    override fun load() {
        // Single provider listing camsoda female cams via the lemoncams API
        // (All Female + the aggregator's category / hair color rows).
        registerMainAPI(CamsodaProvider())
    }
}
