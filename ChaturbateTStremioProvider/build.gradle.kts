// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 1

cloudstream {
    language = "en"
    description = "Live Chaturbate Trans cams (Stremio bridge). Bridges the Chaturbate Stremio addon (chaturbate.stremio.homes/t) - Popular + region + Couples Live rows, genre rows, live search, full room metadata and direct LL-HLS stream links."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3 // beta until you are happy with it
    tvTypes = listOf("Live", "NSFW")

    iconUrl = "https://raw.githubusercontent.com/rhoulou/TotalGirls/main/chaturbate.png"
}
