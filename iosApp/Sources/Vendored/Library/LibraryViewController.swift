//
//  LibraryViewController.swift
//  Aidoku (iOS)
//
//  Created by Skitty on 7/23/22.
//

import UIKit
import LocalAuthentication
import SwiftUI
import ExtensionRunner

private final class LibraryCategorySwipeGestureRecognizer: UISwipeGestureRecognizer {}

class LibraryViewController: OldMangaCollectionViewController {
    let viewModel = LibraryViewModel()

    /// Guards against overlapping diffable applies; see `updateDataSource`.
    private var isApplyingSnapshot = false
    private var needsAnotherApply = false

    private let filterDrawerTransitioningDelegate = FilterDrawerTransitioningDelegate()
    private weak var presentedFilterDrawer: UIViewController?

    // MARK: Bar Buttons
    private lazy var downloadBarButton = makeBarButton(
        systemName: "square.and.arrow.down",
        action: #selector(openDownloadQueue),
        titleKey: "DOWNLOAD_QUEUE",
        sharesBackground: false
    )
    private lazy var lockBarButton = makeBarButton(
        systemName: locked ? "lock" : "lock.open",
        action: #selector(performToggleLock),
        titleKey: "TOGGLE_LOCK"
    )
    private lazy var moreBarButton =  makeBarButton(
        systemName: "ellipsis",
        action: nil,
        titleKey: "MORE_BARBUTTON"
    )
    private func makeBarButton(systemName: String? = nil, action: Selector?, titleKey: String, sharesBackground: Bool = true) -> UIBarButtonItem {
        let item = UIBarButtonItem(
            image: systemName.flatMap { UIImage(systemName: $0) },
            style: .plain,
            target: self,
            action: action
        )
        item.title = NSLocalizedString(titleKey)
        if #available(iOS 26.0, *), !sharesBackground {
            item.sharesBackground = false
        }
        return item
    }

    private lazy var refreshControl = UIRefreshControl()
    private lazy var emptyStackView = EmptyPageStackView()
    private lazy var lockedStackView = LockedPageStackView()

    private lazy var locked = viewModel.isCategoryLocked()
    private var lastSearch: String?

    private let libraryUndoManager = UndoManager()
    override var undoManager: UndoManager { libraryUndoManager }
    override var canBecomeFirstResponder: Bool { true }

    override var usesListLayout: Bool {
        get {
            UserDefaults.standard.bool(forKey: "Library.listView")
        }
        set {
            UserDefaults.standard.setValue(newValue, forKey: "Library.listView")
        }
    }

    override init() {
        super.init()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.isToolbarHidden = true
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)

        // fix refresh control snapping height
        refreshControl.didMoveToSuperview()

        // hack to show search bar on initial presentation
        if !navigationItem.hidesSearchBarWhenScrolling {
            navigationItem.hidesSearchBarWhenScrolling = true
        }

        becomeFirstResponder()
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        // load stored download queue state on first load
        Task {
            await SourceManager.shared.waitForSourcesLoad() // make sure sources are loaded first
            await DownloadManager.shared.loadQueueState()
        }
    }

    override func configure() {
        super.configure()

        title = NSLocalizedString("LIBRARY")

        navigationController?.navigationBar.prefersLargeTitles = true
        navigationItem.hidesSearchBarWhenScrolling = false

        collectionView.keyboardDismissMode = .onDrag

        // search controller
        let searchController = UISearchController(searchResultsController: nil)
        searchController.searchResultsUpdater = self
        searchController.obscuresBackgroundDuringPresentation = false
        searchController.searchBar.placeholder = viewModel.currentCategory == nil
            ? NSLocalizedString("LIBRARY_SEARCH")
            : NSLocalizedString("CATEGORY_SEARCH")
        navigationItem.searchController = searchController

        let filterEdgePan = UIScreenEdgePanGestureRecognizer(
            target: self,
            action: #selector(handleFilterEdgePan(_:))
        )
        filterEdgePan.edges = .right
        view.addGestureRecognizer(filterEdgePan)

        let activeSearchFilterEdgePan = UIScreenEdgePanGestureRecognizer(
            target: self,
            action: #selector(handleFilterEdgePan(_:))
        )
        activeSearchFilterEdgePan.edges = .right
        searchController.view.addGestureRecognizer(activeSearchFilterEdgePan)

        // navbar buttons
        updateMoreMenu()

        // toolbar buttons (editing)
        let deleteButton = UIBarButtonItem(
            title: nil,
            style: .plain,
            target: self,
            action: #selector(removeSelectedFromLibrary)
        )
        deleteButton.image = UIImage(systemName: "trash")
        if #unavailable(iOS 26.0) {
            deleteButton.tintColor = .systemRed
        }

        let addButton = UIBarButtonItem(
            title: nil,
            style: .plain,
            target: self,
            action: #selector(addSelectedToCategories)
        )
        addButton.image = UIImage(systemName: "folder.badge.plus")

        toolbarItems = [
            deleteButton,
            UIBarButtonItem(systemItem: .flexibleSpace),
            addButton
        ]

        // pull to refresh
        refreshControl.addTarget(self, action: #selector(updateLibraryRefresh(refreshControl:)), for: .valueChanged)
        collectionView.refreshControl = refreshControl

        collectionView.allowsMultipleSelection = !ProcessInfo.processInfo.isMacCatalystApp
        collectionView.allowsSelectionDuringEditing = true

        let previousCategoryGesture = LibraryCategorySwipeGestureRecognizer(
            target: self,
            action: #selector(swipeBetweenCategories(_:))
        )
        previousCategoryGesture.direction = .right
        previousCategoryGesture.cancelsTouchesInView = false
        previousCategoryGesture.delegate = self
        collectionView.addGestureRecognizer(previousCategoryGesture)

        let nextCategoryGesture = LibraryCategorySwipeGestureRecognizer(
            target: self,
            action: #selector(swipeBetweenCategories(_:))
        )
        nextCategoryGesture.direction = .left
        nextCategoryGesture.cancelsTouchesInView = false
        nextCategoryGesture.delegate = self
        collectionView.addGestureRecognizer(nextCategoryGesture)

        // header view
        let registration = UICollectionView.SupplementaryRegistration<LibraryCategorySelectionHeader>(
            elementKind: UICollectionView.elementKindSectionHeader
        ) { [weak self] header, _, _ in
            guard let self else { return }

            header.delegate = self
            header.options = makeCategoryHeaderOptions()
            header.setSelectedOption(selectedCategoryIndexPath())

            // load locked icons
            if UserDefaults.standard.bool(forKey: "Library.lockLibrary") {
                header.lockedOptions = lockedCategoryIndexPaths()
            }
        }

        dataSource.supplementaryViewProvider = { collectionView, kind, indexPath in
            if kind == UICollectionView.elementKindSectionHeader {
                return collectionView.dequeueConfiguredReusableSupplementary(
                    using: registration,
                    for: indexPath
                )
            }
            return nil
        }

        // empty text view
        emptyStackView.isHidden = true
        view.addSubview(emptyStackView)

        // locked text view
        lockedStackView.isHidden = true
        lockedStackView.text = viewModel.currentCategory == nil
            ? NSLocalizedString("LIBRARY_LOCKED")
            : NSLocalizedString("CATEGORY_LOCKED")
        lockedStackView.buttonText = NSLocalizedString("VIEW_LIBRARY")
        lockedStackView.button.addTarget(self, action: #selector(performUnlock), for: .touchUpInside)
        view.addSubview(lockedStackView)

        // load data
        Task {
            // load categories
            await viewModel.refreshCategories(skipDataLoad: true)
            updateNavbarItems()

            // Categories are independent of the library contents. Publish the
            // tab strip immediately instead of making it wait for a large
            // library fetch and badge calculation.
            collectionView.collectionViewLayout = self.makeCollectionViewLayout()
            var initialSnapshot = NSDiffableDataSourceSnapshot<Section, MangaInfo>()
            initialSnapshot.appendSections([.regular])
            await dataSource.apply(initialSnapshot, animatingDifferences: false)
            collectionView.layoutIfNeeded()
            updateHeaderCategories()
            updateHeaderLockIcons()

            // load library
            await viewModel.loadLibrary(refreshBadges: false)
            // refresh header after category availability is known
            collectionView.collectionViewLayout = self.makeCollectionViewLayout()
            updateEmptyStack()
            updateLockState()
            updateDataSource(animatingDifferences: false)
            collectionView.layoutIfNeeded()
            updateHeaderCategories()
            updateHeaderLockIcons()
        }
    }

    override func constrain() {
        super.constrain()

        emptyStackView.translatesAutoresizingMaskIntoConstraints = false
        lockedStackView.translatesAutoresizingMaskIntoConstraints = false

        NSLayoutConstraint.activate([
            emptyStackView.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            emptyStackView.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            lockedStackView.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            lockedStackView.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])
    }

    override func observe() {
        super.observe()

        let checkNavbarDownloadButton: (Notification) -> Void = { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                guard !self.isEditing else { return }
                let shouldShowButton = await DownloadManager.shared.hasQueuedDownloads()
                let index = self.navigationItem.rightBarButtonItems?.firstIndex(of: self.downloadBarButton)
                if shouldShowButton && index == nil {
                    // rightmost button
                    self.navigationItem.rightBarButtonItems?.insert(
                        self.downloadBarButton,
                        at: (self.navigationItem.rightBarButtonItems?.count ?? 1) - 1
                    )
                } else if !shouldShowButton, let index = index {
                    self.navigationItem.rightBarButtonItems?.remove(at: index)
                }
            }
        }
        addObserver(forName: .downloadsQueued, using: checkNavbarDownloadButton)
        addObserver(forName: .downloadCancelled, using: checkNavbarDownloadButton)
        addObserver(forName: .downloadsCancelled, using: checkNavbarDownloadButton)

        let updateDownloadCounts: (Notification) -> Void = { [weak self] notification in
            guard let self else { return }
            if notification.name == .downloadsRemoved, notification.object == nil {
                Task {
                    await self.viewModel.clearDownloadCounts()
                    self.updateDataSource()
                }
                return
            }
            if let id = notification.object as? ChapterIdentifier {
                Task {
                    await self.viewModel.fetchDownloadCounts(for: id.mangaIdentifier)
                    self.updateDataSource()
                }
            } else if let id = notification.object as? MangaIdentifier {
                Task {
                    await self.viewModel.fetchDownloadCounts(for: id)
                    self.updateDataSource()
                }
            }
        }
        addObserver(forName: .downloadFinished) { notification in
            checkNavbarDownloadButton(notification)
            updateDownloadCounts(.init(name: .downloadFinished, object: (notification.object as? Download)?.mangaIdentifier))
        }
        addObserver(forName: .downloadRemoved, using: updateDownloadCounts)
        addObserver(forName: .downloadsRemoved, using: updateDownloadCounts)

        addObserver(forName: .updateLibrary) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                let categoryAvailabilityChanged = await self.viewModel.loadLibrary()
                if categoryAvailabilityChanged {
                    self.collectionView.collectionViewLayout = self.makeCollectionViewLayout()
                }
                self.updateEmptyStack()
                self.updateDataSource()
                self.refreshCategoryHeader()
            }
        }
        addObserver(forName: .updateLibraryLock) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                self.locked = self.viewModel.isCategoryLocked()
                self.updateLockState()
            }
        }
        addObserver(forName: .updateCategories) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                await self.viewModel.refreshCategories()
                self.collectionView.collectionViewLayout = self.makeCollectionViewLayout()
                self.updateDataSource()
                if !self.isEditing {
                    self.updateToolbar() // show/hide add category button
                }
                self.refreshCategoryHeader()
                // update lock state
                if UserDefaults.standard.bool(forKey: "Library.lockLibrary") {
                    NotificationCenter.default.post(name: .updateLibraryLock, object: nil)
                }
            }
        }
        addObserver(forName: .searchLibrary) { [weak self] notification in
            guard let self, let query = notification.object as? String else { return }
            navigationController?.popToViewController(self, animated: true)
            showLibrarySearch(query: query)
        }
        addObserver(forName: .updateMangaCategories) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                let categoryAvailabilityChanged = await self.viewModel.loadLibrary(refreshBadges: false)
                if categoryAvailabilityChanged {
                    self.collectionView.collectionViewLayout = self.makeCollectionViewLayout()
                }
                self.updateDataSource()
                self.refreshCategoryHeader()
            }
        }
        addObserver(forName: .updateManga) { [weak self] notification in
            guard let self, let id = notification.object as? MangaIdentifier else { return }
            Task {
                let libraryReloaded = if !UserDefaults.standard.bool(forKey: "General.incognitoMode") {
                    await self.viewModel.mangaOpened(sourceId: id.sourceKey, mangaId: id.mangaKey)
                } else {
                    false
                }
                let metadataFilterChanged = self.viewModel.hasEffectiveFilter([
                    .completed,
                    .contentRating,
                    .source
                ])
                if metadataFilterChanged {
                    await self.viewModel.loadLibrary()
                } else if !libraryReloaded {
                    if self.viewModel.sortMethod == .lastUpdated || self.viewModel.sortMethod == .lastChapter {
                        // if sorting by updated or last chapter, or pinning updated, we need to reload the library to update the order
                        await self.viewModel.loadLibrary()
                    } else {
                        // otherwise, just update the unread count (in case chapters were added)
                        await self.viewModel.fetchUnreads(for: id)
                    }
                }
                self.updateDataSource()
            }
        }
        addObserver(forName: .updateTrackers) { [weak self] _ in
            guard let self,
                  self.viewModel.hasEffectiveFilter([.tracking])
            else {
                return
            }
            Task { @MainActor in
                await self.viewModel.loadLibrary()
                self.updateDataSource()
            }
        }
        addObserver(forName: .openedManga) { [weak self] notification in
            guard let self, let id = notification.object as? MangaIdentifier else { return }
            Task {
                await self.viewModel.mangaOpened(sourceId: id.sourceKey, mangaId: id.mangaKey)
                self.updateDataSource()
            }
        }

        addObserver(forName: .pinTitles) { [weak self] _ in
            guard let self else { return }
            self.viewModel.pinType = self.viewModel.getPinType()
            Task { @MainActor in
                await self.viewModel.loadLibrary()
                self.updateDataSource()
            }
        }

        // refresh badges
        addObserver(forName: "Library.unreadChapterBadges") { [weak self] _ in
            if UserDefaults.standard.bool(forKey: "Library.unreadChapterBadges") {
                self?.viewModel.badgeType.insert(.unread)
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    await viewModel.fetchUnreads(skipSortCheck: true)
                    reloadItems()
                }
            } else {
                self?.viewModel.badgeType.remove(.unread)
                self?.reloadItems()
            }
        }
        addObserver(forName: "Library.downloadedChapterBadges") { [weak self] _ in
            if UserDefaults.standard.bool(forKey: "Library.downloadedChapterBadges") {
                self?.viewModel.badgeType.insert(.downloaded)
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    await viewModel.fetchDownloadCounts()
                    reloadItems()
                }
            } else {
                self?.viewModel.badgeType.remove(.downloaded)
                self?.reloadItems()
            }
        }

        // update history
        addObserver(forName: .updateHistory) { [weak self] notification in
            guard let self else { return }
            Task { @MainActor in
                if notification.object as? String == "backupRestore" {
                    self.viewModel.reloadPersistedBadgeCaches()
                    await self.viewModel.loadLibrary(refreshBadges: false)
                    self.updateDataSource(animatingDifferences: false)
                    return
                }
                await self.viewModel.fetchUnreads()
                if self.viewModel.pinType != .unread {
                    await self.viewModel.loadLibrary()
                }
                self.updateDataSource()
            }
        }
        addObserver(forName: .historyAdded) { [weak self] notification in
            guard let self, let chapters = notification.object as? [ChapterIdentifier] else { return }
            Task { @MainActor in
                let manga = Array(Set(chapters.map { MangaInfo(mangaId: $0.mangaKey, sourceId: $0.sourceKey) }))
                await self.viewModel.updateHistory(for: manga, read: true)
                self.updateDataSource()
            }
        }
        addObserver(forName: .historyRemoved) { [weak self] notification in
            guard let self else { return }
            Task { @MainActor in
                var manga: [MangaInfo] = []
                if let chapters = notification.object as? [ChapterIdentifier] {
                    manga = Array(Set(chapters.map { MangaInfo(mangaId: $0.mangaKey, sourceId: $0.sourceKey) }))
                } else if let mangaObject = notification.object as? MangaIdentifier {
                    manga = [MangaInfo(mangaId: mangaObject.mangaKey, sourceId: mangaObject.sourceKey)]
                }
                await self.viewModel.updateHistory(for: manga, read: false)
                self.updateDataSource()
            }
        }
        addObserver(forName: .historySet) { [weak self] notification in
            guard let self, let item = notification.object as? (chapter: ChapterIdentifier, page: Int) else { return }
            Task { @MainActor in
                self.viewModel.mangaRead(sourceId: item.chapter.sourceKey, mangaId: item.chapter.mangaKey)
                if self.viewModel.hasEffectiveFilter([.started]) {
                    await self.viewModel.loadLibrary()
                }
                self.updateDataSource()
            }
        }

        // lock library when moving to background
        addObserver(forName: UIApplication.willResignActiveNotification) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                self.locked = self.viewModel.isCategoryLocked()
                self.updateLockState()
            }
        }
    }

    // collection view layout with header
    override func makeCollectionViewLayout() -> UICollectionViewLayout {
        let layout = super.makeCollectionViewLayout()
        guard let layout = layout as? UICollectionViewCompositionalLayout else { return layout }

        let config = UICollectionViewCompositionalLayoutConfiguration()
        config.interSectionSpacing = layout.configuration.interSectionSpacing
        if !categoryIndexPaths().isEmpty {
            let globalHeader = NSCollectionLayoutBoundarySupplementaryItem(
                layoutSize: NSCollectionLayoutSize(
                    widthDimension: .fractionalWidth(1),
                    heightDimension: .absolute(48)
                ),
                elementKind: UICollectionView.elementKindSectionHeader,
                alignment: .top
            )
            globalHeader.pinToVisibleBounds = true
            globalHeader.zIndex = 2
            config.boundarySupplementaryItems = [globalHeader]
        }
        layout.configuration = config

        return layout
    }

    // cells with badges
    override func configure(cell: MangaGridCell, info: MangaInfo, indexPath: IndexPath) {
        super.configure(cell: cell, info: info, indexPath: indexPath)

        cell.badgeNumber = viewModel.badgeType.contains(.unread) ? info.unread : 0
        cell.badgeNumber2 = viewModel.badgeType.contains(.downloaded) ? info.downloads : 0

        cell.setEditing(self.isEditing, animated: false)
    }

    override func configure(cell: MangaListCell, info: MangaInfo, indexPath: IndexPath) {
        super.configure(cell: cell, info: info, indexPath: indexPath)

        cell.badgeNumber = viewModel.badgeType.contains(.unread) ? info.unread : 0
        cell.badgeNumber2 = viewModel.badgeType.contains(.downloaded) ? info.downloads : 0

        cell.setEditing(isEditing, animated: false)
    }

    override func setEditing(_ editing: Bool, animated: Bool) {
        super.setEditing(editing, animated: animated)
        updateNavbarItems()
        updateToolbar()

        if ProcessInfo.processInfo.isMacCatalystApp {
            collectionView.allowsMultipleSelection = editing
        }

        for cell in collectionView.visibleCells {
            if let cell = cell as? MangaGridCell {
                cell.setEditing(editing, animated: animated)
            } else if let cell = cell as? MangaListCell {
                cell.setEditing(editing, animated: animated)
            }
        }
    }
}

extension LibraryViewController {
    func updateNavbarItems() {
        if isEditing {
            let allItemsSelected = collectionView.indexPathsForSelectedItems?.count ?? 0 == dataSource.snapshot().itemIdentifiers.count
            navigationItem.leftBarButtonItem = if allItemsSelected {
                makeBarButton(
                    action: #selector(deselectAllItems),
                    titleKey: "DESELECT_ALL"
                )
            } else {
                makeBarButton(
                    action: #selector(selectAllItems),
                    titleKey: "SELECT_ALL"
                )
            }
            navigationItem.rightBarButtonItems = [UIBarButtonItem(
                barButtonSystemItem: .done,
                target: self,
                action: #selector(stopEditing)
            )]
        } else {
            var items: [UIBarButtonItem] = [moreBarButton]
            if viewModel.isCategoryLocked() {
                items.append(lockBarButton)
            }
            navigationItem.rightBarButtonItems = items
            navigationItem.leftBarButtonItem =
                (tabBarController as? TabBarController)?
                .makeDrawerBarButtonItem()
            Task { @MainActor in
                if await DownloadManager.shared.hasQueuedDownloads() {
                    let index = (navigationItem.rightBarButtonItems?.count ?? 1) - 1
                    guard !(navigationItem.rightBarButtonItems?.contains(downloadBarButton) ?? true) else { return }
                    navigationItem.rightBarButtonItems?.insert(
                        downloadBarButton,
                        at: index
                    )
                }
            }
        }
    }

    func updateToolbar() {
        if isEditing {
            // show toolbar
            if navigationController?.isToolbarHidden ?? false {
                UIView.animate(withDuration: CATransaction.animationDuration()) {
                    self.navigationController?.isToolbarHidden = false
                    self.navigationController?.toolbar.alpha = 1
                    if #available(iOS 26.0, *) {
                        // hide tab bar on iOS 26 (it covers the toolbar)
                        self.tabBarController?.isTabBarHidden = true
                    }
                }
            }
            // show add to category button if categories exist
            if viewModel.categories.isEmpty {
                if #available(iOS 16.0, *) {
                    toolbarItems?.last?.isHidden = true
                } else {
                    toolbarItems?.last?.image = nil
                }
            } else {
                if !self.viewModel.categories.isEmpty {
                    if #available(iOS 16.0, *) {
                        toolbarItems?.last?.isHidden = false
                    } else {
                        toolbarItems?.last?.image = UIImage(systemName: "folder.badge.plus")
                    }
                }
            }
            // enable items
            let hasSelectedItems = !(collectionView.indexPathsForSelectedItems?.isEmpty ?? true)
            toolbarItems?.first?.isEnabled = hasSelectedItems
            toolbarItems?.last?.isEnabled = hasSelectedItems
        } else if !(self.navigationController?.isToolbarHidden ?? true) {
            // fade out toolbar
            UIView.animate(withDuration: CATransaction.animationDuration()) {
                self.navigationController?.toolbar.alpha = 0
                if #available(iOS 26.0, *) {
                    // reshow tab bar on iOS 26
                    self.tabBarController?.isTabBarHidden = false
                }
            } completion: { _ in
                self.navigationController?.isToolbarHidden = true
            }
        }
    }

    // updates library empty message
    // should be called when category changes and when library loads initially
    func updateEmptyStack() {
        emptyStackView.imageSystemName = "books.vertical.fill"
        emptyStackView.title = viewModel.currentCategory == nil
            ? NSLocalizedString("LIBRARY_EMPTY")
            : NSLocalizedString("CATEGORY_EMPTY")
        emptyStackView.text = viewModel.actuallyEmpty
            ? NSLocalizedString("LIBRARY_ADD_CONTENT")
            : NSLocalizedString("LIBRARY_ADJUST_FILTERS")

        navigationItem.searchController?.searchBar.placeholder = viewModel.currentCategory == nil
            ? NSLocalizedString("LIBRARY_SEARCH")
            : NSLocalizedString("CATEGORY_SEARCH")
    }

    @objc func stopEditing() {
        setEditing(false, animated: true)
        deselectAllItems()
    }

    @objc func selectAllItems() {
        for item in dataSource.snapshot().itemIdentifiers {
            if let indexPath = dataSource.indexPath(for: item) {
                collectionView.selectItem(at: indexPath, animated: false, scrollPosition: [])
            }
        }
        updateNavbarItems()
        updateToolbar()
        reloadItems()
    }

    @objc func deselectAllItems() {
        for item in dataSource.snapshot().itemIdentifiers {
            if let indexPath = dataSource.indexPath(for: item) {
                collectionView.deselectItem(at: indexPath, animated: false)
            }
        }
        updateNavbarItems()
        updateToolbar()
        reloadItems()
    }

    @objc func updateLibraryRefresh(refreshControl: UIRefreshControl? = nil) {
        let isBlockedByNoWifi = UserDefaults.standard.bool(forKey: "Library.updateOnlyOnWifi") && Reachability.getConnectionType() != .wifi
        // nil means All, while an empty string intentionally identifies the
        // uncategorized tab.
        let refreshCategory: String? = if viewModel.isInRealCategory || viewModel.isInUncategorizedCategory {
            viewModel.currentCategory
        } else {
            nil
        }

        Task {
            // delay hiding refresh control to avoid buggy animation
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            refreshControl?.endRefreshing()

            if isBlockedByNoWifi {
                self.presentAlert(
                    title: NSLocalizedString("REFRESH_NO_WIFI"),
                    message: NSLocalizedString("REFRESH_NO_WIFI_TEXT"),
                    actions: [
                        UIAlertAction(title: NSLocalizedString("OK"), style: .cancel),
                        UIAlertAction(title: NSLocalizedString("REFRESH_ANYWAYS"), style: .default) { _ in
                            Task {
                                await MangaManager.shared.backgroundRefreshLibrary(
                                    category: refreshCategory,
                                    skipReachabilityCheck: true
                                )
                            }
                        }
                    ]
                )
            }
        }

        // trigger library refresh
        guard !isBlockedByNoWifi else { return }
        Task {
            await MangaManager.shared.backgroundRefreshLibrary(
                category: refreshCategory
            )
        }
    }

    @objc func openDownloadQueue() {
        let viewController = UIHostingController(rootView: DownloadQueueView())
        viewController.navigationItem.largeTitleDisplayMode = .never
        viewController.navigationItem.title = NSLocalizedString("DOWNLOAD_QUEUE")
        if #available(iOS 26.0, *) {
            viewController.preferredTransition = .zoom { _ in
                self.downloadBarButton
            }
        }
        viewController.modalPresentationStyle = .pageSheet
        present(viewController, animated: true)
    }

    @objc func removeSelectedFromLibrary() {
        let inCategory = viewModel.isInRealCategory
        let selectedItems = collectionView.indexPathsForSelectedItems ?? []
        confirmAction(
            actions: inCategory ? [
                UIAlertAction(
                    title: NSLocalizedString("REMOVE_FROM_CATEGORY"),
                    style: .destructive
                ) { _ in
                    Task {
                        let identifiers = selectedItems.compactMap { self.dataSource.itemIdentifier(for: $0) }
                        await self.removeFromCategory(mangaInfo: identifiers)?.value
                        self.updateNavbarItems()
                        self.updateToolbar()
                    }
                }
            ] : [],
            continueActionName: NSLocalizedString("REMOVE_FROM_LIBRARY"),
            sourceItem: toolbarItems?.first
        ) {
            Task {
                let identifiers = selectedItems.compactMap { self.dataSource.itemIdentifier(for: $0) }
                await self.removeFromLibrary(mangaInfo: identifiers)?.value
                self.updateNavbarItems()
                self.updateToolbar()
            }
        }
    }

    @objc func addSelectedToCategories() {
        let manga = (collectionView.indexPathsForSelectedItems ?? []).compactMap {
            dataSource.itemIdentifier(for: $0)
        }
        present(
            UINavigationController(rootViewController: AddToCategoryViewController(
                manga: manga,
                disabledCategories: viewModel.isInRealCategory ? [viewModel.currentCategory!] : []
            )),
            animated: true
        )
    }
}

// MARK: - Data Source Updating
extension LibraryViewController {
    func clearDataSource() {
        let snapshot = NSDiffableDataSourceSnapshot<Section, MangaInfo>()
        dataSource.apply(snapshot)
    }

    /// Applies the current library to the collection view, one apply at a time.
    ///
    /// A dozen observers end here, and several of them fire together: finishing a chapter posts
    /// historyAdded, historySet and updateHistory, each handled in its own `Task { @MainActor }`,
    /// so they interleave rather than running in order. Starting an animated diffable apply while
    /// another is mid-animation corrupts the layout engine -- it shows up as an EXC_BAD_ACCESS deep
    /// inside NSISEngine during `_endItemAnimationsWithInvalidationContext`, nowhere near the code
    /// that caused it, and only on a library big enough for the animation to still be running when
    /// the next apply arrives.
    ///
    /// So an apply that arrives during one is remembered rather than started. The coalesced apply
    /// runs unanimated: it is catching up to state the user has already seen change, and animating
    /// a diff that may now span many items is both wrong-looking and the expensive case.
    func updateDataSource(animatingDifferences: Bool = true) {
        // Cheap, idempotent, and independent of the apply -- so it runs even on a call that
        // coalesces into an in-flight one and builds no snapshot. Mirrors what the snapshot would
        // have contained: nothing when locked, otherwise the two arrays.
        let willBeEmpty = locked || (viewModel.manga.isEmpty && viewModel.pinnedManga.isEmpty)
        if navigationItem.searchController?.searchBar.text?.isEmpty ?? true {
            emptyStackView.isHidden = !willBeEmpty
        }
        collectionView.isScrollEnabled = emptyStackView.isHidden && lockedStackView.isHidden
        collectionView.refreshControl = collectionView.isScrollEnabled ? refreshControl : nil

        guard !isApplyingSnapshot else {
            needsAnotherApply = true
            return
        }

        var snapshot = NSDiffableDataSourceSnapshot<Section, MangaInfo>()

        if !locked {
            if !viewModel.pinnedManga.isEmpty {
                snapshot.appendSections(Section.allCases)
                snapshot.appendItems(viewModel.pinnedManga, toSection: .pinned)
            } else {
                snapshot.appendSections([.regular])
            }

            snapshot.appendItems(viewModel.manga, toSection: .regular)
        }

        isApplyingSnapshot = true
        dataSource.apply(
            snapshot,
            animatingDifferences: animatingDifferences
        ) { [weak self] in
            guard let self else { return }
            self.isApplyingSnapshot = false
            if self.needsAnotherApply {
                self.needsAnotherApply = false
                self.updateDataSource(animatingDifferences: false)
            }
        }

    }

    func reloadItems() {
        var snapshot = dataSource.snapshot()
        snapshot.reconfigureItems(snapshot.itemIdentifiers)
        dataSource.apply(snapshot)
    }
}

// MARK: - Locking
extension LibraryViewController {
    func lock() {
        locked = true
        updateLockState()
    }

    func unlock() {
        locked = false
        updateLockState()
    }

    func attemptUnlock() async {
        do {
            let success = try await LAContext().evaluatePolicy(
                .defaultPolicy,
                localizedReason: NSLocalizedString("AUTH_FOR_LIBRARY")
            )
            guard success else { return }
        } catch {
            // The error is displayed to users, so we can ignore it.
            return
        }

        unlock()
    }

    @objc func performUnlock() {
        Task {
            await attemptUnlock()
        }
    }

    @objc func performToggleLock() {
        Task {
            if locked {
                await attemptUnlock()
            } else {
                lock()
            }
        }
    }

    func updateLockState(updateCollection: Bool = true) {
        if locked {
            // only update if lock view not already showing
            if emptyStackView.alpha != 0 {
                collectionView.isScrollEnabled = false
                emptyStackView.alpha = 0
                lockedStackView.alpha = 0
                lockedStackView.isHidden = false
                UIView.animate(withDuration: CATransaction.animationDuration()) {
                    self.lockedStackView.alpha = 1
                }
            }
        } else {
            collectionView.isScrollEnabled = emptyStackView.isHidden
            lockedStackView.isHidden = true
            UIView.animate(withDuration: CATransaction.animationDuration()) {
                self.emptyStackView.alpha = 1
            }
        }
        lockBarButton.image = UIImage(systemName: locked ? "lock" : "lock.open")

        lockedStackView.text = viewModel.currentCategory == nil
            ? NSLocalizedString("LIBRARY_LOCKED")
            : NSLocalizedString("CATEGORY_LOCKED")

        updateNavbarLock()
        updateHeaderLockIcons()
        if updateCollection {
            updateDataSource()
        }
    }

    func updateNavbarLock() {
        guard !isEditing else { return }
        let shouldShowLockIcon = viewModel.isCategoryLocked()
        let index = navigationItem.rightBarButtonItems?.firstIndex(of: lockBarButton)
        if shouldShowLockIcon && index == nil {
            if navigationItem.rightBarButtonItems?.count ?? 0 == 0 {
                navigationItem.rightBarButtonItems = [lockBarButton]
            } else {
                navigationItem.rightBarButtonItems?.insert(lockBarButton, at: 1)
            }
        } else if !shouldShowLockIcon, let index {
            navigationItem.rightBarButtonItems?.remove(at: index)
        }
    }

    private func showLibrarySearch(query: String) {
        guard let searchController = navigationItem.searchController else { return }
        lastSearch = nil
        searchController.searchBar.text = query
        searchController.isActive = true
        updateSearchResults(for: searchController)
        DispatchQueue.main.async {
            searchController.searchBar.becomeFirstResponder()
        }
    }

    private func makeCategoryHeaderOptions() -> [LibraryCategorySelectionHeader.Section] {
        var options: [LibraryCategorySelectionHeader.Section] = []
        var primaryOptions: [String] = []
        if UserDefaults.standard.bool(forKey: "Library.showAllCategory") {
            primaryOptions.append(NSLocalizedString("ALL"))
        }
        if viewModel.hasUncategorizedManga {
            primaryOptions.append(NSLocalizedString("UNCATEGORIZED"))
        }
        options.append(.init(options: primaryOptions))
        if !viewModel.categories.isEmpty {
            options.append(.init(title: NSLocalizedString("CATEGORIES"), options: viewModel.categories))
        }
        if !viewModel.filterGroups.isEmpty {
            options.append(.init(title: NSLocalizedString("FILTER_GROUPS"), options: viewModel.filterGroups.map(\.title)))
        }
        return options
    }

    private func categoryIndexPaths() -> [IndexPath] {
        makeCategoryHeaderOptions().enumerated().flatMap { sectionIndex, section in
            section.options.indices.map { IndexPath(row: $0, section: sectionIndex) }
        }
    }

    private func selectedCategoryIndexPath() -> IndexPath {
        guard let currentCategory = viewModel.currentCategory else {
            return IndexPath(row: 0, section: 0)
        }
        if currentCategory.isEmpty, viewModel.hasUncategorizedManga {
            let row = UserDefaults.standard.bool(forKey: "Library.showAllCategory") ? 1 : 0
            return IndexPath(row: row, section: 0)
        }
        if let index = viewModel.categories.firstIndex(of: currentCategory) {
            return IndexPath(row: index, section: 1)
        }
        if let index = viewModel.filterGroups.firstIndex(where: { $0.title == currentCategory }) {
            return IndexPath(row: index, section: viewModel.categories.isEmpty ? 1 : 2)
        }
        return IndexPath(row: 0, section: 0)
    }

    private func lockedCategoryIndexPaths() -> [IndexPath] {
        var indexPaths = UserDefaults.standard.bool(forKey: "Library.showAllCategory")
            ? [IndexPath(row: 0, section: 0)]
            : []
        let lockedCategories = UserDefaults.standard.stringArray(forKey: "Library.lockedCategories") ?? []
        indexPaths += lockedCategories.compactMap { category -> IndexPath? in
            if let index = viewModel.categories.firstIndex(of: category) {
                return IndexPath(row: index, section: 1)
            }
            if let index = viewModel.filterGroups.firstIndex(where: { $0.title == category }) {
                return IndexPath(row: index, section: viewModel.categories.isEmpty ? 1 : 2)
            }
            return nil
        }
        return indexPaths
    }

    @objc private func swipeBetweenCategories(_ gestureRecognizer: UISwipeGestureRecognizer) {
        guard !isEditing else { return }
        let indexPaths = categoryIndexPaths()
        guard
            indexPaths.count > 1,
            let currentIndex = indexPaths.firstIndex(of: selectedCategoryIndexPath())
        else { return }

        let offset = gestureRecognizer.direction == .left ? 1 : -1
        let destinationIndex = currentIndex + offset
        guard indexPaths.indices.contains(destinationIndex) else { return }

        let destination = indexPaths[destinationIndex]
        let header = collectionView.supplementaryView(
            forElementKind: UICollectionView.elementKindSectionHeader,
            at: IndexPath(index: 0)
        ) as? LibraryCategorySelectionHeader
        header?.setSelectedOption(destination, animated: true)
        optionSelected(destination)
        UISelectionFeedbackGenerator().selectionChanged()
    }

    func updateHeaderLockIcons() {
        guard let header = (collectionView.supplementaryView(
            forElementKind: UICollectionView.elementKindSectionHeader, at: IndexPath(index: 0)
        ) as? LibraryCategorySelectionHeader) else {
            return
        }
        if UserDefaults.standard.bool(forKey: "Library.lockLibrary") {
            header.lockedOptions = lockedCategoryIndexPaths()
        } else {
            header.lockedOptions = []
        }
    }

    func refreshCategoryHeader() {
        // Applying the snapshot can recreate the supplementary header. Resolve
        // it only after the collection view has materialized the new layout.
        collectionView.layoutIfNeeded()
        updateHeaderCategories()
        updateHeaderLockIcons()
    }

    // update category options in header
    func updateHeaderCategories() {
        guard let header = (collectionView.supplementaryView(
            forElementKind: UICollectionView.elementKindSectionHeader, at: IndexPath(index: 0)
        ) as? LibraryCategorySelectionHeader) else {
            return
        }
        header.options = makeCategoryHeaderOptions()
        header.setSelectedOption(selectedCategoryIndexPath())
    }
}

// MARK: - Sorting and Filtering
extension LibraryViewController {
    func setSort(method: LibraryViewModel.SortMethod, ascending: Bool) {
        Task {
            await viewModel.setSort(method: method, ascending: ascending)
            updateDataSource()
            updateMoreMenu()
        }
    }

    @objc private func handleFilterEdgePan(_ gestureRecognizer: UIScreenEdgePanGestureRecognizer) {
        if gestureRecognizer.state == .recognized {
            presentFilterDrawer()
        }
    }

    @objc private func presentFilterDrawer() {
        guard presentedFilterDrawer == nil else { return }

        var sourceKeys = viewModel.sourceKeys
        for filter in viewModel.filters {
            guard let value = filter.value else { continue }
            switch filter.type {
                case .source where !sourceKeys.contains(value):
                    sourceKeys.append(value)
                default:
                    break
            }
        }

        let view = LibraryFilterDrawerView(
            filters: viewModel.filters,
            sourceKeys: sourceKeys
        ) { [weak self] filters in
            self?.applyLibraryFilters(filters)
        }
        let controller = UIHostingController(rootView: view)
        controller.modalPresentationStyle = .custom
        controller.transitioningDelegate = filterDrawerTransitioningDelegate

        let presenter: UIViewController
        if let searchController = navigationItem.searchController, searchController.isActive {
            presenter = searchController
        } else {
            presenter = self
        }
        guard presenter.presentedViewController == nil else { return }
        presentedFilterDrawer = controller
        presenter.present(controller, animated: true)
    }

    private func applyLibraryFilters(_ filters: [LibraryFilter]) {
        guard filters != viewModel.filters else { return }
        viewModel.filters = filters
        Task {
            await viewModel.loadLibrary()
            updateDataSource()
            updateMoreMenu()
        }
    }

    func filtersSubtitle() -> String? {
        guard !viewModel.filters.isEmpty else { return nil }
        var options: [String] = []
        var methods: Set<LibraryFilter.FilterMethod> = []
        for filterMethod in LibraryFilter.FilterMethod.allCases {
            // ensure we only list each method type once (e.g. for multiple source filters)
            guard methods.insert(filterMethod).inserted else {
                continue
            }
            if let filter = viewModel.filters.first(where: { $0.type == filterMethod }) {
                guard options.count < 3 else {
                    options.removeLast() // make subtitle fit in two lines
                    options.append(NSLocalizedString("AND_MORE"))
                    break
                }
                if filter.exclude {
                    options.append(String(format: NSLocalizedString("NOT_%@"), filterMethod.title))
                } else {
                    options.append(filterMethod.title)
                }
            }
        }
        return options.joined(separator: NSLocalizedString("FILTER_SEPARATOR"))
    }

    func updateMoreMenu() {
        let selectAction = UIAction(
            title: NSLocalizedString("SELECT"),
            image: UIImage(systemName: "checkmark.circle")
        ) { [weak self] _ in
            guard let self else { return }
            self.setEditing(true, animated: true)
        }

        let layoutActions = [
            UIAction(
                title: NSLocalizedString("LAYOUT_GRID"),
                image: UIImage(systemName: "square.grid.2x2"),
                state: usesListLayout ? .off : .on
            ) { [weak self] _ in
                guard let self, self.usesListLayout else { return }
                self.usesListLayout = false
                self.collectionView.setCollectionViewLayout(self.makeCollectionViewLayout(), animated: true)
                self.collectionView.reloadData()
                self.updateMoreMenu()
            },
            UIAction(
                title: NSLocalizedString("LAYOUT_LIST"),
                image: UIImage(systemName: "list.bullet"),
                state: usesListLayout ? .on : .off
            ) { [weak self] _ in
                guard let self, !self.usesListLayout else { return }
                self.usesListLayout = true
                self.collectionView.setCollectionViewLayout(self.makeCollectionViewLayout(), animated: true)
                self.collectionView.reloadData()
                self.updateMoreMenu()
            }
        ]

        let sortMenu = UIMenu(
            title: NSLocalizedString("SORT_BY"),
            subtitle: viewModel.sortMethod.title,
            image: UIImage(systemName: "arrow.up.arrow.down"),
            children: [
                UIMenu(options: .displayInline, children: LibraryViewModel.SortMethod.allCases.map { method in
                    UIAction(
                        title: method.title,
                        state: viewModel.sortMethod == method ? .on : .off
                    ) { [weak self] _ in
                        self?.setSort(method: method, ascending: false)
                    }
                }),
                UIMenu(options: .displayInline, children: [false, true].map { ascending in
                    UIAction(
                        title: ascending ? viewModel.sortMethod.ascendingTitle : viewModel.sortMethod.descendingTitle,
                        state: viewModel.sortAscending == ascending ? .on : .off
                    ) { [weak self] _ in
                        guard let self else { return }
                        self.setSort(method: self.viewModel.sortMethod, ascending: ascending)
                    }
                })
            ]
        )

        let filterAction = UIAction(
            title: NSLocalizedString("BUTTON_FILTER"),
            subtitle: filtersSubtitle(),
            image: UIImage(systemName: "line.3.horizontal.decrease")
        ) { [weak self] _ in
            Task { @MainActor in
                // Let the menu finish dismissing before presenting the drawer.
                try? await Task.sleep(nanoseconds: 100_000_000)
                self?.presentFilterDrawer()
            }
        }

        moreBarButton.menu = UIMenu(
            children: [
                UIMenu(options: .displayInline, children: [selectAction]),
                UIMenu(options: .displayInline, children: layoutActions),
                UIMenu(options: .displayInline, children: [sortMenu, filterAction])
            ]
        )

        if #available(iOS 26.0, *) {
            if !viewModel.filters.isEmpty {
                moreBarButton.isSelected = true
                moreBarButton.image = UIImage(systemName: "line.3.horizontal.decrease")?
                    .withTintColor(.white, renderingMode: .alwaysOriginal)
            } else {
                moreBarButton.isSelected = false
                moreBarButton.image = UIImage(systemName: "ellipsis")
            }
        }
    }
}

// MARK: - Listing Header Delegate
extension LibraryViewController: LibraryCategorySelectionHeaderDelegate {
    nonisolated func optionSelected(_ indexPath: IndexPath) {
        Task { @MainActor in
            if indexPath.section == 0 {
                let showAllCategory = UserDefaults.standard.bool(forKey: "Library.showAllCategory")
                if showAllCategory, indexPath.row == 0 {
                    viewModel.currentCategory = nil
                } else {
                    viewModel.currentCategory = ""
                }
            } else if indexPath.section == 1 && !viewModel.categories.isEmpty {
                viewModel.currentCategory = viewModel.categories[indexPath.row]
            } else if indexPath.section == 2 || (indexPath.section == 1 && viewModel.categories.isEmpty) {
                viewModel.currentCategory = viewModel.filterGroups[indexPath.row].title
            }
            locked = viewModel.isCategoryLocked()
            updateLockState(updateCollection: false)
            deselectAllItems()
            updateToolbar()
            updateNavbarItems()

            await viewModel.loadLibrary(
                refreshBadges: false,
                refreshCategoryAvailability: false
            )
            updateEmptyStack()
            updateDataSource(animatingDifferences: false)
        }
    }
}

// MARK: - Category Swipe Gestures
extension LibraryViewController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard let swipeGesture = gestureRecognizer as? LibraryCategorySwipeGestureRecognizer else {
            return true
        }
        guard !isEditing else { return false }

        var touchedView: UIView? = touch.view
        while let currentView = touchedView {
            if currentView is LibraryCategorySelectionHeader {
                // Horizontal movement on the tab strip itself scrolls the tabs.
                return false
            }
            touchedView = currentView.superview
        }

        // Preserve the navigation drawer's left-edge gesture.
        if swipeGesture.direction == .right, touch.location(in: view).x <= max(view.safeAreaInsets.left, 24) {
            return false
        }
        // Preserve the filter drawer's right-edge gesture.
        if swipeGesture.direction == .left,
           touch.location(in: view).x >= view.bounds.width - max(view.safeAreaInsets.right, 24) {
            return false
        }
        return true
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        gestureRecognizer is LibraryCategorySwipeGestureRecognizer
            || otherGestureRecognizer is LibraryCategorySwipeGestureRecognizer
    }
}

// MARK: - Collection View Delegate
extension LibraryViewController {
    // support two finger drag to select
    func collectionView(_ collectionView: UICollectionView, shouldBeginMultipleSelectionInteractionAt indexPath: IndexPath) -> Bool {
        true
    }

    func collectionView(_ collectionView: UICollectionView, didBeginMultipleSelectionInteractionAt indexPath: IndexPath) {
        setEditing(true, animated: true)
    }

    override func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard let info = dataSource.itemIdentifier(for: indexPath) else { return }

        if isEditing {
            let cell = collectionView.cellForItem(at: indexPath)
            guard let cell else { return }
            if let cell = cell as? MangaGridCell {
                cell.setSelected(true)
            } else if let cell = cell as? MangaListCell {
                cell.setSelected(true)
            }
            if #available(iOS 17.5, *) {
                UISelectionFeedbackGenerator().selectionChanged(at: cell.center)
            } else {
                UISelectionFeedbackGenerator().selectionChanged()
            }
            updateNavbarItems()
            updateToolbar()
            return
        }

        if UserDefaults.standard.bool(forKey: "Library.opensReaderView") {
            Task {
                // get next chapter to read
                let history = await CoreDataManager.shared.getReadingHistory(
                    sourceId: info.sourceId,
                    mangaId: info.mangaId
                )
                let chapters = await CoreDataManager.shared.getChapters(sourceId: info.sourceId, mangaId: info.mangaId)
                    .map { $0.toNew() }

                let filters = CoreDataManager.shared.getMangaChapterFilters(
                    sourceId: info.sourceId,
                    mangaId: info.mangaId
                )
                let sortOption = ChapterSortOption(flags: filters.flags)
                let sortAscending = ChapterFlagMask.sortAscending(filters.flags)

                let sortedChapters: [ExtensionRunner.Chapter] = {
                    switch sortOption {
                        case .sourceOrder:
                            return sortAscending ? chapters.reversed() : chapters
                        case .chapter:
                            return chapters.sorted {
                                let lhs = $0.chapterNumber ?? -1
                                let rhs = $1.chapterNumber ?? -1
                                return sortAscending ? lhs < rhs : lhs > rhs
                            }
                        case .uploadDate:
                            return chapters.sorted {
                                let lhs = $0.dateUploaded ?? .distantPast
                                let rhs = $1.dateUploaded ?? .distantPast
                                return sortAscending ? lhs < rhs : lhs > rhs
                            }
                    }
                }()

                let manga = ExtensionRunner.Manga(
                    sourceKey: info.sourceId,
                    key: info.mangaId,
                    title: info.title ?? "",
                    chapters: sortedChapters
                )

                let nextChapter = MangaManager.shared.getNextChapter(
                    manga: manga,
                    chapters: sortedChapters,
                    readingHistory: history,
                    sortAscending: sortAscending
                )

                if let chapter = nextChapter {
                    // open reader view
                    guard let source = SourceManager.shared.source(for: info.sourceId) else {
                        return
                    }
                    let manga = ExtensionRunner.Manga(
                        sourceKey: info.sourceId,
                        key: info.mangaId,
                        title: info.title ?? "",
                        chapters: sortedChapters
                    )
                    let readerController = ReaderViewController(
                        source: source,
                        manga: manga,
                        chapter: chapter
                    )
                    let navigationController = ReaderNavigationController(
                        readerViewController: readerController,
                        mangaInfo: info
                    )
                    if #available(iOS 18.0, *) {
                        navigationController.preferredTransition = .zoom { context in
                            guard
                                let navigationController = context.zoomedViewController as? ReaderNavigationController,
                                let info = navigationController.mangaInfo,
                                let indexPath = self.dataSource.indexPath(for: info),
                                let cell = self.collectionView.cellForItem(at: indexPath)
                            else {
                                return nil
                            }
                            if let cell = cell as? MangaListCell {
                                return cell.coverImageView
                            } else {
                                return cell.contentView
                            }
                        }
                    }
                    navigationController.modalPresentationStyle = .fullScreen
                    present(navigationController, animated: true)
                } else {
                    // no chapter to read, open manga page
                    let indexPath = dataSource.indexPath(for: info) ?? indexPath // get new index path in case it changed
                    super.collectionView(collectionView, didSelectItemAt: indexPath)
                }
            }
        } else {
            super.collectionView(collectionView, didSelectItemAt: indexPath)
        }

        if !UserDefaults.standard.bool(forKey: "General.incognitoMode") {
            Task {
                await CoreDataManager.shared.setOpened(sourceId: info.sourceId, mangaId: info.mangaId)
                await self.viewModel.mangaOpened(sourceId: info.sourceId, mangaId: info.mangaId)
                self.updateDataSource()
            }
        }

        collectionView.deselectItem(at: indexPath, animated: true)
    }

    func collectionView(_ collectionView: UICollectionView, didDeselectItemAt indexPath: IndexPath) {
        if isEditing {
            let cell = collectionView.cellForItem(at: indexPath)
            if let cell = cell as? MangaGridCell {
                cell.setSelected(false)
            } else if let cell = cell as? MangaListCell {
                cell.setSelected(false)
            }
            updateNavbarItems()
            updateToolbar()
        }
    }

    // don't highlighting when selecting during editing
    override func collectionView(_ collectionView: UICollectionView, didHighlightItemAt indexPath: IndexPath) {
        guard !isEditing else { return }
        super.collectionView(collectionView, didHighlightItemAt: indexPath)
    }

    private func mangaInfo(at path: IndexPath) -> MangaInfo {
        let manga: [MangaInfo] = if path.section == 0 && !viewModel.pinnedManga.isEmpty {
            viewModel.pinnedManga
        } else {
            viewModel.manga
        }

        return manga[path.row]
    }

    func collectionView(
        _ collectionView: UICollectionView,
        contextMenuConfigurationForItemsAt indexPaths: [IndexPath],
        point: CGPoint
    ) -> UIContextMenuConfiguration? {
        guard let indexPath = indexPaths.first else { return nil }

        let manga = mangaInfo(at: indexPath)
        let mangaInfo = indexPaths.map(mangaInfo(at:))

        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ -> UIMenu? in
            var actions: [UIMenuElement] = []
            let singleAttributes = mangaInfo.count > 1
                ? .disabled
                : UIMenuElement.Attributes()

            if let url = manga.url {
                actions.append(UIMenu(identifier: .share, options: .displayInline, children: [
                    UIAction(
                        title: NSLocalizedString("SHARE"),
                        image: UIImage(systemName: "square.and.arrow.up"),
                        attributes: singleAttributes
                    ) { _ in
                        let activityViewController = UIActivityViewController(
                            activityItems: [url],
                            applicationActivities: nil
                        )
                        activityViewController.popoverPresentationController?.sourceView = self.view
                        activityViewController.popoverPresentationController?.sourceRect = collectionView.cellForItem(at: indexPath)?.frame ?? .zero

                        self.present(activityViewController, animated: true)
                    }
                ]))
            }

            if UserDefaults.standard.bool(forKey: "Library.opensReaderView"), mangaInfo.count == 1 {
                actions.append(UIAction(
                    title: NSLocalizedString("MANGA_INFO"),
                    image: UIImage(systemName: "info.circle"),
                    attributes: singleAttributes
                ) { _ in
                    self.openInfoView(info: mangaInfo[0], zoom: false)
                })
            }

            if !self.viewModel.categories.isEmpty {
                actions.append(UIAction(
                    title: NSLocalizedString("EDIT_CATEGORIES"),
                    image: UIImage(systemName: "folder.badge.gearshape"),
                    attributes: singleAttributes
                ) { _ in
                    let manga = manga.toManga()
                    self.present(
                        UINavigationController(
                            rootViewController: CategorySelectViewController(
                                manga: manga.toNew()
                            )
                        ),
                        animated: true
                    )
                })
            }

            actions.append(UIAction(
                title: NSLocalizedString("MIGRATE"),
                image: UIImage(systemName: "arrow.left.arrow.right")
            ) { _ in
                let manga = mangaInfo.map { $0.toManga().toNew() }
                let migrateView = MigrateSelectDestinationView(
                    selectedSeries: manga,
                    selectedSources: manga.count == 1
                        ? SourceManager.shared.source(for: manga[0].sourceKey).flatMap { [$0.toInfo()] } ?? []
                        : []
                )
                let viewController = SwiftUINavigationViewController(rootView: migrateView)
                self.present(viewController, animated: true)
            })

            var bottomMenuChildren: [UIMenuElement] = []

            bottomMenuChildren.append(UIMenu(title: NSLocalizedString("MARK_ALL"), image: nil, children: [
                // read chapters
                UIAction(title: NSLocalizedString("READ"), image: UIImage(systemName: "checkmark.circle")) { _ in
                    (UIApplication.shared.delegate as? AppDelegate)?.showLoadingIndicator()

                    Task {
                        for manga in mangaInfo {
                            let manga = manga.toManga()
                            let chapters = await CoreDataManager.shared.getChapters(sourceId: manga.sourceId, mangaId: manga.id)

                            await HistoryManager.shared.addHistory(
                                sourceId: manga.sourceId,
                                mangaId: manga.id,
                                chapters: chapters.map { $0.toNew() }
                            )
                        }

                        await (UIApplication.shared.delegate as? AppDelegate)?.hideLoadingIndicator()
                    }
                },
                // unread chapters
                UIAction(title: NSLocalizedString("UNREAD"), image: UIImage(systemName: "minus.circle")) { _ in
                    (UIApplication.shared.delegate as? AppDelegate)?.showLoadingIndicator()

                    Task {
                        for manga in mangaInfo {
                            let manga = manga.toManga()
                            let chapters = await CoreDataManager.shared.getChapters(sourceId: manga.sourceId, mangaId: manga.id)

                            await HistoryManager.shared.removeHistory(
                                sourceId: manga.sourceId,
                                mangaId: manga.id,
                                chapterIds: chapters.map { $0.id }
                            )
                        }

                        await (UIApplication.shared.delegate as? AppDelegate)?.hideLoadingIndicator()
                    }
                }
            ]))

            let downloadAllAction = UIAction(title: NSLocalizedString("ALL")) { _ in
                if UserDefaults.standard.bool(forKey: "Library.downloadOnlyOnWifi") &&
                    Reachability.getConnectionType() == .wifi ||
                    !UserDefaults.standard.bool(forKey: "Library.downloadOnlyOnWifi") {
                    Task {
                        for mangaInfo in mangaInfo {
                            await DownloadManager.shared.downloadAll(manga: mangaInfo.toManga().toNew())
                        }
                    }
                } else {
                    self.presentAlert(
                        title: NSLocalizedString("NO_WIFI_ALERT_TITLE"),
                        message: NSLocalizedString("NO_WIFI_ALERT_MESSAGE")
                    )
                }
            }

            let downloadUnreadAction = UIAction(title: NSLocalizedString("UNREAD")) { _ in
                if UserDefaults.standard.bool(forKey: "Library.downloadOnlyOnWifi") &&
                    Reachability.getConnectionType() == .wifi ||
                    !UserDefaults.standard.bool(forKey: "Library.downloadOnlyOnWifi") {
                    Task {
                        for manga in mangaInfo {
                            await DownloadManager.shared.downloadUnread(manga: manga.toManga().toNew())
                        }
                    }
                } else {
                    self.presentAlert(
                        title: NSLocalizedString("NO_WIFI_ALERT_TITLE"),
                        message: NSLocalizedString("NO_WIFI_ALERT_MESSAGE")
                    )
                }
            }

            if manga.sourceId != "local" /* local sources parked; see Vendored/_excluded/Local */ && SourceManager.shared.hasSourceInstalled(id: manga.sourceId) {
                bottomMenuChildren.append(UIMenu(
                    title: NSLocalizedString("DOWNLOAD"),
                    image: UIImage(systemName: "arrow.down.circle"),
                    children: [downloadAllAction, downloadUnreadAction]
                ))
            }

            if self.viewModel.isInRealCategory {
                bottomMenuChildren.append(UIAction(
                    title: NSLocalizedString("REMOVE_FROM_CATEGORY"),
                    image: UIImage(systemName: "folder.badge.minus"),
                    attributes: .destructive
                ) { _ in
                    self.removeFromCategory(mangaInfo: mangaInfo)
                })
            }

            bottomMenuChildren.append(UIAction(
                title: NSLocalizedString("REMOVE_FROM_LIBRARY"),
                image: UIImage(systemName: "trash"),
                attributes: .destructive
            ) { _ in
                self.removeFromLibrary(mangaInfo: mangaInfo)
            })

            actions.append(UIMenu(options: .displayInline, children: bottomMenuChildren))

            return UIMenu(title: "", children: actions)
        }
    }

    func collectionView(
        _ collectionView: UICollectionView,
        contextMenuConfigurationForItemAt indexPath: IndexPath,
        point: CGPoint
    ) -> UIContextMenuConfiguration? {
        self.collectionView(collectionView, contextMenuConfigurationForItemsAt: [indexPath], point: point)
    }
}

// MARK: - Search Results
extension LibraryViewController: UISearchResultsUpdating {
    func updateSearchResults(for searchController: UISearchController) {
        guard searchController.searchBar.text != lastSearch else { return }
        lastSearch = searchController.searchBar.text
        Task {
            await viewModel.search(query: searchController.searchBar.text ?? "")
            updateDataSource()
        }
    }
}

// MARK: - Undoable Methods
extension LibraryViewController {
    @discardableResult
    func removeFromLibrary(mangaInfo: [MangaInfo]) -> Task<Void, Never>? {
        guard !mangaInfo.isEmpty else { return nil }
        let mangaCount = mangaInfo.count
        let actionName =
            mangaCount > 1
            ? String(
                format: NSLocalizedString("REMOVING_%i_ITEMS_FROM_LIBRARY"), mangaCount
            ) : NSLocalizedString("REMOVING_(ONE)_ITEM_FROM_LIBRARY")
        undoManager.setActionName(actionName)

        return Task {
            // Update the in-memory snapshot immediately with a single O(n)
            // filter, then perform one background Core Data transaction.
            viewModel.removeFromLibrary(manga: mangaInfo)
            // Animating hundreds of simultaneous diffable-data-source deletes
            // can monopolize the main thread even though the model update is
            // cheap. Keep the animation for the normal single-title action.
            updateDataSource(animatingDifferences: mangaInfo.count == 1)

            let snapshots = await MangaManager.shared.removeFromLibrary(
                manga: mangaInfo.map(\.identifier)
            )
            guard !snapshots.isEmpty else {
                await viewModel.loadLibrary()
                updateDataSource()
                return
            }

            undoManager.registerUndo(withTarget: self) { target in
                target.undoManager.registerUndo(withTarget: target) {
                    redoTarget in
                    redoTarget.removeFromLibrary(mangaInfo: mangaInfo)
                }

                Task {
                    await MangaManager.shared.restoreLibraryMembership(
                        snapshots
                    )
                }
            }
        }
    }

    @discardableResult
    func removeFromCategory(mangaInfo: [MangaInfo]) -> Task<Void, Never>? {
        guard let currentCategory = viewModel.currentCategory else { return nil }
        let mangaCount = mangaInfo.count
        let actionName =
            mangaCount > 1
            ? String(
                format: NSLocalizedString("REMOVING_%i_ITEMS_FROM_CATEGORY_%@"),
                mangaCount, currentCategory)
            : String(
                format: NSLocalizedString("REMOVING_(ONE)_ITEM_FROM_CATEGORY_%@"),
                currentCategory)
        undoManager.setActionName(actionName)

        undoManager.registerUndo(withTarget: self) { target in
            target.undoManager.registerUndo(withTarget: target) { redoTarget in
                redoTarget.removeFromCategory(mangaInfo: mangaInfo)
            }

            Task {
                await target.viewModel.addToCurrentCategory(manga: mangaInfo)
                NotificationCenter.default.post(name: .updateMangaCategories, object: nil)
            }
        }

        return Task {
            await viewModel.removeFromCurrentCategory(manga: mangaInfo)
            updateDataSource(animatingDifferences: mangaInfo.count == 1)
            NotificationCenter.default.post(name: .updateMangaCategories, object: nil)
        }
    }
}
