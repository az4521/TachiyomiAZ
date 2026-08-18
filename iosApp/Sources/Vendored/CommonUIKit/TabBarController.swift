//
//  TabBarController.swift
//  Aidoku
//
//  Created by Skitty on 7/26/25.
//

import Combine
import SwiftUI

class TabBarController: UITabBarController {
    private struct DrawerDestination {
        let title: String
        let symbol: String
    }

    private let drawerDestinations = [
        DrawerDestination(title: NSLocalizedString("LIBRARY"), symbol: "books.vertical.fill"),
        DrawerDestination(
            title: NSLocalizedString(
                "RECENT_UPDATES",
                value: "Recent Updates",
                comment: "Recent updates drawer destination"
            ),
            symbol: "bell.fill"
        ),
        DrawerDestination(
            title: NSLocalizedString(
                "READING_HISTORY",
                value: "Reading History",
                comment: "Reading history drawer destination"
            ),
            symbol: "clock.fill"
        ),
        DrawerDestination(title: NSLocalizedString("BROWSE"), symbol: "globe"),
        DrawerDestination(
            title: NSLocalizedString(
                "EXTENSIONS",
                value: "Extensions",
                comment: "Extensions drawer destination"
            ),
            symbol: "shippingbox.fill"
        ),
        DrawerDestination(
            title: NSLocalizedString("DOWNLOAD_QUEUE"),
            symbol: "arrow.down.circle.fill"
        ),
        DrawerDestination(title: NSLocalizedString("SETTINGS"), symbol: "gear")
    ]

    private var originalFrame: CGRect = .zero
    private var shrunkFrame: CGRect = .zero
    private var cancellables: [AnyCancellable] = []

    private var settingsPath: NavigationCoordinator?
    private var drawerControllers: [UIViewController] = []
    private var selectedDrawerIndex = 0
    private var previousSelectedIndex: Int?
    private var drawerLeadingConstraint: NSLayoutConstraint?
    private var drawerButtons: [UIButton] = []
    private weak var drawerHeaderView: UIView?
    private var isDrawerOpen = false

    private lazy var drawerBackdrop: UIControl = {
        let view = UIControl()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.32)
        view.alpha = 0
        view.isHidden = true
        view.translatesAutoresizingMaskIntoConstraints = false
        view.addTarget(self, action: #selector(closeDrawer), for: .touchUpInside)
        return view
    }()

    private lazy var drawerView: UIView = {
        let view = UIView()
        view.backgroundColor = .systemBackground
        view.layer.shadowColor = UIColor.black.cgColor
        view.layer.shadowOpacity = 0.28
        view.layer.shadowRadius = 12
        view.layer.shadowOffset = CGSize(width: 4, height: 0)
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }()

    private lazy var libraryProgressView = CircularProgressView(frame: CGRect(x: 0, y: 0, width: 20, height: 20))

    private lazy var libraryRefreshTitleLabel: UILabel = {
        let label = UILabel()
        label.text = NSLocalizedString("REFRESHING_LIBRARY")
        label.font = .preferredFont(forTextStyle: .subheadline)
        label.adjustsFontForContentSizeCategory = true
        return label
    }()

    private lazy var libraryRefreshDetailLabel: UILabel = {
        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.textColor = .secondaryLabel
        label.adjustsFontForContentSizeCategory = true
        return label
    }()

    private lazy var libraryRefreshAccessory: UIView = {
        let view = UIView()

        let labelStack = UIStackView(arrangedSubviews: [
            libraryRefreshTitleLabel,
            libraryRefreshDetailLabel
        ])
        labelStack.axis = .vertical
        labelStack.alignment = .leading
        labelStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(labelStack)

        libraryProgressView.radius = 12
        libraryProgressView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(libraryProgressView)

        if #unavailable(iOS 26) {
            // add styling for older versions without the bottom accessory view
            let backgroundView = UIVisualEffectView(effect: UIBlurEffect(style: .systemMaterial))
            backgroundView.layer.cornerRadius = 48 / 2
            backgroundView.layer.borderColor = UIColor.quaternarySystemFill.cgColor
            backgroundView.layer.borderWidth = 1
            backgroundView.clipsToBounds = true
            backgroundView.translatesAutoresizingMaskIntoConstraints = false
            view.insertSubview(backgroundView, at: 0)

            NSLayoutConstraint.activate([
                backgroundView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                backgroundView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                backgroundView.topAnchor.constraint(equalTo: view.topAnchor),
                backgroundView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
            ])
        }

        NSLayoutConstraint.activate([
            labelStack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            labelStack.trailingAnchor.constraint(equalTo: libraryProgressView.leadingAnchor, constant: -16),
            labelStack.centerYAnchor.constraint(equalTo: view.centerYAnchor),

            libraryProgressView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            libraryProgressView.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            libraryProgressView.widthAnchor.constraint(equalToConstant: 20),
            libraryProgressView.heightAnchor.constraint(equalToConstant: 20)
        ])

        return view
    }()

    override func viewDidLoad() {
        super.viewDidLoad()

        let libraryViewController = NavigationController(rootViewController: LibraryViewController())
        let browseViewController = NavigationController(rootViewController: BrowseViewController())

        let updatesViewController = makeMangaUpdatesViewController()

        let historyPath = NavigationCoordinator(rootViewController: nil)
        let historyHostingController = UIHostingController(
            rootView: HistoryView()
                .environmentObject(historyPath)
                .appTheme()
        )
        historyPath.rootViewController = historyHostingController
        let historyViewController = NavigationController(rootViewController: historyHostingController)

        let extensionsPath = NavigationCoordinator(rootViewController: nil)
        let extensionsHostingController = UIHostingController(
            rootView: ExtensionManagementView()
                .environmentObject(extensionsPath)
                .appTheme()
        )
        extensionsPath.rootViewController = extensionsHostingController
        let extensionsViewController = NavigationController(
            rootViewController: extensionsHostingController
        )

        let downloadQueueHostingController = UIHostingController(
            rootView: DownloadQueueView(embeddedInNavigationController: true)
                .appTheme()
        )
        let downloadQueueViewController = NavigationController(
            rootViewController: downloadQueueHostingController
        )

        let settingsPath = NavigationCoordinator(rootViewController: nil)
        let settingsHostingController = UIHostingController(
            rootView: SettingsView()
                .environmentObject(settingsPath)
                .appTheme()
        )
        let settingsViewController = NavigationController(
            rootViewController: settingsHostingController
        )
        settingsViewController.navigationBar.prefersLargeTitles = true
        settingsPath.rootViewController = settingsViewController
        self.settingsPath = settingsPath

        libraryViewController.navigationBar.prefersLargeTitles = true
        updatesViewController.navigationBar.prefersLargeTitles = true
        browseViewController.navigationBar.prefersLargeTitles = true
        historyViewController.navigationBar.prefersLargeTitles = true
        extensionsViewController.navigationBar.prefersLargeTitles = true
        downloadQueueViewController.navigationBar.prefersLargeTitles = true

        let controllers = [
            libraryViewController,
            updatesViewController,
            historyViewController,
            browseViewController,
            extensionsViewController,
            downloadQueueViewController,
            settingsViewController
        ]
        for (index, controller) in controllers.enumerated() {
            let destination = drawerDestinations[index]
            controller.tabBarItem = UITabBarItem(
                title: destination.title,
                image: UIImage(systemName: destination.symbol),
                tag: index
            )
            controller.viewControllers.first?.navigationItem.leftBarButtonItem =
                makeDrawerBarButtonItem()
        }
        drawerControllers = controllers
        // UITabBarController automatically replaces destinations after the
        // fifth with a "More" navigation controller. The tab bar is only a
        // container here, so attach one drawer destination at a time.
        setViewControllers([controllers[0]], animated: false)
        tabBar.isHidden = true
        configureDrawer()

        let updateCount = UserDefaults.standard.integer(forKey: "Browse.updateCount")
        browseViewController.tabBarItem.badgeValue = updateCount > 0 ? String(updateCount) : nil

        NotificationCenter.default.publisher(for: .incognitoMode)
            .sink { [weak self] _ in
                self?.updateFrame(animated: true)
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: .accentColorSetting)
            .sink { [weak self] notification in
                let color = notification.object as? UIColor ?? AppAccentColor.uiColor
                self?.view.tintColor = color
                self?.drawerView.tintColor = color
                self?.drawerHeaderView?.backgroundColor = color
                self?.updateDrawerSelection()
            }
            .store(in: &cancellables)
    }

    private func configureDrawer() {
        view.addSubview(drawerBackdrop)
        view.addSubview(drawerView)

        let width = min(CGFloat(304), view.bounds.width - 56)
        let leading = drawerView.leadingAnchor.constraint(
            equalTo: view.leadingAnchor,
            constant: -width - 16
        )
        drawerLeadingConstraint = leading
        NSLayoutConstraint.activate([
            drawerBackdrop.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            drawerBackdrop.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            drawerBackdrop.topAnchor.constraint(equalTo: view.topAnchor),
            drawerBackdrop.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            leading,
            drawerView.topAnchor.constraint(equalTo: view.topAnchor),
            drawerView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            drawerView.widthAnchor.constraint(equalToConstant: width)
        ])

        let header = UIView()
        header.backgroundColor = AppAccentColor.uiColor
        header.translatesAutoresizingMaskIntoConstraints = false
        drawerView.addSubview(header)
        drawerHeaderView = header

        let appName = UILabel()
        appName.text = "TachiyomiAZ"
        appName.textColor = .white
        appName.font = .systemFont(ofSize: 24, weight: .medium)
        appName.translatesAutoresizingMaskIntoConstraints = false
        header.addSubview(appName)

        let subtitle = UILabel()
        subtitle.text = "Manga reader"
        subtitle.textColor = UIColor.white.withAlphaComponent(0.78)
        subtitle.font = .systemFont(ofSize: 14)
        subtitle.translatesAutoresizingMaskIntoConstraints = false
        header.addSubview(subtitle)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false
        drawerView.addSubview(stack)

        drawerButtons = drawerDestinations.enumerated().map { index, destination in
            var configuration = UIButton.Configuration.plain()
            configuration.title = destination.title
            configuration.image = UIImage(systemName: destination.symbol)
            configuration.imagePadding = 32
            configuration.contentInsets = .init(
                top: 0,
                leading: 16,
                bottom: 0,
                trailing: 16
            )
            let button = UIButton(configuration: configuration)
            button.tag = index
            button.contentHorizontalAlignment = .leading
            button.heightAnchor.constraint(equalToConstant: 48).isActive = true
            button.addTarget(
                self,
                action: #selector(selectDrawerDestination),
                for: .touchUpInside
            )
            stack.addArrangedSubview(button)
            return button
        }

        NSLayoutConstraint.activate([
            header.leadingAnchor.constraint(equalTo: drawerView.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: drawerView.trailingAnchor),
            header.topAnchor.constraint(equalTo: drawerView.topAnchor),
            header.heightAnchor.constraint(equalToConstant: 168),
            appName.leadingAnchor.constraint(equalTo: header.leadingAnchor, constant: 16),
            appName.bottomAnchor.constraint(equalTo: subtitle.topAnchor, constant: -4),
            subtitle.leadingAnchor.constraint(equalTo: appName.leadingAnchor),
            subtitle.bottomAnchor.constraint(equalTo: header.bottomAnchor, constant: -20),
            stack.leadingAnchor.constraint(equalTo: drawerView.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: drawerView.trailingAnchor),
            stack.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8)
        ])

        let edgePan = UIScreenEdgePanGestureRecognizer(
            target: self,
            action: #selector(handleEdgePan)
        )
        edgePan.edges = .left
        view.addGestureRecognizer(edgePan)

        let closeSwipe = UISwipeGestureRecognizer(
            target: self,
            action: #selector(handleDrawerCloseSwipe)
        )
        closeSwipe.direction = .left
        // Attach this to the drawer itself. A recognizer on the backdrop only
        // receives gestures that begin outside the drawer's bounds.
        drawerView.addGestureRecognizer(closeSwipe)
        updateDrawerSelection()
    }

    func makeDrawerBarButtonItem() -> UIBarButtonItem {
        UIBarButtonItem(
            image: UIImage(systemName: "line.3.horizontal"),
            style: .plain,
            target: self,
            action: #selector(openDrawer)
        )
    }

    @objc private func openDrawer() {
        guard !isDrawerOpen else { return }
        isDrawerOpen = true
        drawerBackdrop.isHidden = false
        drawerLeadingConstraint?.constant = 0
        UIView.animate(
            withDuration: 0.25,
            delay: 0,
            options: [.curveEaseOut, .beginFromCurrentState]
        ) {
            self.drawerBackdrop.alpha = 1
            self.view.layoutIfNeeded()
        }
    }

    @objc private func closeDrawer() {
        guard isDrawerOpen else { return }
        isDrawerOpen = false
        drawerLeadingConstraint?.constant = -(drawerView.bounds.width + 16)
        UIView.animate(
            withDuration: 0.2,
            delay: 0,
            options: [.curveEaseIn, .beginFromCurrentState]
        ) {
            self.drawerBackdrop.alpha = 0
            self.view.layoutIfNeeded()
        } completion: { _ in
            self.drawerBackdrop.isHidden = true
        }
    }

    @objc private func selectDrawerDestination(_ sender: UIButton) {
        activateDrawerDestination(at: sender.tag)
        closeDrawer()
    }

    private func activateDrawerDestination(at index: Int) {
        guard drawerControllers.indices.contains(index) else { return }
        selectedDrawerIndex = index
        // Manga updates used to be pushed from the Library bell. Recreate that
        // screen when it is selected from the drawer so its SwiftUI loading
        // state and navigation coordinator always start in the same known-good
        // state as the former Library entry point.
        if index == 1 {
            drawerControllers[index] = makeMangaUpdatesViewController()
        }
        let controller = drawerControllers[index]
        if selectedViewController !== controller {
            setViewControllers([controller], animated: false)
            view.bringSubviewToFront(drawerBackdrop)
            view.bringSubviewToFront(drawerView)
        }
        checkForSettingsPop()
        updateDrawerSelection()
    }

    private func makeMangaUpdatesViewController() -> NavigationController {
        let path = NavigationCoordinator(rootViewController: nil)
        let hostingController = UIHostingController(
            rootView: MangaUpdatesView()
                .environmentObject(path)
                .appTheme()
        )
        path.rootViewController = hostingController

        let navigationController = NavigationController(
            rootViewController: hostingController
        )
        navigationController.navigationBar.prefersLargeTitles = true
        navigationController.tabBarItem = UITabBarItem(
            title: drawerDestinations[1].title,
            image: UIImage(systemName: drawerDestinations[1].symbol),
            tag: 1
        )
        hostingController.navigationItem.leftBarButtonItem =
            makeDrawerBarButtonItem()
        return navigationController
    }

    @objc private func handleEdgePan(_ gesture: UIScreenEdgePanGestureRecognizer) {
        if gesture.state == .recognized {
            openDrawer()
        }
    }

    @objc private func handleDrawerCloseSwipe(_ gesture: UISwipeGestureRecognizer) {
        guard gesture.state == .recognized else { return }
        closeDrawer()
    }

    private func updateDrawerSelection() {
        for (index, button) in drawerButtons.enumerated() {
            guard var configuration = button.configuration else { continue }
            let isSelected = index == selectedDrawerIndex
            configuration.baseForegroundColor = isSelected
                ? AppAccentColor.uiColor
                : .label
            var background = configuration.background
            background.backgroundColor = isSelected
                ? AppAccentColor.uiColor.withAlphaComponent(0.12)
                : .clear
            configuration.background = background
            button.configuration = configuration
            button.accessibilityTraits = isSelected ? [.button, .selected] : .button
        }
    }

    func updateFrame(animated: Bool = false) {
        if originalFrame == .zero {
            let bannerHeight = (UIApplication.shared.connectedScenes.first?.delegate as? SceneDelegate)?.totalBannerHeight ?? 0
            originalFrame = view.frame
            shrunkFrame = .init(
                x: originalFrame.origin.x,
                y: originalFrame.origin.y + bannerHeight,
                width: originalFrame.width,
                height: originalFrame.height - bannerHeight
            )
        }
        func commit() {
            if UserDefaults.standard.bool(forKey: "General.incognitoMode") {
                view.frame = shrunkFrame
            } else {
                view.frame = originalFrame
            }
        }
        if animated {
            UIView.animate(withDuration: CATransaction.animationDuration()) {
                commit()
            }
        } else {
            commit()
        }
    }
}

extension TabBarController {
    func showLibraryRefreshView() {
        libraryRefreshDetailLabel.text = NotificationManager.calculatingLibraryRefreshDetail
        libraryProgressView.setProgress(value: 0, withAnimation: false)
        libraryRefreshAccessory.layer.opacity = 0
        view.insertSubview(libraryRefreshAccessory, belowSubview: drawerBackdrop)
        UIView.animate(withDuration: 0.5) {
            self.libraryRefreshAccessory.layer.opacity = 1
        }
    }

    func setLibraryRefreshProgress(_ progress: LibraryRefreshProgress) {
        libraryRefreshDetailLabel.text = progress.localizedDetail
        libraryProgressView.setProgress(value: progress.fractionCompleted, withAnimation: true)
    }

    func hideAccessoryView() {
        UIView.animate(withDuration: 0.5) {
            self.libraryRefreshAccessory.layer.opacity = 0
        } completion: { _ in
            self.libraryRefreshAccessory.removeFromSuperview()
        }
    }

    override func viewDidLayoutSubviews() {
        let height: CGFloat = 48
        let padding: CGFloat = 16
        libraryRefreshAccessory.frame = CGRect(
            x: view.safeAreaInsets.left + padding,
            y: view.bounds.height - view.safeAreaInsets.bottom - height - padding,
            width: view.bounds.width - padding * 2 - view.safeAreaInsets.left - view.safeAreaInsets.right,
            height: height
        )
        updateFrame()
    }

    override func viewWillTransition(to size: CGSize, with coordinator: any UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        originalFrame = .init(origin: self.originalFrame.origin, size: size)
        shrunkFrame = self.originalFrame
        coordinator.animate { _ in
            self.view.setNeedsLayout()
        } completion: { _ in
            let bannerHeight = (UIApplication.shared.connectedScenes.first?.delegate as? SceneDelegate)?.totalBannerHeight ?? 0
            self.shrunkFrame = .init(
                x: self.originalFrame.origin.x,
                y: self.originalFrame.origin.y + bannerHeight,
                width: self.originalFrame.width,
                height: self.originalFrame.height - bannerHeight
            )
            self.updateFrame(animated: true)
        }
    }
}

extension TabBarController {
    private func checkForSettingsPop() {
        let settingsIndex = 6
        if
            selectedDrawerIndex == previousSelectedIndex,
            previousSelectedIndex == settingsIndex
        {
            settingsPath?.navigationController?.popToRootViewController(animated: true)
        }
        previousSelectedIndex = selectedDrawerIndex
    }
}

// MARK: - Keyboard Shortcuts
extension TabBarController {
    override var keyCommands: [UIKeyCommand]? {
        drawerDestinations.enumerated().map { index, destination in
            UIKeyCommand(
                title: destination.title,
                action: #selector(selectTab),
                input: "\(index + 1)",
                modifierFlags: .shiftOrCommand,
                alternates: [],
                attributes: [],
                state: .off
            )
        }
    }

    @objc private func selectTab(sender: UIKeyCommand) {
        guard
            let input = sender.input,
            let newIndex = Int(input),
            newIndex >= 1 && newIndex <= drawerControllers.count
        else { return }
        activateDrawerDestination(at: newIndex - 1)
    }

    override var canBecomeFirstResponder: Bool { true }
}
