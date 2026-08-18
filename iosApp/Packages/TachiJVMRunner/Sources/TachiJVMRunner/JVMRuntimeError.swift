import Foundation

public enum JVMRuntimeError: LocalizedError, Sendable {
    case invalidConfiguration(String)
    case startupFailed(String)
    case dispatchFailed(status: Int, message: String)
    case invalidUTF8Response
    case encodingFailed
    case decodingFailed(String)

    public var errorDescription: String? {
        switch self {
            case .invalidConfiguration(let message):
                "Invalid JVM configuration: \(message)"
            case .startupFailed(let message):
                "Unable to start Java: \(message)"
            case .dispatchFailed(_, let message):
                "Java extension host failed: \(message)"
            case .invalidUTF8Response:
                "Java returned a response that is not valid UTF-8."
            case .encodingFailed:
                "Unable to encode the Java extension request."
            case .decodingFailed(let message):
                "Unable to decode the Java extension response: \(message)"
        }
    }
}
