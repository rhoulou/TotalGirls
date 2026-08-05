package com.example.bongacams

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BongaCamsProviderPlugin : BasePlugin() {
    override fun load() {
        // Single provider mirroring the bongacams.com female categories
        // (All Female + the 18 listing categories - male and trans tabs skipped).
        registerMainAPI(BongaCamsProvider())
    }
}
