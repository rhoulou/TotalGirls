// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 1

cloudstream {
    language = "en"
    description = "PornTube videos (NSFW). Bridges the PornTube Stremio addon (catalog/meta/stream) - New + genre rows, search, full metadata, plus direct and torrent stream links."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3 // beta until you are happy with it
    tvTypes = listOf("NSFW")

    iconUrl = "https://raw.githubusercontent.com/rhoulou/TotalGirls/main/ptube.png"
}
