import SwiftUI
import TachiJVMRunner

/// A minimal vertical reader.
///
/// Page fetching is the source's job -- `getPageList` returns whatever the extension resolves, and
/// some sources hand back an image URL directly while others return a page URL that needs a second
/// resolution step. Both shapes are handled here; anything else is shown as an error rather than a
/// blank page, because a silently empty reader is indistinguishable from a broken source.
struct ReaderView: View {
    let source: SourceDescriptor
    let manga: TachiyomiXManga
    let chapter: TachiyomiXChapter

    @EnvironmentObject private var runtime: SourceRuntime
    @EnvironmentObject private var history: HistoryStore

    @State private var pages: [TachiyomiXPage] = []
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        Group {
            if isLoading {
                ProgressView("Loading pages\u{2026}")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error {
                VStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 34))
                        .foregroundStyle(.orange)
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Button("Retry") { Task { await load() } }
                        .buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if pages.isEmpty {
                Text("This chapter has no pages.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(Array(pages.enumerated()), id: \.offset) { _, page in
                            pageView(page)
                        }
                    }
                }
                .background(Color.black)
            }
        }
        .navigationTitle(chapter.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await load()
            await history.record(manga: manga, chapter: chapter, source: source)
        }
    }

    @ViewBuilder
    private func pageView(_ page: TachiyomiXPage) -> some View {
        // imageURL when the source resolved it; uri when it handed back a local or data URI.
        let address = page.imageURL ?? page.uri ?? page.url
        if let url = URL(string: address), !address.isEmpty {
            AsyncImage(url: url) { phase in
                switch phase {
                case let .success(image):
                    image.resizable().scaledToFit()
                case .failure:
                    Color.gray.opacity(0.2).frame(height: 220).overlay {
                        Image(systemName: "photo").foregroundStyle(.secondary)
                    }
                case .empty:
                    Color.gray.opacity(0.1).frame(height: 320).overlay {
                        ProgressView()
                    }
                @unknown default:
                    EmptyView()
                }
            }
        } else {
            Color.gray.opacity(0.2).frame(height: 120).overlay {
                Text("Page \(page.index + 1) has no image").font(.caption)
            }
        }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            pages = try await runtime.pages(
                source,
                chapterURL: chapter.url,
                chapterName: chapter.name,
                memo: chapter.memo
            )
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }
}
