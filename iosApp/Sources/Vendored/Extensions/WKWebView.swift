//
//  WKWebView.swift
//  Aidoku
//
//  Created by Skitty on 5/21/25.
//

import WebKit

extension WKWebView {
    func getCookies(for domain: String? = nil) async -> [String: String]  {
        await withCheckedContinuation { continuation in
            configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                var cookieDict = [String: String]()
                for cookie in cookies {
                    if let domain {
                        if cookie.domain.contains(domain) {
                            cookieDict[cookie.name] = cookie.value
                        }
                    } else {
                        cookieDict[cookie.name] = cookie.value
                    }
                }
                continuation.resume(returning: cookieDict)
            }
        }
    }

    func getLocalStorage(keys: [String]) async -> [String: String] {
        guard !keys.isEmpty else { return [:] }
        let js = """
        (function() {
            var result = {};
            var keys = \(keys);
            for (var i = 0; i < keys.length; i++) {
                var key = keys[i];
                var value = localStorage.getItem(key);
                if (value) { result[key] = value; }
            }
            return result;
        })();
        """
        do {
            let result = try await evaluateJavaScript(js) as? [String: String]
            return result ?? [:]
        } catch {
            return [:]
        }
    }

    /// Captures the current security origin's complete DOM storage. Returning
    /// nil distinguishes a JavaScript/storage failure from a legitimately
    /// empty store so callers do not replace a valid session with bad data.
    func getAllLocalStorage() async -> [String: String]? {
        let js = """
        (function() {
            var result = {};
            for (var i = 0; i < localStorage.length; i++) {
                var key = localStorage.key(i);
                if (key !== null) { result[key] = localStorage.getItem(key); }
            }
            return result;
        })();
        """
        do {
            return try await evaluateJavaScript(js) as? [String: String]
        } catch {
            return nil
        }
    }
}
