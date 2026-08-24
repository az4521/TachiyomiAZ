//
//  LibraryCategorySelectionHeader.swift
//  Aidoku
//
//  Created by Skitty on 2/25/26.
//

import SwiftUI
import UIKit

protocol LibraryCategorySelectionHeaderDelegate: AnyObject {
    func optionSelected(_ indexPath: IndexPath)
}

/// A Material-style, horizontally scrolling tab strip for library categories.
class LibraryCategorySelectionHeader: UICollectionReusableView {
    weak var delegate: LibraryCategorySelectionHeaderDelegate?

    struct Section {
        var title: String?
        var options: [String] = []
        /// What the user sees. `options` deliberately remains the stable category/group key used
        /// by selection, locking and persistence.
        var labels: [String]? = nil
    }

    var options: [Section] = [] {
        didSet {
            if !contains(selectedIndexPath) {
                selectedIndexPath = IndexPath(row: 0, section: 0)
            }
            rebuildTabs()
        }
    }

    var lockedOptions: [IndexPath] = [] {
        didSet { rebuildTabs() }
    }

    private let scrollView = UIScrollView()
    private let stackView = UIStackView()
    private let selectionIndicator = UIView()
    private let bottomDivider = UIView()

    private var tabButtons: [UIButton] = []
    private var tabIndexPaths: [IndexPath] = []
    private var indicatorHorizontalConstraints: [NSLayoutConstraint] = []
    private weak var indicatorButton: UIButton?
    private var selectedIndexPath = IndexPath(row: 0, section: 0)
    private var animateNextIndicatorUpdate = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
        constrain()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateSelectionIndicator(animated: animateNextIndicatorUpdate)
        animateNextIndicatorUpdate = false
    }

    private func configure() {
        backgroundColor = .systemBackground

        scrollView.alwaysBounceHorizontal = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.contentInsetAdjustmentBehavior = .never
        addSubview(scrollView)

        stackView.axis = .horizontal
        stackView.alignment = .fill
        stackView.distribution = .fill
        stackView.spacing = 0
        scrollView.addSubview(stackView)

        selectionIndicator.backgroundColor = tintColor
        selectionIndicator.layer.cornerRadius = 1.5
        stackView.addSubview(selectionIndicator)

        bottomDivider.backgroundColor = .separator
        addSubview(bottomDivider)
    }

    private func constrain() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        stackView.translatesAutoresizingMaskIntoConstraints = false
        selectionIndicator.translatesAutoresizingMaskIntoConstraints = false
        bottomDivider.translatesAutoresizingMaskIntoConstraints = false

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomAnchor),

            stackView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            stackView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 4),
            stackView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -4),
            stackView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            stackView.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor),

            selectionIndicator.bottomAnchor.constraint(equalTo: stackView.bottomAnchor),
            selectionIndicator.heightAnchor.constraint(equalToConstant: 3),

            bottomDivider.leadingAnchor.constraint(equalTo: leadingAnchor),
            bottomDivider.trailingAnchor.constraint(equalTo: trailingAnchor),
            bottomDivider.bottomAnchor.constraint(equalTo: bottomAnchor),
            bottomDivider.heightAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale)
        ])
    }

    override func tintColorDidChange() {
        super.tintColorDidChange()
        selectionIndicator.backgroundColor = tintColor
        updateButtonAppearance()
    }

    private func contains(_ indexPath: IndexPath) -> Bool {
        options.indices.contains(indexPath.section)
            && options[indexPath.section].options.indices.contains(indexPath.row)
    }

    private func rebuildTabs() {
        NSLayoutConstraint.deactivate(indicatorHorizontalConstraints)
        indicatorHorizontalConstraints.removeAll(keepingCapacity: true)
        indicatorButton = nil
        tabButtons.forEach { $0.removeFromSuperview() }
        tabButtons.removeAll(keepingCapacity: true)
        tabIndexPaths.removeAll(keepingCapacity: true)

        for (sectionIndex, section) in options.enumerated() {
            for (rowIndex, option) in section.options.enumerated() {
                let indexPath = IndexPath(row: rowIndex, section: sectionIndex)
                let button = UIButton(type: .system)
                var configuration = UIButton.Configuration.plain()
                configuration.title = section.labels.flatMap {
                    rowIndex < $0.count ? $0[rowIndex] : nil
                } ?? option
                configuration.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16)
                configuration.imagePadding = 5
                if lockedOptions.contains(indexPath) {
                    configuration.image = UIImage(systemName: "lock.fill")
                    configuration.imagePlacement = .leading
                }
                button.configuration = configuration
                button.titleLabel?.font = .preferredFont(forTextStyle: .subheadline)
                button.titleLabel?.adjustsFontForContentSizeCategory = true
                button.accessibilityIdentifier = "library-category-tab-\(sectionIndex)-\(rowIndex)"
                button.addTarget(self, action: #selector(tabPressed(_:)), for: .touchUpInside)
                stackView.addArrangedSubview(button)
                tabButtons.append(button)
                tabIndexPaths.append(indexPath)
            }
        }

        stackView.bringSubviewToFront(selectionIndicator)
        if
            let selectedIndex = tabIndexPaths.firstIndex(of: selectedIndexPath),
            tabButtons.indices.contains(selectedIndex)
        {
            constrainIndicator(to: tabButtons[selectedIndex])
        }
        updateButtonAppearance()
        setNeedsLayout()
    }

    private func updateButtonAppearance() {
        for (button, indexPath) in zip(tabButtons, tabIndexPaths) {
            let selected = indexPath == selectedIndexPath
            button.configuration?.baseForegroundColor = selected ? tintColor : .secondaryLabel
            button.titleLabel?.font = selected
                ? .preferredFont(forTextStyle: .headline)
                : .preferredFont(forTextStyle: .subheadline)
            button.accessibilityTraits = selected ? [.button, .selected] : .button
        }
    }

    private func updateSelectionIndicator(animated: Bool) {
        scrollView.layoutIfNeeded()
        stackView.layoutIfNeeded()
        guard
            let selectedIndex = tabIndexPaths.firstIndex(of: selectedIndexPath),
            tabButtons.indices.contains(selectedIndex)
        else {
            selectionIndicator.isHidden = true
            return
        }

        selectionIndicator.isHidden = false
        let button = tabButtons[selectedIndex]
        let scrollFrame = button.convert(button.bounds, to: scrollView)
        constrainIndicator(to: button)
        let changes = { self.stackView.layoutIfNeeded() }
        if animated, !UIAccessibility.isReduceMotionEnabled {
            UIView.animate(withDuration: 0.2, delay: 0, options: [.beginFromCurrentState, .curveEaseOut], animations: changes)
        } else {
            changes()
        }
        scrollView.scrollRectToVisible(scrollFrame.insetBy(dx: -12, dy: 0), animated: animated)
    }

    private func constrainIndicator(to button: UIButton) {
        guard indicatorButton !== button else { return }
        NSLayoutConstraint.deactivate(indicatorHorizontalConstraints)
        indicatorHorizontalConstraints = [
            selectionIndicator.centerXAnchor.constraint(equalTo: button.centerXAnchor),
            selectionIndicator.widthAnchor.constraint(equalTo: button.widthAnchor, constant: -28)
        ]
        NSLayoutConstraint.activate(indicatorHorizontalConstraints)
        indicatorButton = button
    }

    @objc private func tabPressed(_ sender: UIButton) {
        guard
            let index = tabButtons.firstIndex(of: sender),
            tabIndexPaths.indices.contains(index)
        else { return }
        setSelectedOption(tabIndexPaths[index], notifyDelegate: true, animated: true)
    }

    func setSelectedOption(_ indexPath: IndexPath, notifyDelegate: Bool = false, animated: Bool = false) {
        guard contains(indexPath) else { return }
        let changed = selectedIndexPath != indexPath
        selectedIndexPath = indexPath
        updateButtonAppearance()
        animateNextIndicatorUpdate = animated
        setNeedsLayout()
        layoutIfNeeded()
        if notifyDelegate, changed {
            delegate?.optionSelected(indexPath)
        }
    }
}

struct LibraryFilterDrawerView: View {
    private let originalFilters: [LibraryFilter]
    private let sourceKeys: [String]
    private let onApply: ([LibraryFilter]) -> Void

    @State private var filters: [LibraryFilter]
    @State private var showDiscardConfirmation = false
    @Environment(\.dismiss) private var dismiss

    init(
        filters: [LibraryFilter],
        sourceKeys: [String],
        onApply: @escaping ([LibraryFilter]) -> Void
    ) {
        self.originalFilters = filters
        self.sourceKeys = sourceKeys
        self.onApply = onApply
        self._filters = State(initialValue: filters)
    }

    var body: some View {
        PlatformNavigationStack {
            List {
                Section(NSLocalizedString("FILTERS")) {
                    ForEach(LibraryFilter.FilterMethod.allCases, id: \.self) { method in
                        if method.isAvailable {
                            filterRow(title: method.title, image: method.systemImageName, method: method)
                        }
                    }
                }

                Section {
                    DisclosureGroup {
                        ForEach(MangaContentRating.allCases, id: \.self) { rating in
                            filterRow(
                                title: rating.title,
                                method: .contentRating,
                                value: rating.stringValue
                            )
                        }
                    } label: {
                        filterGroupLabel(for: .contentRating)
                    }

                    if !sourceKeys.isEmpty {
                        DisclosureGroup {
                            ForEach(sourceKeys, id: \.self) { key in
                                filterRow(
                                    title: SourceManager.shared.source(for: key)?.name ?? key,
                                    method: .source,
                                    value: key
                                )
                            }
                        } label: {
                            filterGroupLabel(for: .source)
                        }
                    }
                }

                Section {
                    Button(NSLocalizedString("CLEAR_FILTERS")) {
                        filters = []
                    }
                    .disabled(filters.isEmpty)
                }
            }
            .navigationTitle(NSLocalizedString("FILTERS"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(NSLocalizedString("CANCEL")) {
                        if filters == originalFilters {
                            dismiss()
                        } else {
                            showDiscardConfirmation = true
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("APPLY")) {
                        onApply(filters)
                        dismiss()
                    }
                    .font(.body.weight(.medium))
                }
            }
            .confirmationDialogOrAlert(
                NSLocalizedString("CANCEL_CONFIRM"),
                isPresented: $showDiscardConfirmation,
                titleVisibility: .visible
            ) {
                Button(NSLocalizedString("DISCARD_CHANGES"), role: .destructive) {
                    dismiss()
                }
            } message: {
                Text(NSLocalizedString("CANCEL_CONFIRM_TEXT"))
            }
        }
    }

    private func filterGroupLabel(for method: LibraryFilter.FilterMethod) -> some View {
        Label(method.title, systemImage: method.systemImageName)
            .foregroundStyle(.primary)
    }

    private func filterRow(
        title: String,
        image: String? = nil,
        method: LibraryFilter.FilterMethod,
        value: String? = nil
    ) -> some View {
        Button {
            toggleFilter(method: method, value: value)
        } label: {
            HStack(spacing: 12) {
                if let image {
                    Image(systemName: image)
                        .frame(minWidth: 24)
                        .foregroundStyle(.tint)
                }
                Text(title)
                Spacer()
                switch filterState(for: method, value: value) {
                    case .included:
                        Image(systemName: "checkmark")
                            .foregroundStyle(.tint)
                    case .excluded:
                        Image(systemName: "xmark")
                            .foregroundStyle(.tint)
                    case .none:
                        EmptyView()
                }
            }
        }
        .foregroundStyle(.primary)
    }

    private func toggleFilter(method: LibraryFilter.FilterMethod, value: String? = nil) {
        let index = filters.firstIndex { $0.type == method && $0.value == value }
        if let index {
            if filters[index].exclude {
                filters.remove(at: index)
            } else {
                filters[index].exclude = true
            }
        } else {
            filters.append(.init(type: method, value: value, exclude: false))
        }
    }

    private func filterState(
        for method: LibraryFilter.FilterMethod,
        value: String? = nil
    ) -> FilterState {
        if let filter = filters.first(where: { $0.type == method && $0.value == value }) {
            filter.exclude ? .excluded : .included
        } else {
            .none
        }
    }

    private enum FilterState {
        case none
        case included
        case excluded
    }
}
