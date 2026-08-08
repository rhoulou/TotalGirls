// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 13

cloudstream {
    language = "en"
    description = "Live Sinparty cams (Girls). Scrapes api.sinparty.com directly from the phone - web-rtc listing plus userHash/Streamate/profile HLS resolution."
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

    iconUrl = "https://raw.githubusercontent.com/rhoulou/TotalGirls/main/sinparty.png"
}
