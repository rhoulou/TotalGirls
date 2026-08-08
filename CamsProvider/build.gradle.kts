// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 13

cloudstream {
    language = "en"
    description = "Live Cams.com cams (Girls). Scrapes cams.com directly from the phone - __NEXT_DATA__ wonStore listing, camshls HLS master passed to the player."
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

    iconUrl = "https://raw.githubusercontent.com/rhoulou/TotalGirls/main/cams.png"
}
