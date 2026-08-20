import SwiftUI

public final class SettingsStore: Sendable {
    public static let shared = SettingsStore()

    private init() {}

    public func set(key: String, value: Bool) {
        UserDefaults.standard.setValue(value, forKey: key)
    }

    public func set(key: String, value: Int) {
        UserDefaults.standard.setValue(value, forKey: key)
    }

    public func set(key: String, value: Double) {
        UserDefaults.standard.setValue(value, forKey: key)
    }

    public func set(key: String, value: String) {
        UserDefaults.standard.setValue(value, forKey: key)
    }

    public func set(key: String, value: [String]) {
        UserDefaults.standard.setValue(value, forKey: key)
    }

    public func set(key: String, value: Data) {
        UserDefaults.standard.setValue(value, forKey: key)
    }

    public func setValue(key: String, value: Any?) {
        UserDefaults.standard.setValue(value, forKey: key)
    }

    public func remove(key: String) {
        UserDefaults.standard.removeObject(forKey: key)
    }

    public func get(key: String) -> Bool {
        UserDefaults.standard.bool(forKey: key)
    }

    public func get(key: String) -> Int {
        UserDefaults.standard.integer(forKey: key)
    }

    public func get(key: String) -> Double {
        UserDefaults.standard.double(forKey: key)
    }

    public func get(key: String) -> String {
        UserDefaults.standard.string(forKey: key) ?? ""
    }

    public func get(key: String) -> [String] {
        UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    public func get(key: String) -> Data? {
        UserDefaults.standard.data(forKey: key)
    }

    public func object(key: String) -> Any? {
        UserDefaults.standard.object(forKey: key)
    }

    public func register(key: String, default value: Any) {
        UserDefaults.standard.register(defaults: [key: value])
    }

    public func binding(key: String) -> Binding<Bool> {
        Binding(
            get: { self.get(key: key) },
            set: { self.set(key: key, value: $0) }
        )
    }

    public func binding(key: String) -> Binding<Int> {
        Binding(
            get: { self.get(key: key) },
            set: { self.set(key: key, value: $0) }
        )
    }

    public func binding(key: String) -> Binding<Double> {
        Binding(
            get: { self.get(key: key) },
            set: { self.set(key: key, value: $0) }
        )
    }

    public func binding(key: String) -> Binding<String> {
        Binding(
            get: { self.get(key: key) },
            set: { self.set(key: key, value: $0) }
        )
    }

    public func binding(key: String) -> Binding<[String]> {
        Binding(
            get: { self.get(key: key) },
            set: { self.set(key: key, value: $0) }
        )
    }
}
