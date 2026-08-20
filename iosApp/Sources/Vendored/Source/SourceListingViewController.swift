//
//  SourceListingViewController.swift
//  Aidoku
//
//  Created by Skitty on 12/27/24.
//

import SwiftUI
import ExtensionRunner

class SourceListingViewController: MangaListViewController {
    init(source: ExtensionRunner.Source, listing: ExtensionRunner.Listing) {
        super.init(
            source: source,
            title: listing.name,
            listingKind: listing.kind
        )
        self.getEntries = { page in
            try await source.getMangaList(listing: listing, page: page)
        }
    }
}
