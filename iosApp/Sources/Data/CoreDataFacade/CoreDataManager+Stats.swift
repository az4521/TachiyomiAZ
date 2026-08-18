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
extension CoreDataManager {
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

    /// A chapter that has been read, with when it was read and for how long.
    private struct ReadEvent {
        let date: Date
        let seconds: Int64
        let mangaId: Int64
    }

    private func readEvents() -> [ReadEvent] {
        var byChapterId: [Int64: DbChapter] = [:]
        for chapter in handler.getAllChapters() {
            guard let id = chapter.id?.int64Value else { continue }
            byChapterId[id] = chapter
        }
        return handler.getAllHistory().compactMap { history in
            guard history.last_read > 0, let chapter = byChapterId[history.chapter_id] else { return nil }
            guard let mangaId = chapter.manga_id?.int64Value else { return nil }
            return ReadEvent(
                date: Date(timeIntervalSince1970: TimeInterval(history.last_read) / 1000),
                seconds: history.time_read / 1000,
                mangaId: mangaId
            )
        }
    }

    func getBasicStats(context: Any? = nil) -> BasicStats {
        let events = readEvents()
        let calendar = Calendar.current
        let now = Date()
        let month = events.filter { calendar.isDate($0.date, equalTo: now, toGranularity: .month) }
        let year = events.filter { calendar.isDate($0.date, equalTo: now, toGranularity: .year) }

        func hours(_ slice: [ReadEvent]) -> Int { Int(slice.reduce(0) { $0 + $1.seconds } / 3600) }
        func series(_ slice: [ReadEvent]) -> Int { Set(slice.map(\.mangaId)).count }

        return BasicStats(
            pagesTotal: events.count,
            pagesMonth: month.count,
            pagesYear: year.count,
            seriesTotal: series(events),
            seriesMonth: series(month),
            seriesYear: series(year),
            hoursTotal: hours(events),
            hoursMonth: hours(month),
            hoursYear: hours(year)
        )
    }

    /// Consecutive days with at least one chapter read. The current streak only counts if it runs
    /// up to today or yesterday, so an old streak does not read as ongoing.
    func getStreakLengths(context: Any? = nil) -> (current: Int, longest: Int) {
        let calendar = Calendar.current
        let days = Set(readEvents().map { calendar.startOfDay(for: $0.date) }).sorted()
        guard !days.isEmpty else { return (0, 0) }

        var longest = 1
        var run = 1
        for index in 1..<max(days.count, 1) {
            let gap = calendar.dateComponents([.day], from: days[index - 1], to: days[index]).day ?? 0
            run = gap == 1 ? run + 1 : 1
            longest = max(longest, run)
        }

        let today = calendar.startOfDay(for: Date())
        let sinceLast = calendar.dateComponents([.day], from: days[days.count - 1], to: today).day ?? .max
        let current = sinceLast <= 1 ? run : 0

        return (current, longest >= 2 ? longest : 0)
    }

    /// Chapters read per month, grouped by year, as the yearly chart renders it.
    func getChapterYearlyReadingData(context: Any? = nil) -> [YearlyMonthData] {
        let calendar = Calendar.current
        let byYear = Dictionary(grouping: readEvents()) { calendar.component(.year, from: $0.date) }
        return byYear
            .map { year, events in
                var counts = [Int](repeating: 0, count: 12)
                for event in events {
                    let month = calendar.component(.month, from: event.date)
                    counts[month - 1] += 1
                }
                return YearlyMonthData(
                    year: year,
                    data: MonthData(
                        january: counts[0], february: counts[1], march: counts[2],
                        april: counts[3], may: counts[4], june: counts[5],
                        july: counts[6], august: counts[7], september: counts[8],
                        october: counts[9], november: counts[10], december: counts[11]
                    )
                )
            }
            .sorted { $0.year < $1.year }
    }

    /// Chapters read per day over the trailing year, in the heatmap's day grid.
    func getReadingHeatmapData(context: Any? = nil) -> HeatmapData {
        let calendar = Calendar.current
        let (totalDays, startDate) = HeatmapData.getDaysAndStartDate()
        var values = [Int](repeating: 0, count: totalDays)
        for event in readEvents() {
            let day = calendar.startOfDay(for: event.date)
            guard let offset = calendar.dateComponents([.day], from: startDate, to: day).day,
                  offset >= 0, offset < totalDays else { continue }
            values[offset] += 1
        }
        return HeatmapData(startDate: startDate, values: values)
    }
}
