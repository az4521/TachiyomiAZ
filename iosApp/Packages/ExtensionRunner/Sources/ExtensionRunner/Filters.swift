import Foundation

public struct Filter: Sendable, Hashable, Codable {
    public struct SortDefault: Sendable, Hashable, Codable {
        public let index: Int
        public let ascending: Bool
        public init(index: Int, ascending: Bool) {
            self.index = index
            self.ascending = ascending
        }
    }

    public enum Value: Sendable, Hashable, Codable {
        case text(placeholder: String?)
        case sort(canAscend: Bool = true, options: [String], defaultValue: SortDefault?)
        case check(name: String?, canExclude: Bool = false, defaultValue: Bool?)
        case select(SelectFilter)
        case multiselect(MultiSelectFilter)
        case note(String)
        case range(min: Float?, max: Float?, decimal: Bool = false)
    }

    public var id: String
    public var title: String?
    public var hideFromHeader: Bool?
    public var value: Value
    public init(id: String, title: String? = nil, hideFromHeader: Bool? = nil, value: Value) {
        self.id = id
        self.title = title
        self.hideFromHeader = hideFromHeader
        self.value = value
    }
}

public struct SelectFilter: Sendable, Hashable, Codable {
    public var isGenre: Bool
    public var usesTagStyle: Bool
    public var options: [String]
    public var ids: [String]?
    public var defaultValue: String?
    public init(
        isGenre: Bool = false,
        usesTagStyle: Bool? = nil,
        options: [String],
        ids: [String]? = nil,
        defaultValue: String? = nil
    ) {
        self.isGenre = isGenre
        self.usesTagStyle = usesTagStyle ?? isGenre
        self.options = options
        self.ids = ids
        self.defaultValue = defaultValue
    }
}

public struct MultiSelectFilter: Sendable, Hashable, Codable {
    public var isGenre: Bool
    public var canExclude: Bool
    public var usesTagStyle: Bool
    public var options: [String]
    public var ids: [String]?
    public var defaultIncluded: [String]?
    public var defaultExcluded: [String]?
    public init(
        isGenre: Bool = false,
        canExclude: Bool = false,
        usesTagStyle: Bool? = nil,
        options: [String],
        ids: [String]? = nil,
        defaultIncluded: [String]? = nil,
        defaultExcluded: [String]? = nil
    ) {
        self.isGenre = isGenre
        self.canExclude = canExclude
        self.usesTagStyle = usesTagStyle ?? isGenre
        self.options = options
        self.ids = ids
        self.defaultIncluded = defaultIncluded
        self.defaultExcluded = defaultExcluded
    }
}

public struct SortFilterValue: Sendable, Equatable, Hashable, Codable {
    public let id: String
    public let index: Int32
    public let ascending: Bool
    public init(id: String, index: Int, ascending: Bool) {
        self.id = id
        self.index = Int32(index)
        self.ascending = ascending
    }
}

public enum FilterValue: Sendable, Hashable, Codable {
    case text(id: String, value: String)
    case sort(SortFilterValue)
    case check(id: String, value: Int)
    case select(id: String, value: String)
    case multiselect(id: String, included: [String], excluded: [String])
    case range(id: String, from: Float?, to: Float?)

    public var id: String {
        switch self {
            case let .text(id, _), let .check(id, _), let .select(id, _),
                 let .multiselect(id, _, _), let .range(id, _, _):
                id
            case .sort(let value):
                value.id
        }
    }
}
