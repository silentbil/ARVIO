import Foundation
import Security

struct AuthSession: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let userId: String
    let email: String
    let expiresAt: Date
}

struct UserProfile: Codable, Equatable {
    let id: String
    let email: String?
    let addons: String?
    let defaultSubtitle: String?
    let autoPlayNext: Bool?

    enum CodingKeys: String, CodingKey {
        case id
        case email
        case addons
        case defaultSubtitle = "default_subtitle"
        case autoPlayNext = "auto_play_next"
    }
}

private struct SupabaseAuthResponse: Decodable {
    let accessToken: String
    let refreshToken: String
    let expiresIn: Int?
    let user: SupabaseUser?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
        case user
    }
}

private struct SupabaseUser: Decodable {
    let id: String
    let email: String?
}

private struct EmailPasswordBody: Encodable {
    let email: String
    let password: String
}

private struct RefreshBody: Encodable {
    let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case refreshToken = "refresh_token"
    }
}

final class KeychainStore {
    private let service = "com.arvio.ios.session"

    func save<T: Encodable>(_ value: T, account: String) throws {
        let data = try JSONEncoder().encode(value)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw ArvioError.requestFailed("Unable to save secure session")
        }
    }

    func load<T: Decodable>(_ type: T.Type, account: String) -> T? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else {
            return nil
        }
        return try? JSONDecoder().decode(type, from: data)
    }

    func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}

@MainActor
final class AuthService: ObservableObject {
    @Published private(set) var session: AuthSession?
    @Published private(set) var profile: UserProfile?
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let client = JSONClient()
    private let keychain = KeychainStore()
    private let sessionAccount = "supabase-session"

    init() {
        session = keychain.load(AuthSession.self, account: sessionAccount)
    }

    var isAuthenticated: Bool {
        session != nil
    }

    func restore() async {
        guard let existing = session else { return }
        do {
            if existing.expiresAt.timeIntervalSinceNow < 120 {
                try await refreshSession()
            }
            try await loadProfile()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func signIn(email: String, password: String) async {
        await authenticate(path: "/auth/v1/token?grant_type=password", body: EmailPasswordBody(email: email, password: password))
    }

    func signUp(email: String, password: String) async {
        await authenticate(path: "/auth/v1/signup", body: EmailPasswordBody(email: email, password: password))
    }

    func signOut() {
        session = nil
        profile = nil
        errorMessage = nil
        keychain.delete(account: sessionAccount)
    }

    func accessToken() async throws -> String {
        guard let current = session else { throw ArvioError.notAuthenticated }
        if current.expiresAt.timeIntervalSinceNow < 120 {
            try await refreshSession()
        }
        guard let token = session?.accessToken else { throw ArvioError.notAuthenticated }
        return token
    }

    func loadProfile() async throws {
        guard let current = session else { throw ArvioError.notAuthenticated }
        let rows: [UserProfile] = try await supabaseRequest(
            "/rest/v1/profiles?id=eq.\(current.userId)&select=id,email,addons,default_subtitle,auto_play_next",
            token: current.accessToken
        )
        profile = rows.first ?? UserProfile(id: current.userId, email: current.email, addons: nil, defaultSubtitle: nil, autoPlayNext: nil)
    }

    func supabaseRequest<T: Decodable, B: Encodable>(
        _ path: String,
        method: String = "GET",
        token: String,
        prefer: String? = nil,
        body: B? = nil
    ) async throws -> T {
        guard AppConfig.isCloudConfigured else {
            throw ArvioError.missingConfiguration("Supabase")
        }
        guard let url = URL(string: AppConfig.supabaseURL + path) else {
            throw ArvioError.invalidURL(path)
        }
        var headers = [
            "apikey": AppConfig.supabaseAnonKey,
            "Authorization": "Bearer \(token)"
        ]
        if let prefer {
            headers["Prefer"] = prefer
        }
        return try await client.request(url, method: method, headers: headers, body: body)
    }

    func supabaseRequest<T: Decodable>(
        _ path: String,
        method: String = "GET",
        token: String,
        prefer: String? = nil
    ) async throws -> T {
        let body: EmptyBody? = nil
        return try await supabaseRequest(path, method: method, token: token, prefer: prefer, body: body)
    }

    private func authenticate(path: String, body: EmailPasswordBody) async {
        guard AppConfig.isCloudConfigured else {
            errorMessage = "Supabase is not configured for iOS"
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            guard let url = URL(string: AppConfig.supabaseURL + path) else {
                throw ArvioError.invalidURL(path)
            }
            let response: SupabaseAuthResponse = try await client.request(
                url,
                method: "POST",
                headers: ["apikey": AppConfig.supabaseAnonKey],
                body: body
            )
            try persist(response: response, fallbackEmail: body.email)
            try await loadProfile()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func refreshSession() async throws {
        guard AppConfig.isCloudConfigured else {
            throw ArvioError.missingConfiguration("Supabase")
        }
        guard let current = session else { throw ArvioError.notAuthenticated }
        guard let url = URL(string: AppConfig.supabaseURL + "/auth/v1/token?grant_type=refresh_token") else {
            throw ArvioError.invalidURL("refresh")
        }
        let response: SupabaseAuthResponse = try await client.request(
            url,
            method: "POST",
            headers: ["apikey": AppConfig.supabaseAnonKey],
            body: RefreshBody(refreshToken: current.refreshToken)
        )
        try persist(response: response, fallbackEmail: current.email)
    }

    private func persist(response: SupabaseAuthResponse, fallbackEmail: String) throws {
        guard let userId = response.user?.id ?? decodeSubject(response.accessToken) else {
            throw ArvioError.requestFailed("Auth response missing user")
        }
        let email = response.user?.email ?? decodeEmail(response.accessToken) ?? fallbackEmail
        let expiresAt = Date().addingTimeInterval(TimeInterval(response.expiresIn ?? 3600))
        let newSession = AuthSession(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            userId: userId,
            email: email,
            expiresAt: expiresAt
        )
        try keychain.save(newSession, account: sessionAccount)
        session = newSession
    }

    private func decodeSubject(_ jwt: String) -> String? {
        decodePayload(jwt)?["sub"] as? String
    }

    private func decodeEmail(_ jwt: String) -> String? {
        decodePayload(jwt)?["email"] as? String
    }

    private func decodePayload(_ jwt: String) -> [String: Any]? {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var base64 = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        guard let data = Data(base64Encoded: base64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return json
    }
}
