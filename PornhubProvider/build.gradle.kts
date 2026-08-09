// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 15

cloudstream {
    language = "en"
    description = "Pornhub videos (NSFW). Scrapes pornhub.com directly from the phone - home rows and search from the /video listing plus viewkey-page mediaDefinitions HLS/mp4 extraction."
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

    iconUrl = "https://raw.githubusercontent.com/rhoulou/TotalGirls/main/pornhub.png"
}
