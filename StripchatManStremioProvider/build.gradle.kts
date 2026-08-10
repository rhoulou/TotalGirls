// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 1

cloudstream {
    language = "en"
    description = "Live Stripchat Guys cams (Stremio bridge). Bridges the Stripchat Stremio addon (stripchat.stremio.homes/men) - Popular + New & Trending + Couples Live + VR Cams rows, genre rows, live search, full room metadata and direct HLS links via the saawsedge master playlist."
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

    iconUrl = "https://raw.githubusercontent.com/rhoulou/TotalGirls/main/stripchat.png"
}
