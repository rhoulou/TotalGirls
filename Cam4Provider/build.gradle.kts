// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 13

cloudstream {
    language = "en"
    description = "Live Cam4 cams. Scrapes cam4.com directly from the phone - GraphQL female category rows (New/Teen/MILF/Babe/Mature/Petite/Skinny/BBW/Asian/Black-Ebony/Latina-Hispanic/White), directory + streamInfo logic, xcdnpro HLS master passed to the player."
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

    iconUrl = "https://rhoulou.github.io/cloudstream/cam4.png"
}
