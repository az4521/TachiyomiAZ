//
//  SourceImageView.swift
//  Aidoku
//
//  Created by Skitty on 4/26/25.
//

import ExtensionRunner
import Foundation
import Nuke
import NukeUI
import SwiftUI

/// Disk cache for covers shown in browse and search results. These images are
/// intentionally small: a grid never needs the original source-sized artwork.
enum TransientCoverCache {
    static let maximumDiskUsage = 250 * 1024 * 1024
    static let maximumPixelWidth: CGFloat = 320

    private static let dataCache = try? DataCache(
        name: "app.tachiyomiaz.TachiyomiAZ.transient-covers"
    )

    static let pipeline: ImagePipeline = {
        ImagePipeline {
            let configuration = URLSessionConfiguration.default
            configuration.urlCache = nil
            var protocolClasses = configuration.protocolClasses ?? []
            protocolClasses.insert(JVMImageURLProtocol.self, at: 0)
            configuration.protocolClasses = protocolClasses
            let imageCache = ImageCache()
            imageCache.costLimit = 30 * 1024 * 1024
            $0.dataCache = dataCache
            $0.dataCachePolicy = .storeEncodedImages
            $0.dataLoader = DataLoader(configuration: configuration)
            $0.imageCache = imageCache
            $0.isStoringPreviewsInMemoryCache = false
        }
    }()

    static func clear() {
        dataCache?.removeAll()
        (pipeline.configuration.imageCache as? ImageCache)?.removeAll()
    }

    static var diskUsage: Int { dataCache?.totalSize ?? 0 }

    static func configure() {
        dataCache?.sizeLimit = maximumDiskUsage
    }
}

struct SourceImageView: View {
    var source: ExtensionRunner.Source?
    var sourceKey: String?

    let imageUrl: String
    var width: CGFloat?
    var height: CGFloat?
    var downsampleWidth: CGFloat?
    var useTransientCoverCache = false
    var contentMode: ContentMode = .fill
    var placeholder = "MangaPlaceholder"

    @State private var imageRequest: ImageRequest?

    var body: some View {
        LazyImage(
            request: imageRequest,
            transaction: .init(animation: .default)
        ) { state in
            if state.imageContainer?.type == .gif, let data = state.imageContainer?.data {
                GIFImage(
                    data: data,
                    contentMode: contentMode
                )
                    .frame(width: width, height: height)
                    .id(state.image != nil ? imageUrl : "placeholder") // ensures only opacity is animated
            } else {
                let result = if let image = state.image {
                    image
                } else {
                    Image(placeholder)
                }
                result
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
                    .frame(width: width, height: height)
                    .id(state.image != nil ? imageUrl : "placeholder") // ensures only opacity is animated
            }
        }
        .processors({
            if let downsampleWidth {
                [DownsampleProcessor(width: downsampleWidth)]
            } else {
                []
            }
        }())
        .pipeline(
            useTransientCoverCache
                ? TransientCoverCache.pipeline
                : ImagePipeline.shared
        )
        .task(id: ImageRequestIdentity(
            imageUrl: imageUrl,
            sourceKey: source?.key ?? sourceKey
        )) {
            imageRequest = nil
            await loadImageRequest(url: imageUrl)
        }
    }

    func loadImageRequest(url: String) async {
        let url = URL(string: url)
        if let fileUrl = url?.toAidokuFileUrl() {
            imageRequest = ImageRequest(url: fileUrl)
            return
        }
        guard let url, !url.isFileURL else {
            imageRequest = ImageRequest(url: url)
            return
        }

        var resolvedSource = source
        if resolvedSource == nil, let sourceKey {
            await SourceManager.shared.waitForSourcesLoad()
            resolvedSource = SourceManager.shared.source(for: sourceKey)
        }
        guard !Task.isCancelled else { return }

        let urlRequest = if let resolvedSource {
            await resolvedSource.getModifiedImageRequest(url: url, context: nil)
        } else {
            await ExtensionRunner.Source.modify(url: url, request: URLRequest(url: url))
        }
        guard !Task.isCancelled else { return }
        imageRequest = ImageRequest(urlRequest: urlRequest)
    }
}

private struct ImageRequestIdentity: Equatable {
    let imageUrl: String
    let sourceKey: String?
}
