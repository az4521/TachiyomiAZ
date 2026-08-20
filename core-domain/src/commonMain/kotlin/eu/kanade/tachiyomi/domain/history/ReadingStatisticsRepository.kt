package eu.kanade.tachiyomi.domain.history

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * Reading statistics derived from the shared history and chapter tables.
 *
 * The database retains one history row per chapter, so a "page" here means a read chapter. This
 * mirrors the Android statistics screen; there is no historical page-count column to report.
 */
class ReadingStatisticsRepository(private val db: DatabaseHandler) {
    /**
     * Produces all platform-neutral statistics from one consistent view of the database.
     *
     * [timeZoneId] belongs to the caller rather than the database: a read close to midnight must
     * be assigned to the same local day on Android and iOS when they render the statistics.
     */
    fun snapshot(nowEpochMilliseconds: Long, timeZoneId: String): ReadingStatistics {
        val timeZone = runCatching { TimeZone.of(timeZoneId) }
            .getOrElse { TimeZone.currentSystemDefault() }
        val events = readEvents(timeZone)
        val today = Instant.fromEpochMilliseconds(nowEpochMilliseconds).toLocalDateTime(timeZone).date
        val thisMonth = events.filter { it.day.year == today.year && it.day.monthNumber == today.monthNumber }
        val thisYear = events.filter { it.day.year == today.year }

        return ReadingStatistics(
            basic = ReadingBasicStatistics(
                pagesTotal = events.size,
                pagesMonth = thisMonth.size,
                pagesYear = thisYear.size,
                seriesTotal = events.map { it.mangaId }.toSet().size,
                seriesMonth = thisMonth.map { it.mangaId }.toSet().size,
                seriesYear = thisYear.map { it.mangaId }.toSet().size,
                hoursTotal = events.sumOf { it.seconds } / SECONDS_PER_HOUR,
                hoursMonth = thisMonth.sumOf { it.seconds } / SECONDS_PER_HOUR,
                hoursYear = thisYear.sumOf { it.seconds } / SECONDS_PER_HOUR,
            ),
            streaks = streaks(events.map { it.day }.toSet(), today),
            yearlyChapters = yearlyChapterCounts(events),
            dailyChapters = dailyChapterCounts(events, timeZone),
        )
    }

    private fun readEvents(timeZone: TimeZone): List<ReadEvent> {
        val mangaIdByChapterId = db.getAllChapters()
            .mapNotNull { chapter -> chapter.id?.let { id -> chapter.manga_id?.let { mangaId -> id to mangaId } } }
            .toMap()

        return db.getAllHistory().mapNotNull { history ->
            val mangaId = mangaIdByChapterId[history.chapter_id] ?: return@mapNotNull null
            if (history.last_read <= 0) return@mapNotNull null
            ReadEvent(
                day = Instant.fromEpochMilliseconds(history.last_read).toLocalDateTime(timeZone).date,
                seconds = history.time_read / MILLIS_PER_SECOND,
                mangaId = mangaId,
            )
        }
    }

    private fun streaks(days: Set<LocalDate>, today: LocalDate): ReadingStreaks {
        val sortedDays = days.sorted()
        if (sortedDays.isEmpty()) return ReadingStreaks(current = 0, longest = 0)

        var longest = 1
        var run = 1
        for (index in 1 until sortedDays.size) {
            run = if (sortedDays[index - 1].toEpochDays() + 1 == sortedDays[index].toEpochDays()) run + 1 else 1
            longest = maxOf(longest, run)
        }

        val current = if (today.toEpochDays() - sortedDays.last().toEpochDays() <= 1) run else 0
        return ReadingStreaks(current = current, longest = longest.takeIf { it >= 2 } ?: 0)
    }

    private fun yearlyChapterCounts(events: List<ReadEvent>): List<YearlyReadingChapterCounts> =
        events.groupBy { it.day.year }
            .map { (year, yearEvents) ->
                val months = MutableList(MONTHS_PER_YEAR) { 0 }
                yearEvents.forEach { months[it.day.monthNumber - 1]++ }
                YearlyReadingChapterCounts(year = year, monthCounts = months)
            }
            .sortedBy { it.year }

    private fun dailyChapterCounts(
        events: List<ReadEvent>,
        timeZone: TimeZone,
    ): List<DailyReadingChapterCount> =
        events.groupingBy { it.day }
            .eachCount()
            .map { (day, chapters) ->
                DailyReadingChapterCount(
                    dayStartEpochMilliseconds = day.atStartOfDayIn(timeZone).toEpochMilliseconds(),
                    chapters = chapters,
                )
            }
            .sortedBy { it.dayStartEpochMilliseconds }

    private data class ReadEvent(
        val day: LocalDate,
        val seconds: Long,
        val mangaId: Long,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val SECONDS_PER_HOUR = 3_600L
        const val MONTHS_PER_YEAR = 12
    }
}

/** Aggregate totals used by the reading-statistics summary. */
data class ReadingBasicStatistics(
    val pagesTotal: Int,
    val pagesMonth: Int,
    val pagesYear: Int,
    val seriesTotal: Int,
    val seriesMonth: Int,
    val seriesYear: Int,
    val hoursTotal: Long,
    val hoursMonth: Long,
    val hoursYear: Long,
)

/** Current and all-time consecutive-day reading streaks. */
data class ReadingStreaks(
    val current: Int,
    val longest: Int,
)

/** Per-month chapter counts for one calendar year; [monthCounts] is January through December. */
data class YearlyReadingChapterCounts(
    val year: Int,
    val monthCounts: List<Int>,
)

/** Chapters read on one local calendar day. */
data class DailyReadingChapterCount(
    val dayStartEpochMilliseconds: Long,
    val chapters: Int,
)

/** Complete shared input for a platform's reading-statistics presentation layer. */
data class ReadingStatistics(
    val basic: ReadingBasicStatistics,
    val streaks: ReadingStreaks,
    val yearlyChapters: List<YearlyReadingChapterCounts>,
    val dailyChapters: List<DailyReadingChapterCount>,
)
