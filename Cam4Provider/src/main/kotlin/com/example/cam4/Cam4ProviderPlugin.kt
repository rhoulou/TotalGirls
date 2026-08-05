package com.example.cam4

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Cam4ProviderPlugin : BasePlugin() {
    override fun load() {
        // Single provider mirroring the cam4.com tabs
        // (All / Female / Couples - trans and male rows are skipped).
        registerMainAPI(Cam4Provider())
    }
}
