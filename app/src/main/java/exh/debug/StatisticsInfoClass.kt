package exh.debug

data class StatisticsInfoClass(
    var mangaCount: Int = 0,
    var completedMangaCount: Int = 0,
    var startedMangaCount: Int = 0,
    var localMangaCount: Int = 0,
    var totalChapterCount: Int = 0,
    var readChapterCount: Int = 0,
    var downloadedChapterCount: Int = 0,
    var trackedMangaCount: Int = 0,
    var meanMangaScore: Double = Double.NaN
)
