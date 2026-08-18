import CJVMBridge
import Foundation

public final class JVMRuntime: @unchecked Sendable {
    private let handle: OpaquePointer

    public init(configuration: JVMRuntimeConfiguration) throws {
        guard configuration.javaHomeURL.isFileURL else {
            throw JVMRuntimeError.invalidConfiguration(
                "JAVA_HOME must be a file URL"
            )
        }
        guard configuration.frameworksURL.isFileURL else {
            throw JVMRuntimeError.invalidConfiguration(
                "Framework directory must be a file URL"
            )
        }
        guard !configuration.classpathURLs.isEmpty else {
            throw JVMRuntimeError.invalidConfiguration(
                "Classpath cannot be empty"
            )
        }

        var errorPointer: UnsafeMutablePointer<CChar>?
        let optionPointers = configuration.additionalOptions.compactMap {
            strdup($0)
        }
        defer {
            optionPointers.forEach { free($0) }
        }
        guard optionPointers.count == configuration.additionalOptions.count else {
            throw JVMRuntimeError.startupFailed(
                "Unable to allocate JVM option strings"
            )
        }

        let immutableOptionPointers: [UnsafePointer<CChar>?] = optionPointers.map {
            UnsafePointer<CChar>($0)
        }
        let runtime = configuration.javaHomeURL.path.withCString { javaHome in
            configuration.frameworksURL.path.withCString { frameworks in
                configuration.classpath.withCString { classpath in
                    immutableOptionPointers.withUnsafeBufferPointer { options in
                        tjr_runtime_create(
                            javaHome,
                            frameworks,
                            classpath,
                            options.baseAddress,
                            Int32(options.count),
                            &errorPointer
                        )
                    }
                }
            }
        }

        guard let runtime else {
            throw JVMRuntimeError.startupFailed(
                Self.takeString(errorPointer) ?? "Unknown startup error"
            )
        }
        handle = runtime
    }

    deinit {
        tjr_runtime_release(handle)
    }

    public func dispatch(_ requestJSON: String) throws -> String {
        var responsePointer: UnsafeMutablePointer<CChar>?
        var errorPointer: UnsafeMutablePointer<CChar>?
        let status = requestJSON.withCString { request in
            tjr_runtime_dispatch(
                handle,
                request,
                &responsePointer,
                &errorPointer
            )
        }

        let error = Self.takeString(errorPointer)
        guard status == TJRStatusOK else {
            if let responsePointer {
                tjr_string_free(responsePointer)
            }
            throw JVMRuntimeError.dispatchFailed(
                status: Int(status.rawValue),
                message: error ?? "Unknown Java error"
            )
        }

        guard let responsePointer else {
            throw JVMRuntimeError.invalidUTF8Response
        }
        defer {
            tjr_string_free(responsePointer)
        }
        guard let response = String(validatingUTF8: responsePointer) else {
            throw JVMRuntimeError.invalidUTF8Response
        }
        return response
    }

    public func dispatch<Request: Encodable, Response: Decodable>(
        _ request: Request,
        as responseType: Response.Type = Response.self,
        encoder: JSONEncoder = JSONEncoder(),
        decoder: JSONDecoder = JSONDecoder()
    ) throws -> Response {
        let encoded: Data
        do {
            encoded = try encoder.encode(request)
        } catch {
            throw JVMRuntimeError.encodingFailed
        }

        let responseJSON = try dispatch(String(decoding: encoded, as: UTF8.self))
        do {
            return try decoder.decode(
                responseType,
                from: Data(responseJSON.utf8)
            )
        } catch {
            throw JVMRuntimeError.decodingFailed(error.localizedDescription)
        }
    }

    private static func takeString(
        _ pointer: UnsafeMutablePointer<CChar>?
    ) -> String? {
        guard let pointer else { return nil }
        defer {
            tjr_string_free(pointer)
        }
        return String(validatingUTF8: pointer)
    }
}
