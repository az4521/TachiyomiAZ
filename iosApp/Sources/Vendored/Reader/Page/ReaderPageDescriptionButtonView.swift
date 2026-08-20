//
//  ReaderPageDescriptionButtonView.swift
//  Aidoku
//
//  Created by Skitty on 5/20/25.
//

import ExtensionRunner
import MarkdownUI
import SwiftUI

struct ReaderPageDescriptionButtonView: View {
    let source: ExtensionRunner.Source?
    let pages: [ExtensionRunner.Page]

    @State private var showingDescription = false

    var body: some View {
        Button {
            showingDescription = true
        } label: {
            if showingDescription {
                Image(systemName: "info.circle.fill")
            } else {
                Image(systemName: "info.circle")
            }
        }
        .padding()
        .padding(.bottom, 2) // extra padding on the bottom
        .sheet(isPresented: $showingDescription) {
            if #available(iOS 16.0, *) {
                PageDescriptionView(source: source, pages: pages)
                    .presentationDetents([.medium, .large])
            } else {
                PageDescriptionView(source: source, pages: pages)
            }
        }
    }
}

struct PageDescriptionView: View {
    let source: ExtensionRunner.Source?
    let pages: [ExtensionRunner.Page]

    @Environment(\.dismiss) private var dismiss

    @State private var description: String?
    @State private var error: Error?

    var body: some View {
        PlatformNavigationStack {
            Group {
                if let error {
                    ErrorView(error: error) {
                        self.error = nil
                        await loadDescription()
                    }
                } else {
                    ScrollView(.vertical) {
                        if let description {
                            Markdown(description)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding()
                        }
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    CloseButton {
                        dismiss()
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            await loadDescription()
        }
    }

    func loadDescription() async {
        do {
            description = try await pages
                .asyncCompactMap { (page: ExtensionRunner.Page) async throws -> String? in
                    if let description = page.description {
                        description
                    } else if page.hasDescription {
                        if let source {
                            try await source.getPageDescription(page: page)
                        } else  {
                            NSLocalizedString("UNAVAILABLE")
                        }
                    } else {
                        nil
                    }
                }
                .joined(separator: "\n\n")
        } catch {
            withAnimation {
                self.error = error
            }
        }
    }
}
