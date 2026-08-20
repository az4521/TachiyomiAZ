import Foundation

public enum SettingType: String, Codable {
    case group, select
    case multiselect = "multi-select"
    case toggle = "switch"
    case stepper, segment, text, button, link, login, page
    case editableList = "editable-list"
    case picker, custom
    public var requiresKey: Bool {
        ![.group, .button, .link, .page].contains(self)
    }
}

public struct Setting: Sendable, Hashable, Codable {
    public enum Value: Sendable, Hashable, Codable {
        case group(GroupSetting)
        case select(SelectSetting)
        case multiselect(MultiSelectSetting)
        case toggle(ToggleSetting)
        case stepper(StepperSetting)
        case segment(SegmentSetting)
        case text(TextSetting)
        case button(ButtonSetting)
        case link(LinkSetting)
        case login(LoginSetting)
        case page(PageSetting)
        case editableList(EditableListSetting)
        case picker(PickerSetting)
        case custom
    }

    public let key: String
    public var title: String
    public var notification: String?
    public var requires: String?
    public var requiresFalse: String?
    public var refreshes: [String]
    public var value: Value

    public init(
        key: String = "",
        title: String = "",
        notification: String? = nil,
        requires: String? = nil,
        requiresFalse: String? = nil,
        refreshes: [String] = [],
        value: Value
    ) {
        self.key = key
        self.title = title
        self.notification = notification
        self.requires = requires
        self.requiresFalse = requiresFalse
        self.refreshes = refreshes
        self.value = value
    }

    public var type: SettingType {
        switch value {
            case .group: .group
            case .select: .select
            case .multiselect: .multiselect
            case .toggle: .toggle
            case .stepper: .stepper
            case .segment: .segment
            case .text: .text
            case .button: .button
            case .link: .link
            case .login: .login
            case .page: .page
            case .editableList: .editableList
            case .picker: .picker
            case .custom: .custom
        }
    }
}

public struct GroupSetting: Sendable, Codable, Hashable {
    public let footer: String?
    public let items: [Setting]
    public init(footer: String? = nil, items: [Setting]) {
        self.footer = footer
        self.items = items
    }
}

public struct SelectSetting: Sendable, Codable, Hashable {
    public let values: [String]
    public let titles: [String]?
    public let authToOpen: Bool?
    public let defaultValue: String?
    public init(values: [String], titles: [String]? = nil, authToOpen: Bool? = nil, defaultValue: String? = nil) {
        self.values = values
        self.titles = titles
        self.authToOpen = authToOpen
        self.defaultValue = defaultValue
    }
}

public struct MultiSelectSetting: Sendable, Codable, Hashable {
    public let values: [String]
    public let titles: [String]?
    public let authToOpen: Bool?
    public let defaultValue: [String]?
    public init(values: [String], titles: [String]? = nil, authToOpen: Bool? = nil, defaultValue: [String]? = nil) {
        self.values = values
        self.titles = titles
        self.authToOpen = authToOpen
        self.defaultValue = defaultValue
    }
}

public struct ToggleSetting: Sendable, Codable, Hashable {
    public let subtitle: String?
    public var authToDisable: Bool?
    public var defaultValue: Bool?
    public init(subtitle: String? = nil, authToDisable: Bool? = nil, defaultValue: Bool = false) {
        self.subtitle = subtitle
        self.authToDisable = authToDisable
        self.defaultValue = defaultValue
    }
}

public struct StepperSetting: Sendable, Codable, Hashable {
    public let minimumValue: Double
    public let maximumValue: Double
    public let stepValue: Double?
    public var defaultValue: Double?
    public init(minimumValue: Double, maximumValue: Double, stepValue: Double? = nil, defaultValue: Double? = nil) {
        self.minimumValue = minimumValue
        self.maximumValue = maximumValue
        self.stepValue = stepValue
        self.defaultValue = defaultValue
    }
}

public struct SegmentSetting: Sendable, Codable, Hashable {
    public let options: [String]
    public var defaultValue: Int?
    public init(options: [String], defaultValue: Int? = nil) {
        self.options = options
        self.defaultValue = defaultValue
    }
}

public struct TextSetting: Sendable, Codable, Hashable {
    public let placeholder: String?
    public let autocapitalizationType: Int?
    public let keyboardType: Int?
    public let returnKeyType: Int?
    public let autocorrectionDisabled: Bool?
    public let secure: Bool?
    public var defaultValue: String?
    public init(
        placeholder: String? = nil,
        autocapitalizationType: Int? = nil,
        keyboardType: Int? = nil,
        returnKeyType: Int? = nil,
        autocorrectionDisabled: Bool = false,
        secure: Bool = false,
        defaultValue: String? = nil
    ) {
        self.placeholder = placeholder
        self.autocapitalizationType = autocapitalizationType
        self.keyboardType = keyboardType
        self.returnKeyType = returnKeyType
        self.autocorrectionDisabled = autocorrectionDisabled
        self.secure = secure
        self.defaultValue = defaultValue
    }
}

public struct ButtonSetting: Sendable, Codable, Hashable {
    public let destructive: Bool?
    public let confirmTitle: String?
    public let confirmText: String?
    public init(destructive: Bool = false, confirmTitle: String? = nil, confirmText: String? = nil) {
        self.destructive = destructive
        self.confirmTitle = confirmTitle
        self.confirmText = confirmText
    }
}

public struct LinkSetting: Sendable, Codable, Hashable {
    public let url: String
    public let external: Bool?
    public init(url: String, external: Bool? = nil) {
        self.url = url
        self.external = external
    }
}

public struct LoginSetting: Sendable, Codable, Hashable {
    public enum Method: String, Sendable, Codable { case basic, oauth, web }
    public let method: Method
    public let url: String?
    public let urlKey: String?
    public let logoutTitle: String?
    public let pkce: Bool?
    public let tokenUrl: String?
    public let callbackScheme: String?
    public let useEmail: Bool?
    public let localStorageKeys: [String]?
    public init(
        method: Method,
        url: String? = nil,
        urlKey: String? = nil,
        logoutTitle: String? = nil,
        pkce: Bool = false,
        tokenUrl: String? = nil,
        callbackScheme: String? = nil,
        useEmail: Bool? = nil,
        localStorageKeys: [String]? = nil
    ) {
        self.method = method
        self.url = url
        self.urlKey = urlKey
        self.logoutTitle = logoutTitle
        self.pkce = pkce
        self.tokenUrl = tokenUrl
        self.callbackScheme = callbackScheme
        self.useEmail = useEmail
        self.localStorageKeys = localStorageKeys
    }
}

public struct PageSetting: Sendable, Codable, Hashable {
    public enum Icon: Codable, Sendable, Hashable {
        case system(name: String, color: String, inset: Int = 5)
        case url(String)
    }
    public let items: [Setting]
    public let inlineTitle: Bool?
    public let authToOpen: Bool?
    public let icon: Icon?
    public let info: String?
    public init(items: [Setting], inlineTitle: Bool = false, authToOpen: Bool = false, icon: Icon? = nil, info: String? = nil) {
        self.items = items
        self.inlineTitle = inlineTitle
        self.authToOpen = authToOpen
        self.icon = icon
        self.info = info
    }
}

public struct EditableListSetting: Sendable, Codable, Hashable {
    public let lineLimit: Int?
    public let inline: Bool?
    public let placeholder: String?
    public let defaultValue: [String]?
    public init(lineLimit: Int? = nil, inline: Bool = false, placeholder: String? = nil, defaultValue: [String]? = nil) {
        self.lineLimit = lineLimit
        self.inline = inline
        self.placeholder = placeholder
        self.defaultValue = defaultValue
    }
}

public struct PickerSetting: Sendable, Codable, Hashable {
    public let values: [String]
    public let titles: [String]?
    public let defaultValue: String?
    public init(values: [String], titles: [String]? = nil, defaultValue: String? = nil) {
        self.values = values
        self.titles = titles
        self.defaultValue = defaultValue
    }
}
