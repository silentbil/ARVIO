import Foundation

struct CloudPayload: Codable {
    var version: Int
    var addons: [InstalledAddon]
    var updatedAt: TimeInterval

    static let empty = CloudPayload(version: 1, addons: [], updatedAt: Date().timeIntervalSince1970)
}

private struct AccountSyncRow: Codable {
    let userId: String?
    let payload: String?
    let updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case payload
        case updatedAt = "updated_at"
    }
}

private struct AccountSyncUpsert: Codable {
    let userId: String
    let payload: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case payload
        case updatedAt = "updated_at"
    }
}

@MainActor
final class CloudSyncService: ObservableObject {
    @Published private(set) var payload = CloudPayload.empty
    @Published private(set) var isSyncing = false
    @Published var lastError: String?

    private let auth: AuthService
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(auth: AuthService) {
        self.auth = auth
    }

    func pull() async {
        guard let session = auth.session else { return }
        isSyncing = true
        defer { isSyncing = false }
        do {
            let token = try await auth.accessToken()
            let rows: [AccountSyncRow] = try await auth.supabaseRequest(
                "/rest/v1/account_sync_state?user_id=eq.\(session.userId)&select=user_id,payload,updated_at",
                token: token
            )
            if let rawPayload = rows.first?.payload,
               let data = rawPayload.data(using: .utf8),
               let decoded = try? decoder.decode(CloudPayload.self, from: data) {
                payload = decoded
            }
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    func save(addons: [InstalledAddon]) async {
        guard let session = auth.session else { return }
        isSyncing = true
        defer { isSyncing = false }
        do {
            let token = try await auth.accessToken()
            payload = CloudPayload(version: 1, addons: addons, updatedAt: Date().timeIntervalSince1970)
            let data = try encoder.encode(payload)
            let raw = String(data: data, encoding: .utf8) ?? "{}"
            let body = AccountSyncUpsert(
                userId: session.userId,
                payload: raw,
                updatedAt: ISO8601DateFormatter().string(from: Date())
            )
            let _: EmptyResponse = try await auth.supabaseRequest(
                "/rest/v1/account_sync_state",
                method: "POST",
                token: token,
                prefer: "resolution=merge-duplicates",
                body: body
            )
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }
}
