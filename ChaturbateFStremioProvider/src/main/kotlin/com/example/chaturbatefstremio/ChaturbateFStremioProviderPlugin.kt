package com.example.chaturbatefstremio

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ChaturbateFStremioProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ChaturbateStremioProvider())
    }
}
