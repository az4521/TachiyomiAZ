//
//  ReaderChapterListView.swift
//  Aidoku (iOS)
//
//  Created by Skitty on 12/20/22.
//

import SwiftUI
import ExtensionRunner

struct ReaderChapterListView: View {
    var chapterList: [ExtensionRunner.Chapter]
    @State var chapter: ExtensionRunner.Chapter
    var chapterSet: ((ExtensionRunner.Chapter) -> Void)?

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        PlatformNavigationStack {
            ScrollViewReader { proxy in
                List(chapterList) { chapter in
                    Button {
                        self.chapter = chapter
                        chapterSet?(chapter)
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(displayString(for: chapter))
                                    .foregroundColor(.primary)
                                    .font(.subheadline)
                                if let title = chapter.title, chapter.chapterNumber != nil || chapter.volumeNumber != nil {
                                    Text(title)
                                        .foregroundColor(.secondary)
                                        .font(.subheadline)
                                }
                            }
                            Spacer()
                            if chapter == self.chapter {
                                Image(systemName: "checkmark")
                                    .foregroundColor(.appAccent)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                    .id(chapter.id)
                }
                .onAppear {
                    proxy.scrollTo(chapter.id, anchor: .center)
                }
            }
            .navigationTitle(NSLocalizedString("CHAPTERS", comment: ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    CloseButton {
                        dismiss()
                    }
                }
            }
        }
    }

    private func displayString(for chapter: ExtensionRunner.Chapter) -> String {
        if let chapterNum = chapter.chapterNumber {
            if let volumeNum = chapter.volumeNumber {
                String(
                    format: NSLocalizedString("VOL_X", comment: "") + " " + NSLocalizedString("CH_X", comment: ""),
                    volumeNum,
                    chapterNum
                )
            } else {
                String(format: NSLocalizedString("CHAPTER_X", comment: ""), chapterNum)
            }
        } else if let volumeNum = chapter.volumeNumber {
            String(format: NSLocalizedString("VOLUME_X", comment: ""), volumeNum)
        } else {
            chapter.title ?? ""
        }
    }
}
