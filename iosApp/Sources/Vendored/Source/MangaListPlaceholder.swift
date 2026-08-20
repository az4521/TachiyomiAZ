//
//  MangaListPlaceholder.swift
//  Aidoku
//

import SwiftUI

struct MangaListPlaceholder: View {
    var showTitle = true
    var itemCount = 5

    var body: some View {
        VStack(alignment: .leading) {
            if showTitle {
                Text("Loading")
                    .font(.title3)
                    .fontWeight(.semibold)
                    .padding(.horizontal)
            }
            Self.mainView(itemCount: itemCount)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .redacted(reason: .placeholder)
        .shimmering()
    }

    static func mainView(itemCount: Int) -> some View {
        LazyVStack {
            ForEach(0..<itemCount, id: \.self) { _ in
                HStack {
                    Rectangle()
                        .fill(Color(.secondarySystemFill))
                        .frame(width: 100 * 2/3, height: 100)
                        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Loading Title")
                        Text("Loading").foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.horizontal)
            }
        }
    }
}
