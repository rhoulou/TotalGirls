package com.example.cam4

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Cam4ProviderPlugin : BasePlugin() {
    override fun load() {
        // Single provider mirroring the cam4.com female category rows
        // (New / Teen / MILF / Babe / Mature / Petite / Skinny / BBW /
        // Asian / Black-Ebony / Latina-Hispanic / White).
        registerMainAPI(Cam4Provider())
    }
}
