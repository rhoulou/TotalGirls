package com.example.chaturbate

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ChaturbateProviderPlugin : BasePlugin() {
    override fun load() {
        // Girls only (no Guys / Trans).
        registerMainAPI(ChaturbateProvider("f", "Chaturbate Girls"))
    }
}
