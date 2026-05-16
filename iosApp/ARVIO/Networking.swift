import Foundation

enum ArvioError: LocalizedError {
    case missingConfiguration(String)
    case invalidURL(String)
    case requestFailed(String)
    case decodingFailed
    case notAuthenticated

    var errorDescription: String? {
        switch self {
        case .missingConfiguration(let value):
            return "\(value) is not configured"
        case .invalidURL(let value):
            return "Invalid URL: \(value)"
        case .requestFailed(let value):
            return value
        case .decodingFailed:
            return "Unable to read server response"
        case .notAuthenticated:
            return "Sign in required"
        }
    }
}

struct EmptyBody: Encodable {}

final class JSONClient {
    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    init(session: URLSession = .shared) {
        self.session = session
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    func request<T: Decodable, B: Encodable>(
        _ url: URL,
        method: String = "GET",
        headers: [String: String] = [:],
        body: B? = nil
    ) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }

        if let body {
            request.httpBody = try encoder.encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw ArvioError.requestFailed("No HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
            throw ArvioError.requestFailed(message?.isEmpty == false ? message! : "Request failed with \(http.statusCode)")
        }
        if T.self == EmptyResponse.self {
            return EmptyResponse() as! T
        }
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw ArvioError.decodingFailed
        }
    }

    func request<T: Decodable>(
        _ url: URL,
        method: String = "GET",
        headers: [String: String] = [:]
    ) async throws -> T {
        let body: EmptyBody? = nil
        return try await request(url, method: method, headers: headers, body: body)
    }
}

struct EmptyResponse: Decodable {}
