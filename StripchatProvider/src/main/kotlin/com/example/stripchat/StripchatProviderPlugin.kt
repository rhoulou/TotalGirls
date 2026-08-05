package com.example.stripchat

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class StripchatProviderPlugin : BasePlugin() {
    override fun load() {
        // Girls only (no Guys / Trans).
        registerMainAPI(StripchatProvider("f", "Stripchat Girls"))
    }
}
