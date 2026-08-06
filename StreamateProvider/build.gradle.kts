// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 13

cloudstream {
    language = "en"
    description = "Live Streamate cams. Scrapes streamate.com directly from the phone - /v4 guest gateway API for the live-now feed, keyword rows and name search (autocomplete), plus the manifest-server HLS stream passed to the player."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3 // beta until you are happy with it
    tvTypes = listOf("Live")

    iconUrl = "https://raw.githubusercontent.com/rhoulou/TotalGirls/main/streamate.png"
}
