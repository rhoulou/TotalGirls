package com.example.camsoda

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CamsodaProviderPlugin : BasePlugin() {
    override fun load() {
        // Single provider mirroring the camsoda.com girls categories
        // (All Girls + the 18 curated category rows - male and trans tabs skipped).
        registerMainAPI(CamsodaProvider())
    }
}
