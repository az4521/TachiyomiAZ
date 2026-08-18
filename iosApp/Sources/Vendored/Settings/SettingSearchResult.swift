//
//  SettingSearchResult.swift
//  Aidoku
//
//  Created by Skitty on 9/19/25.
//

import ExtensionRunner
import SwiftUI

struct SettingPath {
    let key: String
    let title: String
    let paths: [String]
    var setting: ExtensionRunner.Setting?
}

struct SettingSearchResult {
    var sections: [Section]

    struct Section: Identifiable {
        let id = UUID()
        var icon: ExtensionRunner.PageSetting.Icon?
        var header: String?
        var paths: [SettingPath]
    }
}
