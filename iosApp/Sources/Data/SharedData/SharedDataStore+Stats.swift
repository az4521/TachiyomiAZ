import Foundation
import TachiyomiKit

/// Reading statistics, derived from the shared `history` table.
///
/// Upstream computes these from a `ReadingSession` entity that records every stretch of reading.
/// The shared schema has no such table -- Android keeps one `history` row per chapter, holding when
/// it was last read (`last_read`) and how long was spent on it (`time_read`) -- so the numbers here
/// are derived from that instead. This is what the Android stats screen reports, which is the
/// behaviour being matched.
///
/// One consequence worth naming: pages read is not recorded historically, so the "pages" figures
/// count *chapters* read. Reporting a page count the database cannot support would be a guess.
extension SharedDataStore {
    struct BasicStats {
        var pagesTotal: Int = 0
        var pagesMonth: Int = 0
        var pagesYear: Int = 0
        var seriesTotal: Int = 0
        var seriesMonth: Int = 0
        var seriesYear: Int = 0
        var hoursTotal: Int = 0
        var hoursMonth: Int = 0
        var hoursYear: Int = 0
    }

    private func readingStatistics() -> ReadingStatistics {
        ReadingStatisticsRepository(db: handler).snapshot(
            nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1_000),
            timeZoneId: TimeZone.current.identifier
        )
    }

    func getBasicStats(context: Any? = nil) -> BasicStats {
        let basic = readingStatistics().basic

        return BasicStats(
            pagesTotal: Int(basic.pagesTotal),
            pagesMonth: Int(basic.pagesMonth),
            pagesYear: Int(basic.pagesYear),
            seriesTotal: Int(basic.seriesTotal),
            seriesMonth: Int(basic.seriesMonth),
            seriesYear: Int(basic.seriesYear),
            hoursTotal: Int(basic.hoursTotal),
            hoursMonth: Int(basic.hoursMonth),
            hoursYear: Int(basic.hoursYear)
        )
    }

    /// Consecutive days with at least one chapter read. The current streak only counts if it runs
    /// up to today or yesterday, so an old streak does not read as ongoing.
    func getStreakLengths(context: Any? = nil) -> (current: Int, longest: Int) {
        let streaks = readingStatistics().streaks
        return (current: Int(streaks.current), longest: Int(streaks.longest))
    }

    /// Chapters read per month, grouped by year, as the yearly chart renders it.
    func getChapterYearlyReadingData(context: Any? = nil) -> [YearlyMonthData] {
        readingStatistics().yearlyChapters.map { year in
            let counts = year.monthCounts.map { Int(truncating: $0 as NSNumber) }
            precondition(counts.count == 12, "Shared statistics must provide every month")
            return YearlyMonthData(
                year: Int(year.year),
                data: MonthData(
                    january: counts[0], february: counts[1], march: counts[2],
                    april: counts[3], may: counts[4], june: counts[5],
                    july: counts[6], august: counts[7], september: counts[8],
                    october: counts[9], november: counts[10], december: counts[11]
                )
            )
        }
    }

    /// Chapters read per day over the trailing year, in the heatmap's day grid.
    func getReadingHeatmapData(context: Any? = nil) -> HeatmapData {
        let (totalDays, startDate) = HeatmapData.getDaysAndStartDate()
        var values = [Int](repeating: 0, count: totalDays)
        let calendar = Calendar.current
        for day in readingStatistics().dailyChapters {
            let date = Date(timeIntervalSince1970: TimeInterval(day.dayStartEpochMilliseconds) / 1_000)
            guard let offset = calendar.dateComponents([.day], from: startDate, to: date).day,
                  offset >= 0, offset < totalDays else { continue }
            values[offset] += Int(day.chapters)
        }
        return HeatmapData(startDate: startDate, values: values)
    }
}
