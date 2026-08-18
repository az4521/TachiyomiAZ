import ExtensionRunner
import Foundation

// Only the ExtensionRunner.Source extension is taken from tachiyomiazios's ExtensionRunner.swift.
// The rest of that file bridged an older Manga model than the one in Models.swift here, and
// nothing in this port needs it. getModifiedImageRequest is the important part: it lets a source
// authenticate, decrypt or descramble its own images, and deliberately fails loudly rather than
// falling back to the raw URL when a source advertises image-request support.

extension ExtensionRunner.Source {
    // Upstream's convenience init built a Source from a WASM bundle; sources here come from the
    // JVM host instead, so it is not carried over.

    var isExternal: Bool {
        runner is Interpreter
    }

    // toInfo() built a SourceInfo2 for Aidoku's source list, which this port does not use.

    func getModifiedImageRequest(url: URL, context: PageContext?) async -> URLRequest {
        var result: URLRequest
        do {
            result = try await getImageRequest(url: url.absoluteString, context: context)
        } catch {
            LogManager.logger.error(
                "Image request preparation failed for \(key): " +
                    error.localizedDescription
            )
            // A source that advertises image-request support may use its
            // request path for decryption, descrambling, or authentication.
            // Falling back to the original URL silently bypasses all of that
            // work and can display the encrypted source image as if it were
            // the final page.
            if features.providesImageRequests {
                result = .init(
                    url: URL(
                        string: "tachiyomiaz-image-request-failed://\(UUID().uuidString)"
                    )!
                )
            } else {
                result = .init(url: url)
            }
        }
        return await Self.modify(url: url, request: result)
    }

    static func modify(url: URL, request: URLRequest) async -> URLRequest {
        var request = request
        // Supply the single app-level default without replacing a UA authored
        // by the extension itself.
        if request.value(forHTTPHeaderField: "User-Agent") == nil {
            request.setValue(
                await UserAgentProvider.shared.getExtensionNetworkUserAgent(),
                forHTTPHeaderField: "User-Agent"
            )
        }
        let cookies = HTTPCookie.requestHeaderFields(with: HTTPCookieStorage.shared.cookies(for: url) ?? [])
        for (key, value) in cookies {
            if key == "Cookie" {
                var cookieString = value
                // keep cookies in original request
                if let oldCookie = request.value(forHTTPHeaderField: "Cookie") {
                    cookieString += "; " + oldCookie
                }
                request.setValue(cookieString, forHTTPHeaderField: "Cookie")
            } else {
                request.setValue(value, forHTTPHeaderField: key)
            }
        }
        return request
    }

    func getSelectedLanguages() -> [String] {
        if languages.count > 1 {
            if config?.languageSelectType == .single {
                let selectedLanguage = UserDefaults.standard.string(forKey: "\(key).language")
                if let selectedLanguage {
                    return [selectedLanguage]
                } else {
                    return []
                }
            } else {
                let selectedLanguages = UserDefaults.standard.stringArray(forKey: "\(key).languages")
                return selectedLanguages ?? []
            }
        } else {
            return languages
        }
    }
}
