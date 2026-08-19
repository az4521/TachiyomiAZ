//
//  BackupContentView.swift
//  Aidoku
//
//  Created by Skitty on 9/28/25.
//

import SwiftUI
import TachiyomiKit

struct BackupContentView: View {
    let decoded: TachibkBackupCodec.Decoded

    private var backup: TachiyomiKit.Backup { decoded.backup }
    private var state: BackupState { decoded.state }

    @State private var restoreError: String?
    @State private var missingSources: [String] = []
    @State private var showRestoreAlert = false
    @State private var showRestoreErrorAlert = false
    @State private var showMissingSourcesAlert = false

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        PlatformNavigationStack {
            List {
                Section {
                    infoCell(title: NSLocalizedString("NAME"), value: state.name ?? NSLocalizedString("NONE"))
                    infoCell(
                        title: NSLocalizedString("DATE"),
                        value: state.date.formatted(date: .numeric, time: .shortened)
                    )
                }
                Section {
                    infoCell(
                        title: NSLocalizedString("LIBRARY_ENTRIES"),
                        value: String(backup.backupManga.count)
                    )
                    infoCell(
                        title: NSLocalizedString("HISTORY"),
                        value: String(backup.backupManga.reduce(0) { $0 + $1.history.count })
                    )
                    infoCell(
                        title: NSLocalizedString("CHAPTERS"),
                        value: String(backup.backupManga.reduce(0) { $0 + $1.chapters.count })
                    )
                    infoCell(
                        title: NSLocalizedString("TRACKING"),
                        value: String(backup.backupManga.reduce(0) { $0 + $1.tracking.count })
                    )
                    infoCell(
                        title: NSLocalizedString("CATEGORIES"),
                        value: String(backup.backupCategories.count)
                    )
                    infoCell(
                        title: NSLocalizedString("SETTINGS"),
                        value: String(state.settings?.count ?? 0)
                    )
                }

                Section {
                    LargeButton {
                        showRestoreAlert = true
                    } label: {
                        HStack {
                            Image(systemName: "gearshape.arrow.trianglehead.2.clockwise.rotate.90")
                            Text(NSLocalizedString("RESTORE"))
                        }
                    }
                }
            }
            .navigationTitle(NSLocalizedString("BACKUP_INFO_TITLE"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    CloseButton {
                        dismiss()
                    }
                }
            }
            .alert(NSLocalizedString("RESTORE_BACKUP"), isPresented: $showRestoreAlert) {
                Button(NSLocalizedString("CANCEL"), role: .cancel) {}
                Button(NSLocalizedString("RESTORE"), role: .destructive) {
                    restore()
                }
            } message: {
                Text(NSLocalizedString("RESTORE_BACKUP_TEXT"))
            }
            .alert(NSLocalizedString("BACKUP_ERROR"), isPresented: $showRestoreErrorAlert) {
                Button(NSLocalizedString("OK"), role: .cancel) {}
            } message: {
                Text(String(format: NSLocalizedString("BACKUP_ERROR_TEXT"), restoreError ?? NSLocalizedString("UNKNOWN")))
            }
            .alert(NSLocalizedString("MISSING_SOURCES"), isPresented: $showMissingSourcesAlert) {
                Button(NSLocalizedString("OK"), role: .cancel) {}
            } message: {
                Text(NSLocalizedString("MISSING_SOURCES_TEXT") + missingSources.map { "\n- \($0)" }.joined())
            }
        }
    }

    func infoCell(title: String, value: String) -> some View {
        HStack {
            Text(title)
                .lineLimit(1)
            Spacer()
            Text(value)
                .lineLimit(1)
                .foregroundStyle(.secondary)
        }
    }

    func restore() {
        Task {
            await BackupManager.shared.restore(from: backup)
        }
    }
}
