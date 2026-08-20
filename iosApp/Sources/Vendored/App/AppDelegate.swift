//
//  AppDelegate.swift
//  Aidoku (iOS)
//
//  Created by Skitty on 12/29/21.
//

import ExtensionRunner
import CloudKit
import Nuke
import SwiftUI
import UserNotifications

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    static let isSideloaded = true

    private var networkObserverId: UUID?

    private lazy var loadingAlert: UIAlertController = {
        let loadingAlert = UIAlertController(
            title: nil,
            message: NSLocalizedString("LOADING_ELLIPSIS"),
            preferredStyle: .alert
        )
        progressView.tintColor = loadingAlert.view.tintColor
        loadingAlert.view.addSubview(progressView)
        loadingAlert.view.addSubview(loadingIndicator)

        progressView.translatesAutoresizingMaskIntoConstraints = false
        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false

        let progressViewSidePadding: CGFloat
        let progressViewBottomPadding: CGFloat
        let indicatorViewSidePadding: CGFloat
        if #available(iOS 26.0, *) {
            progressViewSidePadding = 32
            progressViewBottomPadding = 16
            indicatorViewSidePadding = 16
        } else {
            progressViewSidePadding = 16
            progressViewBottomPadding = 8
            indicatorViewSidePadding = 10
        }

        NSLayoutConstraint.activate([
            progressView.centerXAnchor.constraint(equalTo: loadingAlert.view.centerXAnchor),
            progressView.bottomAnchor.constraint(equalTo: loadingAlert.view.bottomAnchor, constant: -progressViewBottomPadding),
            progressView.widthAnchor.constraint(equalTo: loadingAlert.view.widthAnchor, constant: -(progressViewSidePadding * 2)),

            loadingIndicator.centerYAnchor.constraint(equalTo: loadingAlert.view.centerYAnchor),
            loadingIndicator.leadingAnchor.constraint(equalTo: loadingAlert.view.leadingAnchor, constant: indicatorViewSidePadding),
            loadingIndicator.widthAnchor.constraint(equalToConstant: 50),
            loadingIndicator.heightAnchor.constraint(equalToConstant: 50)
        ])
        return loadingAlert
    }()

    private lazy var loadingIndicator: UIActivityIndicatorView = {
        let loadingIndicator = UIActivityIndicatorView(frame: .zero)
        loadingIndicator.style = .medium
        loadingIndicator.tag = 3
        return loadingIndicator
    }()

    private lazy var progressView: UIProgressView = {
        let progressView = UIProgressView(frame: .zero)
        progressView.progress = 0
        return progressView
    }()

    var indicatorProgress: Float {
        get { progressView.progress }
        set { progressView.progress = newValue }
    }

    var navigationController: UINavigationController? {
        (UIApplication.shared.firstKeyWindow?.rootViewController as? UITabBarController)?
            .selectedViewController as? UINavigationController
    }

    var visibleViewController: UIViewController? {
        ((UIApplication.shared.firstKeyWindow?.rootViewController as? UITabBarController)?
            .selectedViewController as? UINavigationController)?
            .visibleViewController
    }

    var topViewController: UIViewController? {
        if var topController = UIApplication.shared.firstKeyWindow?.rootViewController {
            while let presentedViewController = topController.presentedViewController {
                topController = presentedViewController
            }
            return topController
        } else {
            return nil
        }
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Opens the shared database and starts the extension runtime. The SwiftUI App that used to
        // do this is gone; without it every screen reads an unopened database.

        Task { await AppEnvironment.shared.start() }

        UserDefaults.standard.register(
            defaults: [
                "Flag.isSideloaded": Self.isSideloaded, // for icloud sync setting
                "Flag.showedLegacySourceListNotice": false,

                "General.incognitoMode": false,
                "General.icloudSync": false,
                "General.appearance": 0,
                "General.useSystemAppearance": true,
                "Appearance.layout": "standard",
                "Appearance.accentColor": AppAccentColor.defaultHex,
                "Appearance.customPortraitRows": UIDevice.current.userInterfaceIdiom == .pad ? 5 : 2,
                "Appearance.customLandscapeRows": UIDevice.current.userInterfaceIdiom == .pad ? 6 : 4,

                // An empty value means every extension networking and WebView
                // path uses WebKit's system user agent.
                UserAgentProvider.extensionNetworkUserAgentKey: "",

                "Library.sortOption": 2, // lastOpened
                "Library.sortAscending": false,
                "Library.listView": false,

                "Library.lastUpdated": Date.distantPast.timeIntervalSince1970,

                "Library.opensReaderView": false,
                "Library.resumeLastOpenedChapter": false,
                "Library.unreadChapterBadges": true,
                "Library.downloadedChapterBadges": true,
                "Library.pinTitles": LibraryViewModel.PinType.none.rawValue,
                "Library.lockLibrary": false,

                "Library.lockedCategories": [String](),
                "Library.showAllCategory": true,

                "Library.updateInterval": "daily",
                "Library.skipTitles": ["hasUnread", "completed", "notStarted"],
                "Library.excludedUpdateCategories": [String](),
                "Library.backgroundRefresh": true,
                "Library.updateOnlyOnWifi": true,
                "Library.refreshMetadata": false,
                "Library.notifyNewChapters": false,
                "Library.progressNotifications": true,

                "Browse.languages": [
                    "multi",
                    Locale.current.languageCode?.lowercased() ?? "en"
                ],
                "Browse.contentRatings": ["safe", "containsNsfw"],
                "Browse.updateCount": 0,

                "History.lockHistoryTab": false,

                "Reader.readingMode": "auto",
                "Reader.skipDuplicateChapters": true,
                "Reader.markDuplicateChapters": true,
                "Reader.downsampleImages": false,
                "Reader.upscaleImages": false,
                "Reader.upscaleMaxHeight": 2000,
                "Reader.cropBorders": false,
                "Reader.disableQuickActions": false,
                "Reader.disableDoubleTap": false,
                "Reader.liveText": false,
                "Reader.hideBarsOnSwipe": false,
                "Reader.tapZones": "disabled",
                "Reader.invertTapZones": false,
                "Reader.animatePageTransitions": true,
                "Reader.backgroundColor": "black",
                "Reader.pagesToPreload": 2,
                "Reader.pagedPageLayout": "auto",
                "Reader.pagedPageOffset": false,
                "Reader.splitWideImages": false,
                "Reader.reverseSplitOrder": false,
                "Reader.verticalInfiniteScroll": true,
                "Reader.pillarbox": false,
                "Reader.pillarboxAmount": 15,
                "Reader.pillarboxOrientation": "both",
                "Reader.orientation": "device",

                // Text Reader defaults
                "Reader.textReaderStyle": "scroll",
                "Reader.textFontFamily": "System",
                "Reader.textFontSize": 18,
                "Reader.textLineSpacing": 8,
                "Reader.textHorizontalPadding": 24,

                "Tracking.updateAfterReading": true,
                "Tracking.autoSyncFromTracker": false,

                "AutomaticBackups.enabled": true,
                "AutomaticBackups.interval": "daily",
                "AutomaticBackups.lastBackup": Date.distantPast.timeIntervalSince1970,
                "AutomaticBackups.libraryEntries": true,
                "AutomaticBackups.chapters": true,
                "AutomaticBackups.tracking": true,
                "AutomaticBackups.history": true,
                "AutomaticBackups.categories": true,
                "AutomaticBackups.readingSessions": true,
                "AutomaticBackups.updates": false,
                "AutomaticBackups.settings": true,
                "AutomaticBackups.sourceLists": false,
                "AutomaticBackups.sensitiveSettings": false,

                "Library.downloadOnlyOnWifi": false,
                "Library.deleteDownloadAfterReading": false,
                // "-1" is DownloadCleanup.KEEP_ALL, as a string because the settings list stores
                // its selections that way.
                "Downloads.removeAfterReadSlots": "-1",
                "Downloads.compress": true,
                "Downloads.parallel": true,
                "Downloads.background": true,
                "Downloads.progressNotifications": true
            ]
        )

        // PlayCover fix: eagerly initialize the Core Data stack on the main thread
        // before any background migration task touches it. The `lazy var container`
        // and the singleton's init are not thread-safe; under PlayCover's timing the
        // main thread and a background migration task race to initialize them, and the
        // persistent-history remote-change observer's performAndWait deadlocks the
        // launch (no window ever appears). Forcing first init on the main thread here
        // makes the main thread win the race deterministically.
        _ = CoreDataManager.shared

        // check for icloud availability
        // https://developer.apple.com/documentation/foundation/filemanager/url(forubiquitycontaineridentifier:)
        // Do not call this method from your app’s main thread. Because this method might take a nontrivial amount of
        // time to set up iCloud and return the requested URL, you should always call it from a secondary thread.
        Task.detached {
            let isiCloudAvailable = FileManager.default.url(forUbiquityContainerIdentifier: nil) != nil
            await MainActor.run {
                if !isiCloudAvailable {
                    LogManager.logger.info("iCloud unavailable")
                }
                UserDefaults.standard.register(defaults: ["Flag.isiCloudAvailable": isiCloudAvailable])
            }
        }

        DataLoader.sharedUrlCache.diskCapacity = 0

        let pipeline = ImagePipeline(delegate: self) {
            let dataLoader: DataLoader = {
                let config = URLSessionConfiguration.default
                config.urlCache = nil
                var protocolClasses = config.protocolClasses ?? []
                protocolClasses.insert(JVMImageURLProtocol.self, at: 0)
                config.protocolClasses = protocolClasses
                return DataLoader(configuration: config)
            }()
            let dataCache = try? DataCache(
                name: "app.tachiyomiaz.TachiyomiAZ.datacache"
            )
            let imageCache = Nuke.ImageCache() // memory cache
            dataCache?.sizeLimit = 500 * 1024 * 1024
            imageCache.costLimit = 100 * 1024 * 1024
            $0.dataCache = dataCache
            $0.imageCache = imageCache
            $0.dataLoader = dataLoader
            $0.dataCachePolicy = .storeOriginalData
            $0.isStoringPreviewsInMemoryCache = false
        }

        ImagePipeline.shared = pipeline
        TransientCoverCache.configure()

        performMigration()
        handleChaptersToBeDeleted()

        networkObserverId = Reachability.registerConnectionTypeObserver { connectionType in
            switch connectionType {
                case .wifi:
                    if UserDefaults.standard.bool(forKey: "Library.downloadOnlyOnWifi") {
                        Task {
                            await DownloadManager.shared.resumeDownloads()
                        }
                    }
                case .cellular, .none:
                    if UserDefaults.standard.bool(forKey: "Library.downloadOnlyOnWifi") {
                        Task {
                            await DownloadManager.shared.pauseDownloads()
                        }
                    }
            }
        }

        application.applicationSupportsShakeToEdit = true

        BackupManager.shared.register()
        MangaManager.shared.register()
        DownloadManager.shared.registerBackgroundTasks()

        Task {
            await BackupManager.shared.scheduleAutoBackup()
            await MangaManager.shared.scheduleLibraryRefresh()
        }

        UNUserNotificationCenter.current().delegate = self
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleNotifyNewChaptersToggle(_:)),
            name: Notification.Name(NotificationManager.settingKey),
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleNotifyNewChaptersToggle(_:)),
            name: Notification.Name(NotificationManager.libraryProgressSettingKey),
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleNotifyNewChaptersToggle(_:)),
            name: Notification.Name(NotificationManager.downloadProgressSettingKey),
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleLibraryUpdateIntervalChange(_:)),
            name: Notification.Name("Library.updateInterval"),
            object: nil
        )

        buildWindow()

        // A URL that launched the app arrives here, not through `application(_:open:options:)`,
        // which only fires once the app is already running. Deferred to the next runloop pass
        // because the alert it raises needs a root view controller that has been presented, and
        // `buildWindow()` has only just made one.
        if let url = launchOptions?[.url] as? URL {
            DispatchQueue.main.async { [weak self] in
                self?.handleUrl(url: url)
            }
        }

        return true
    }

    /// Builds the window and root tab bar.
    ///
    /// Upstream does this in `SceneDelegate`, driven by an Info.plist scene manifest. That never
    /// connected here -- a UIWindowScene was created, but neither
    /// `application(_:configurationForConnecting:options:)` nor `scene(_:willConnectTo:)` was ever
    /// called, so the app launched to an empty black window even though the class resolved by name.
    /// Owning the window here is the path that works; SceneDelegate is kept for the incognito
    /// banner and URL handling it also carries, and can take this back if the cause is found.
    private func buildWindow() {
        let accentColor = AppAccentColor.uiColor
        UIView.appearance().tintColor = accentColor

        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = TabBarController()
        window.tintColor = accentColor

        if UserDefaults.standard.bool(forKey: "General.useSystemAppearance") {
            window.overrideUserInterfaceStyle = .unspecified
        } else {
            window.overrideUserInterfaceStyle =
                UserDefaults.standard.integer(forKey: "General.appearance") == 0 ? .light : .dark
        }

        self.window = window
        window.makeKeyAndVisible()
    }

    @objc private func handleLibraryUpdateIntervalChange(_ note: Notification) {
        Task {
            await MangaManager.shared.scheduleLibraryRefresh()
        }
    }

    @objc private func handleNotifyNewChaptersToggle(_ note: Notification) {
        let settingKey = note.name.rawValue
        let enabled = (note.object as? Bool)
            ?? UserDefaults.standard.bool(forKey: settingKey)
        guard enabled else { return }
        Task {
            let granted = await NotificationManager.shared.requestAuthorization()
            if !granted {
                await MainActor.run {
                    UserDefaults.standard.set(false, forKey: settingKey)
                    NotificationCenter.default.post(
                        name: Notification.Name(settingKey),
                        object: false
                    )
                }
            }
        }
    }

    // configurationForConnecting removed: the Info.plist scene manifest drives this.

    func applicationWillTerminate(_ application: UIApplication) {
        guard let networkObserverId else { return }
        Reachability.unregisterConnectionTypeObserver(networkObserverId)
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        InterfaceOrientationCoordinator.shared.supportedOrientations
    }

    /// A URL opened while the app is already running.
    ///
    /// `SceneDelegate` carries this too, and upstream relies on it, but the scene never connects
    /// here -- see `buildWindow()` -- so `scene(_:openURLContexts:)` is never called and a link
    /// tapped elsewhere reached nothing. This is the delegate the app actually runs under.
    func application(
        _ application: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        handleUrl(url: url)
        return true
    }
}

extension AppDelegate {
    func performMigration() {
        var settingsVersion = UserDefaults.standard.string(forKey: "Flag.currentVersion")
        let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String

        if let oldSettingsVersion = UserDefaults.standard.string(forKey: "currentVersion") {
            settingsVersion = settingsVersion ?? oldSettingsVersion
            UserDefaults.standard.removeObject(forKey: "currentVersion")
        }

        guard currentVersion != settingsVersion else {
            return
        }

        LogManager.logger.info("Migrating settings from version \(settingsVersion ?? "none") to \(currentVersion ?? "unknown")")

        // migrate history to 0.6 format
        if settingsVersion == "0.5" {
            Task.detached {
                await self.migrateHistory()
            }
        }

        // migrate showNsfwSources setting
        if UserDefaults.standard.bool(forKey: "Browse.showNsfwSources") {
            UserDefaults.standard.setValue(["safe", "containsNsfw", "primarilyNsfw"], forKey: "Browse.contentRatings")
            UserDefaults.standard.removeObject(forKey: "Browse.showNsfwSources")
        }

        // migrate pin settings
        if UserDefaults.standard.bool(forKey: "Library.pinManga") {
            let newValue = switch UserDefaults.standard.integer(forKey: "Library.pinMangaType") {
                case 0: LibraryViewModel.PinType.unread.rawValue
                case 1: LibraryViewModel.PinType.updatedChapters.rawValue
                default: LibraryViewModel.PinType.none.rawValue
            }
            UserDefaults.standard.set(newValue, forKey: "Library.pinTitles")
            UserDefaults.standard.removeObject(forKey: "Library.pinManga")
            UserDefaults.standard.removeObject(forKey: "Library.pinMangaType")
        }

        // Aidoku's 0.8.2 migration moved sources between directories. This port has never had
        // those directories -- extensions live where JVMSourceRuntime puts them -- so there is
        // nothing to move.
        if false {
        }

        // migrate unprefixed settings
        if UserDefaults.standard.bool(forKey: "downloadChapterSortAscending") {
            UserDefaults.standard.set(true, forKey: "Flag.downloadChapterSortAscending")
            UserDefaults.standard.removeObject(forKey: "downloadChapterSortAscending")
        }
        if let enabledModelFile = UserDefaults.standard.string(forKey: "enabledModelFile") {
            UserDefaults.standard.set(enabledModelFile, forKey: "Data.enabledModelFile")
            UserDefaults.standard.removeObject(forKey: "enabledModelFile")
        }
        if let downloadQueueState = UserDefaults.standard.data(forKey: "downloadQueueState") {
            UserDefaults.standard.set(downloadQueueState, forKey: "Data.downloadQueueState")
            UserDefaults.standard.removeObject(forKey: "downloadQueueState")
        }
        if let chaptersToBeDeleted = UserDefaults.standard.data(forKey: "chaptersToBeDeleted") {
            UserDefaults.standard.set(chaptersToBeDeleted, forKey: "Data.chaptersToBeDeleted")
            UserDefaults.standard.removeObject(forKey: "chaptersToBeDeleted")
        }

        // migrate to layout appearance setting
        let portraitRows = UserDefaults.standard.integer(forKey: "General.portraitRows")
        let landscapeRows = UserDefaults.standard.integer(forKey: "General.landscapeRows")
        let defaultPortraitRows = UIDevice.current.userInterfaceIdiom == .pad ? 5 : 2
        let defaultLandscapeRows = UIDevice.current.userInterfaceIdiom == .pad ? 6 : 4
        if portraitRows > 0 && portraitRows != defaultPortraitRows {
            UserDefaults.standard.set("custom", forKey: "Appearance.layout")
            UserDefaults.standard.set(portraitRows, forKey: "Appearance.customPortraitRows")
            UserDefaults.standard.removeObject(forKey: "General.portraitRows")
        }
        if landscapeRows > 0 && landscapeRows != defaultLandscapeRows {
            UserDefaults.standard.set("custom", forKey: "Appearance.layout")
            UserDefaults.standard.set(landscapeRows, forKey: "Appearance.customLandscapeRows")
            UserDefaults.standard.removeObject(forKey: "General.landscapeRows")
        }

        UserDefaults.standard.set(currentVersion, forKey: "Flag.currentVersion")
    }

    private func migrateHistory() async {
        showLoadingIndicator(style: .progress)
        try? await Task.sleep(nanoseconds: 500 * 1_000_000)
        await CoreDataManager.shared.migrateChapterHistory(progress: { @Sendable progress in
            Task { @MainActor in
                self.indicatorProgress = progress
            }
        })
        NotificationCenter.default.post(name: .updateLibrary, object: nil)
        await hideLoadingIndicator()
    }

    // migration for 0.8.2
    private func migrateSources() async {
        showLoadingIndicator(style: .indefinite)

        try? await Task.sleep(nanoseconds: 500 * 1_000_000)

        // migrate tracker token settings
        for (key, value) in UserDefaults.standard.dictionaryRepresentation() where key.hasPrefix("Token.") {
            UserDefaults.standard.removeObject(forKey: key)
            UserDefaults.standard.set(value, forKey: key.replacingOccurrences(of: "Token.", with: "Tracker."))
        }

        // Upstream fixes up a lastUpdatedChapters default written by an older build of itself.
        // No build of this app ever wrote that value.

        await AppEnvironment.shared.runtime.reload()

        await hideLoadingIndicator()
    }

    // delete chapters queued for deletion in last launch
    func handleChaptersToBeDeleted() {
        guard
            let data = UserDefaults.standard.data(forKey: "Data.chaptersToBeDeleted"),
            let chapterKeys = try? JSONDecoder().decode([ChapterIdentifier].self, from: data)
        else {
            return
        }
        Task {
            await DownloadManager.shared.delete(chapters: chapterKeys.map {
                .init(sourceKey: $0.sourceKey, mangaKey: $0.mangaKey, chapterKey: $0.chapterKey)
            })
            UserDefaults.standard.removeObject(forKey: "Data.chaptersToBeDeleted")
        }
    }

    enum LoadingStyle {
        case indefinite
        case progress
    }

    /// Shows a non-interactive loading indicator.
    func showLoadingIndicator(style: LoadingStyle = .indefinite, completion: (() -> Void)? = nil) {
        switch style {
            case .indefinite:
                loadingIndicator.startAnimating()
                loadingIndicator.isHidden = false
                progressView.isHidden = true
            case .progress:
                progressView.progress = 0
                loadingIndicator.isHidden = true
                progressView.isHidden = false
        }
        topViewController?.present(loadingAlert, animated: true, completion: completion)
    }

    /// Dismisses a shown loading indicator.
    func hideLoadingIndicator(completion: (() -> Void)? = nil) async {
        await withCheckedContinuation { continuation in
            loadingAlert.dismiss(animated: true) {
                self.loadingIndicator.stopAnimating()
                continuation.resume()
            }
        }
    }

    func handleUrl(url: URL) {
        do {
            if let repositoryURL = try TachiyomiXJarRepository
                .repositoryURL(fromDeepLink: url)
            {
                confirmExtensionRepository(repositoryURL)
                return
            }
        } catch {
            presentAlert(
                title: "Extension Repository Error",
                message: error.localizedDescription
            )
            return
        }

        if
            let scheme = url.scheme?.lowercased(),
            ["tachiyomiaz", "tachiaz", "aidoku"].contains(scheme)
        {
            if
                let host = url.host,
                let source = SourceManager.shared.source(for: host)
            {
                // todo: we should support opening items in library even if the source isn't installed
                Task { @MainActor in
                    // support percent encoding characters like "/" for manga and chapter keys
                    let pathComponents = url.percentEncodedPath
                        .split(separator: "/")
                        .map { String($0).removingPercentEncoding ?? String($0) }

                    if !pathComponents.isEmpty { // /sourceId/mangaId
                        let mangaKey = pathComponents[0].removingPercentEncoding ?? url.pathComponents[1]
                        guard
                            let navigationController,
                            let manga = try? await source.getMangaUpdate(
                                manga: ExtensionRunner.Manga(sourceKey: source.id, key: mangaKey, title: ""),
                                needsDetails: true,
                                needsChapters: false
                            )
                        else {
                            return
                        }
                        let chapterKey = pathComponents[safe: 1]?.removingPercentEncoding ?? pathComponents[safe: 1]
                        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
                        let action = components?.queryItems?.first(where: { $0.name == "action" })?.value.flatMap(MangaView.OpenAction.init)

                        navigationController.pushViewController(
                            MangaViewController(
                                source: source,
                                manga: manga,
                                parent: navigationController.topViewController,
                                chapterKey: chapterKey, // /sourceId/mangaId/chapterId
                                openAction: action // ?action={read,readNext,readLatest}
                            ),
                            animated: true
                        )
                    } else { // /sourceId
                        // Upstream branches to its pre-runner source screen for legacy sources.
                        // This port has none -- see Vendored/_excluded/LegacySourceUI.
                        let vc: UIViewController = NewSourceViewController(source: source)
                        navigationController?.pushViewController(vc, animated: true)
                    }
                }
            } else {
                // Upstream routes OAuth callbacks through an OAuthTracker protocol. This port's
                // MyAnimeList and AniList clients own their own callback handling, and the app
                // registers the scheme itself, so nothing needs dispatching here.
                if false {
                    Task {
                    }
                } else {
                    Task {
                        await handleDeepLink(url: url)
                    }
                }
            }
        } else if url.pathExtension.lowercased() == "tachibk" {
            Task {
                if await BackupManager.shared.importBackup(from: url) {
                    presentAlert(
                        title: NSLocalizedString("BACKUP_IMPORT_SUCCESS", comment: ""),
                        message: NSLocalizedString("BACKUP_IMPORT_SUCCESS_TEXT", comment: "")
                    )
                } else {
                    presentAlert(
                        title: NSLocalizedString("IMPORT_FAIL", comment: ""),
                        message: NSLocalizedString("BACKUP_IMPORT_FAIL_TEXT", comment: "")
                    )
                }
            }
        // Upstream imports an opened CBZ/EPUB into its local source. Local file sources are
        // parked here -- see Vendored/_excluded/Local -- so nothing claims the file.
        } else {
            Task {
                await handleDeepLink(url: url)
            }
        }
    }

    private func confirmExtensionRepository(_ repositoryURL: String) {
        presentAlert(
            title: "Add Extension Repository?",
            message:
                "Only continue if you trust this repository.\n\n" +
                repositoryURL,
            actions: [
                .init(
                    title: NSLocalizedString("CANCEL"),
                    style: .cancel
                ),
                .init(title: "Add Repository", style: .default) { _ in
                    Task {
                        self.showLoadingIndicator()
                        do {
                            let repository = try await
                                TachiyomiXJarRepository.addRepository(
                                    from: repositoryURL
                                )
                            await self.hideLoadingIndicator()
                            self.presentAlert(
                                title: "Repository Added",
                                message: repository.name
                            )
                        } catch {
                            await self.hideLoadingIndicator()
                            self.presentAlert(
                                title: "Extension Repository Error",
                                message: error.localizedDescription
                            )
                        }
                    }
                }
            ]
        )
    }

    func handleDeepLink(url: URL) async -> Bool {
        guard
            let navigationController,
            let targetUrl = (url as NSURL).resourceSpecifier
        else { return false }

        // ensure sources are loaded
        await SourceManager.shared.waitForSourcesLoad()

        // find source that uses the given url
        var targetSource: ExtensionRunner.Source?
        var finalUrl: String?
        for source in SourceManager.shared.sources {
            for sourceUrl in source.urls {
                if let url = (sourceUrl as NSURL).resourceSpecifier, targetUrl.hasPrefix(url) {
                    targetSource = source
                    finalUrl = "\(URL(string: url)?.scheme ?? "https"):\(targetUrl)"
                    break
                }
            }
            if targetSource != nil { break }
        }

        guard let targetSource, let finalUrl else { return false }

        let task = Task { @MainActor in
            do {
                let link = try await targetSource.handleDeepLink(url: finalUrl)

                if let mangaId = link?.mangaKey {
                    // open manga view and scroll to chapter if given
                    guard let manga = try? await targetSource.getMangaUpdate(
                        manga: ExtensionRunner.Manga(
                            sourceKey: targetSource.id,
                            key: mangaId,
                            title: ""
                        ),
                        needsDetails: true,
                        needsChapters: false
                    ) else {
                        return false
                    }

                    navigationController.pushViewController(
                        MangaViewController(
                            source: targetSource,
                            manga: manga,
                            parent: navigationController.topViewController,
                            chapterKey: link?.chapterKey,
                            openAction: .read
                        ),
                        animated: true
                    )

                    return true
                } else if let listing = link?.listing {
                    // open source listing
                    let viewController = SourceListingViewController(source: targetSource, listing: listing)
                    navigationController.pushViewController(viewController, animated: true)

                    return true
                }
            } catch {
                LogManager.logger.error("Failed to handle source deep link: \(error.localizedDescription)")
            }

            return false
        }

        return await task.value
    }

    /// The migration itself, lifted out of the alert action.
    ///
    /// Left inline, this closure crashed the Swift type checker outright ("failed to
    /// produce diagnostic"). It is the same code, one nesting level shallower.
    private func performSourceMigration(source: ExtensionRunner.Source) {
        if source.features.handlesMigration {
            // if the source handles the migration, we can migrate all the db ids
            self.showLoadingIndicator()
            Task {
                // Read straight through rather than as one inferred 4-tuple out of a closure:
                // that form crashed the type checker ("failed to produce diagnostic").
                let sourceKey = source.id
                let historyObjects = CoreDataManager.shared.getHistory(sourceId: sourceKey)
                let libraryMangaIds: [String] = CoreDataManager.shared
                    .getLibraryManga(sourceId: sourceKey)
                    .compactMap { $0.manga?.id }
                let libraryChaptersIds: [(String, String)] = CoreDataManager.shared
                    .getChapters(sourceId: sourceKey)
                    .map { ($0.mangaId, $0.id) }
                // ChapterIdentifier spells these mangaKey/chapterKey.
                let historyMangaIds: [String] = historyObjects.map { $0.mangaKey }
                let historyChapterIds: [(String, String)] = historyObjects
                    .map { ($0.mangaKey, $0.chapterKey) }
                var newMangaIds: [String: String] = [:]
                var newChapterIds: [String: String] = [:]
                if source.features.handlesNotifications {
                    try? await source.handleNotification(notification: "system.startMigration")
                }
                for oldId in libraryMangaIds {
                    newMangaIds[oldId] = try? await source.handleMigration(kind: .manga, mangaKey: oldId, chapterKey: nil)
                }
                for oldId in historyMangaIds where newMangaIds[oldId] == nil  {
                    newMangaIds[oldId] = try? await source.handleMigration(kind: .manga, mangaKey: oldId, chapterKey: nil)
                }
                for (mangaId, oldId) in libraryChaptersIds {
                    newChapterIds[oldId] = try? await source.handleMigration(kind: .chapter, mangaKey: mangaId, chapterKey: oldId)
                }
                if source.features.handlesNotifications {
                    try? await source.handleNotification(notification: "system.endMigration")
                }
                for (mangaId, oldId) in historyChapterIds where newChapterIds[oldId] == nil  {
                    newChapterIds[oldId] = try? await source.handleMigration(kind: .chapter, mangaKey: mangaId, chapterKey: oldId)
                }
                // Rewriting keys in place is a database operation, so it goes through the facade,
                // which uses :core-database's updateMangaUrls for the manga side.
                CoreDataManager.shared.migrateSourceIds(
                    sourceId: source.id,
                    mangaIds: newMangaIds,
                    chapterIds: newChapterIds
                )

                NotificationCenter.default.post(name: .updateLibrary, object: nil)
                NotificationCenter.default.post(name: .updateHistory, object: nil)

                await self.hideLoadingIndicator()
            }
        } else {
            // otherwise, we just show the migration view and let the user do it
            Task {
                let sourceManga = await CoreDataManager.shared.container.performBackgroundTask { context in
                    let objects = CoreDataManager.shared.getLibraryManga(sourceId: source.id, context: context)
                    return objects.compactMap { $0.manga?.toNewManga() }
                }
                if !sourceManga.isEmpty {
                    let migrateView = MigrateResultsView(targetSources: [source], selectedSeries: sourceManga, forceMigrate: true)
                    let viewController = SwiftUINavigationViewController(rootView: migrateView, addDismissButton: false)
                    self.topViewController?.present(viewController, animated: true)
                }
            }
        }
    }

    func handleSourceMigration(source: ExtensionRunner.Source) {
        presentAlert(
            title: NSLocalizedString("SOURCE_BREAKING_CHANGE"),
            message: NSLocalizedString("SOURCE_BREAKING_CHANGE_TEXT"),
            actions: [
                UIAlertAction(title: NSLocalizedString("MIGRATE"), style: .default) { _ in
                    self.performSourceMigration(source: source)
                },
            ]
        )
    }

    func presentAlert(
        title: String,
        message: String? = nil,
        actions: [UIAlertAction] = [],
        textFieldHandlers: [((UITextField) -> Void)] = [],
        textFieldDisablesLastActionWhenEmpty: Bool = false,
        completion: (() -> Void)? = nil
    ) {
        let alertController = UIAlertController(title: title, message: message, preferredStyle: .alert)

        for handler in textFieldHandlers {
            alertController.addTextField { textField in
                handler(textField)

                if textFieldDisablesLastActionWhenEmpty && textFieldHandlers.count == 1 {
                    actions.last?.isEnabled = !(textField.text?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)

                    NotificationCenter.default.addObserver(forName: UITextField.textDidChangeNotification, object: textField, queue: .main) { _ in
                        Task { @MainActor in
                            let text = textField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                            actions.last?.isEnabled = !text.isEmpty
                        }
                    }
                }
            }
        }

        // if no actions are provided, add a default 'OK' action
        if actions.isEmpty {
            let okAction = UIAlertAction(title: NSLocalizedString("OK"), style: .cancel)
            alertController.addAction(okAction)
        } else {
            for action in actions {
                alertController.addAction(action)
            }
        }

        topViewController?.present(alertController, animated: true, completion: completion)
    }
}

extension AppDelegate: ImagePipeline.Delegate {
    nonisolated func imageDecoder(for context: ImageDecodingContext, pipeline: ImagePipeline) -> (any ImageDecoding)? {
        if context.request.userInfo[.processesKey] as? Bool == true {
            // when using a page processor, don't decode data as an image since it may be invalid
            ImageDecoders.Empty.init()
        } else {
            pipeline.configuration.makeImageDecoder(context)
        }
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if
            notification.request.content.categoryIdentifier == NotificationManager.progressCategoryIdentifier
            || notification.request.content.categoryIdentifier == NotificationManager.progressCompletionCategoryIdentifier
        {
            completionHandler([.list])
        } else {
            completionHandler([.banner, .sound, .list])
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        if
            let sourceId = userInfo[NotificationManager.sourceIdInfoKey] as? String,
            let mangaId = userInfo[NotificationManager.mangaIdInfoKey] as? String,
            let encodedSource = sourceId.addingPercentEncoding(withAllowedCharacters: .urlHostAllowed),
            let encodedManga = mangaId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
            let url = URL(
                string: "tachiyomiaz://\(encodedSource)/\(encodedManga)"
            )
        {
            Task { @MainActor in
                self.handleUrl(url: url)
            }
        }
        completionHandler()
    }
}
