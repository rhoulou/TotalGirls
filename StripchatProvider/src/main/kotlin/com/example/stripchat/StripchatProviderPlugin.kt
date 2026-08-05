package com.example.stripchat

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class StripchatProviderPlugin : BasePlugin() {
    override fun load() {
        // One provider per target, mirroring the configure page
        // (f = Girls, m = Guys, t = Trans).
        registerMainAPI(StripchatProvider("f", "Stripchat Girls"))
        registerMainAPI(StripchatProvider("m", "Stripchat Guys"))
        registerMainAPI(StripchatProvider("t", "Stripchat Trans"))
    }
}
