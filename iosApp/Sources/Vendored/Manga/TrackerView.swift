//
//  TrackerView.swift
//  Aidoku (iOS)
//
//  Created by Skitty on 6/26/22.
//

import ExtensionRunner
import SafariServices
import SwiftUI
import UIKit

struct TrackerView: View {
    let tracker: Tracker
    let item: TrackItem
    let info: TrackerInfo
    let manga: ExtensionRunner.Manga

    @Binding var refresh: Bool

    @State var state: TrackState?
    @State var update = TrackUpdate()

    @State var score: Float?
    @State var scoreOption: Int?
    @State var statusOption: Int?
    @State var lastReadChapter: Float?
    @State var lastReadVolume: Float?
    @State var startReadDate: Date?
    @State var finishReadDate: Date?

    @State var stateUpdated = false

    @State var safariUrl: URL?
    @State var showSafari = false
    @State var chapterOffset = 0

    /// Hoisted out of `body`: resolving these inline pushed the type checker past its limit.
    private var trackerIcon: UIImage {
        tracker.icon ?? .mangaPlaceholder
    }

    private var totalChapters: Float? {
        state?.totalChapters.map(Float.init)
    }

    private var totalVolumes: Float? {
        state?.totalVolumes.map(Float.init)
    }

    /// The status at a picker index, or nil if the index is out of range. Written out because the
    /// original one-liner mixed `??` and `?:` in a way the type checker could not solve in time.
    /// A picker's float score as the tracker stores it. The nested ternary this replaces was
    /// another expression the type checker would not solve in time.
    private func scoreValue(from value: Float?) -> Int? {
        guard let value else { return nil }
        return info.scoreType == .tenPointDecimal ? Int(value * 10) : Int(value)
    }

    private func status(at index: Int?) -> TrackStatus? {
        guard let index, index >= 0, index < info.supportedStatuses.count else { return nil }
        return info.supportedStatuses[index]
    }

    /// Split out of `body`: the header, the options grid and the onChange chain together
    /// exceeded what the type checker will solve in one expression.
    @ViewBuilder
    private var headerRow: some View {
        HStack {
            Image(uiImage: trackerIcon)
                .resizable()
                .frame(width: 44, height: 44, alignment: .leading)
                .cornerRadius(10)
                .padding(.trailing, 2)
            Text(item.title ?? "")
                .lineLimit(1)
            Spacer()
            Menu {
                Button {
                    Task {
                        await TrackerManager.shared.removeTrackItem(item: item)
                        stateUpdated = false
                        withAnimation {
                            refresh.toggle()
                        }
                    }
                } label: {
                    Label(
                        NSLocalizedString("STOP_TRACKING", comment: ""),
                        systemImage: "xmark"
                    )
                }
                Button {
                    Task {
                        safariUrl = await tracker.getUrl(trackId: item.id)
                        guard safariUrl != nil else { return }
                        showSafari = true
                    }
                } label: {
                    Label(
                        NSLocalizedString("VIEW_ON_WEBSITE", comment: ""),
                        systemImage: "safari"
                    )
                }
                Button {
                    NotificationCenter.default.post(name: .syncTrackItem, object: item)
                } label: {
                    Label(
                        NSLocalizedString("SYNC_LOCAL_HISTORY", comment: ""),
                        systemImage: "clock.arrow.circlepath"
                    )
                }
                Menu {
                    Button {
                        showOffsetPicker()
                    } label: {
                        Label(
                            NSLocalizedString("SET_TRACK_CHAPTER_OFFSET"),
                            systemImage: "number"
                        )
                    }
                } label: {
                    Label(
                        NSLocalizedString("ADVANCED"),
                        systemImage: "slider.horizontal.3"
                    )
                }
            } label: {
                Image(systemName: "ellipsis")
                    .padding([.vertical, .leading]) // increase hitbox
            }
        }
    }

    @ViewBuilder
    private var optionsGrid: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104))], spacing: 12) {
            TrackerSettingOptionView(
                NSLocalizedString("STATUS", comment: ""),
                type: .menu,
                options: info.supportedStatuses.map { $0.toString() },
                selectedOption: $statusOption
            )
            TrackerSettingOptionView(
                NSLocalizedString("CHAPTERS", comment: ""),
                type: .counter,
                count: $lastReadChapter,
                total: Binding.constant(totalChapters)
            )
            TrackerSettingOptionView(
                NSLocalizedString("VOLUMES", comment: ""),
                type: .counter,
                count: $lastReadVolume,
                total: Binding.constant(totalVolumes)
            )
            TrackerSettingOptionView(NSLocalizedString("STARTED", comment: ""), type: .date, date: $startReadDate)
            TrackerSettingOptionView(NSLocalizedString("FINISHED", comment: ""), type: .date, date: $finishReadDate)

            switch info.scoreType {
                case .tenPoint:
                    TrackerSettingOptionView(NSLocalizedString("SCORE"), count: $score, total: Binding.constant(10))
                case .hundredPoint:
                    TrackerSettingOptionView(NSLocalizedString("SCORE"), count: $score, total: Binding.constant(100))
                case .tenPointDecimal:
                    TrackerSettingOptionView(NSLocalizedString("SCORE"), count: $score, total: Binding.constant(10), numberType: .float)
                case .optionList:
                    TrackerSettingOptionView(
                        NSLocalizedString("SCORE"),
                        type: .menu,
                        options: info.scoreOptions.map { $0.0 },
                        selectedOption: $scoreOption
                    )
            }
        }
    }

    /// Split from `body`: eight chained onChange handlers plus the content exceeded what
    /// the type checker will solve in one expression, so the chain is applied in two stages.
    private var contentWithScoreHandlers: some View {
        VStack {
            headerRow
            optionsGrid
        }
        .padding([.top, .horizontal])
        // handle state updates
        .onChange(of: score) { newValue in
            let new = scoreValue(from: newValue)
            guard state?.score != new else { return }
            state?.score = new
            update.score = new
            stateUpdated = true
        }
        .onChange(of: scoreOption) { newValue in
            let new = newValue.flatMap { info.scoreOptions[safe: $0]?.1 }
            guard state?.score != new else { return }
            state?.score = new
            update.score = new
            stateUpdated = true
        }
        .onChange(of: statusOption) { newValue in
            let new = status(at: newValue)
            guard state?.status != new else { return }
            if new == .completed || new == .dropped {
                finishReadDate = Date()
            }
            state?.status = new
            update.status = new
            stateUpdated = true
        }
    }

    var body: some View {
        contentWithScoreHandlers
        .onChange(of: lastReadChapter) { newValue in
            guard state?.lastReadChapter != newValue else { return }
            state?.lastReadChapter = newValue
            update.lastReadChapter = newValue
            stateUpdated = true
        }
        .onChange(of: lastReadVolume) { newValue in
            let new = newValue != nil ? Int(floor(newValue!)) : nil
            guard state?.lastReadVolume != new else { return }
            state?.lastReadVolume = new
            update.lastReadVolume = new
            stateUpdated = true
        }
        .onChange(of: startReadDate) { newValue in
            guard state?.startReadDate != newValue else { return }
            state?.startReadDate = newValue
            update.startReadDate = newValue == nil ? Date(timeIntervalSince1970: 0) : newValue
            stateUpdated = true
        }
        .onChange(of: finishReadDate) { newValue in
            guard state?.finishReadDate != newValue else { return }
            state?.finishReadDate = newValue
            update.finishReadDate = newValue == nil ? Date(timeIntervalSince1970: 0) : newValue
            stateUpdated = true
        }
        // fetch latest tracker state
        .task {
            state = try? await tracker.getState(trackId: item.id)
            guard let state else { return }

            let newScoreOption: Int?
            if info.scoreType == .optionList {
                let option = await tracker.option(for: state.score ?? 0, options: info.scoreOptions)
                newScoreOption = info.scoreOptions
                    .firstIndex { $0.0 == option }
                    .flatMap {
                        info.supportedStatuses.distance(
                            from: info.supportedStatuses.startIndex,
                            to: $0
                        )
                    }
            } else {
                newScoreOption = scoreOption
            }

            withAnimation {
                chapterOffset = item.chapterOffset
                score = state.score != nil ? info.scoreType == .tenPointDecimal ? Float(state.score!) / 10 : Float(state.score!) : nil
                scoreOption = newScoreOption
                statusOption = info.supportedStatuses
                    .firstIndex { $0.rawValue == state.status?.rawValue }
                    .flatMap {
                        info.supportedStatuses.distance(
                            from: info.supportedStatuses.startIndex,
                            to: $0
                        )
                    } ?? 0
                lastReadChapter = state.lastReadChapter != nil ? Float(state.lastReadChapter!) : nil
                lastReadVolume = state.lastReadVolume != nil ? Float(state.lastReadVolume!) : nil
                startReadDate = state.startReadDate
                finishReadDate = state.finishReadDate
            }
        }
        // send tracker updated state
        .onDisappear {
            if stateUpdated {
                Task {
                    do {
                        try await tracker.update(trackId: item.id, update: update)

                        // The remote tracker is not the database. The editor used to send the
                        // request successfully but never copy the accepted values into the
                        // shared `manga_sync` row, so reopening the title (or restoring it on
                        // Android) showed the old state. Persist only after the remote update
                        // succeeds, matching the tracker as the source of truth.
                        if let state {
                            SharedDataStore.shared.setTrackState(
                                trackerId: item.trackerId,
                                sourceId: manga.sourceKey,
                                mangaId: manga.key,
                                state: state
                            )
                        }

                        // refresh page read history in case the tracker updated it
                        if let tracker = tracker as? PageTracker {
                            await TrackerManager.shared.syncPageTrackerHistory(
                                tracker: tracker,
                                manga: manga,
                                chapters: manga.chapters
                            )
                        }
                    } catch {
                        LogManager.logger.error("Failed to update tracker \(tracker.id): \(error)")
                    }
                }
            }
        }
        .sheet(isPresented: $showSafari) {
            SafariView(url: $safariUrl)
        }
    }

    func showOffsetPicker() {
        let maxValue = 500
        let minValue = -500
        let coordinator = TrackerOffsetPickerCoordinator(minValue: minValue, maxValue: maxValue)
        coordinator.pickerView.selectRow(chapterOffset - minValue, inComponent: 0, animated: false)

        let alert = UIAlertController(
            title: NSLocalizedString("SET_TRACK_CHAPTER_OFFSET"),
            message: NSLocalizedString("TRACK_CHAPTER_OFFSET_TEXT") + "\n\n\n\n\n\n\n\n",
            preferredStyle: .alert
        )

        alert.view.addSubview(coordinator.pickerView)

        coordinator.pickerView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            coordinator.pickerView.centerXAnchor.constraint(equalTo: alert.view.centerXAnchor),
            coordinator.pickerView.centerYAnchor.constraint(equalTo: alert.view.centerYAnchor, constant: 35),
            coordinator.pickerView.widthAnchor.constraint(equalToConstant: 250),
            coordinator.pickerView.heightAnchor.constraint(equalToConstant: 140)
        ])

        alert.addAction(UIAlertAction(title: NSLocalizedString("OK"), style: .default) { _ in
            let selectedRow = coordinator.pickerView.selectedRow(inComponent: 0)
            let value = minValue + selectedRow
            guard value != chapterOffset else { return }
            chapterOffset = value
            Task {
                await TrackerManager.shared.setTrackChapterOffset(item: item, chapterOffset: value)
            }
        })
        alert.addAction(UIAlertAction(title: NSLocalizedString("CANCEL"), style: .cancel, handler: nil))

        (UIApplication.shared.delegate as? AppDelegate)?.visibleViewController?.present(alert, animated: true)
    }
}

private final class TrackerOffsetPickerCoordinator: NSObject, UIPickerViewDelegate, UIPickerViewDataSource {
    let minValue: Int
    let maxValue: Int
    let pickerView = UIPickerView(frame: CGRect(x: 10, y: 48, width: 250, height: 140))

    init(minValue: Int, maxValue: Int) {
        self.minValue = minValue
        self.maxValue = maxValue
        super.init()
        pickerView.delegate = self
        pickerView.dataSource = self
    }

    func numberOfComponents(in pickerView: UIPickerView) -> Int {
        1
    }

    func pickerView(_ pickerView: UIPickerView, numberOfRowsInComponent component: Int) -> Int {
        maxValue - minValue + 1
    }

    func pickerView(_ pickerView: UIPickerView, titleForRow row: Int, forComponent component: Int) -> String? {
        String(minValue + row)
    }
}
