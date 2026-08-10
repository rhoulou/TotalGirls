package com.example.viralxxxporn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ViralXxxProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ViralXxxProvider())
    }
}
