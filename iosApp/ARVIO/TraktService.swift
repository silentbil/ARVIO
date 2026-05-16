import Foundation

struct TraktDeviceCode: Decodable, Equatable {
    let deviceCode: String
    let userCode: String
    let verificationURL: String
    let expiresIn: Int
    let interval: Int

    enum CodingKeys: String, CodingKey {
        case deviceCode = "device_code"
        case userCode = "user_code"
        case verificationURL = "verification_url"
        case expiresIn = "expires_in"
        case interval
    }
}

struct TraktToken: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let expiresAt: Date

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresAt
    }
}

struct TraktWatchlistItem: Identifiable, Decodable, Hashable {
    let id = UUID()
    let type: String
    let title: String
    let year: Int?

    enum RootKeys: String, CodingKey {
        case type
        case movie
        case show
    }

    enum MediaKeys: String, CodingKey {
        case title
        case year
    }

    init(from decoder: Decoder) throws {
        let root = try decoder.container(keyedBy: RootKeys.self)
        type = try root.decode(String.self, forKey: .type)
        if let movie = try? root.nestedContainer(keyedBy: MediaKeys.self, forKey: .movie) {
            title = (try? movie.decode(String.self, forKey: .title)) ?? "Untitled"
            year = try? movie.decode(Int.self, forKey: .year)
        } else if let show = try? root.nestedContainer(keyedBy: MediaKeys.self, forKey: .show) {
            title = (try? show.decode(String.self, forKey: .title)) ?? "Untitled"
            year = try? show.decode(Int.self, forKey: .year)
        } else {
            title = "Untitled"
            year = nil
        }
    }
}

private struct DeviceCodeRequest: Encodable {
    let clientId: String

    enum CodingKeys: String, CodingKey {
        case clientId = "client_id"
    }
}

private struct DeviceTokenRequest: Encodable {
    let code: String
    let clientId: String

    enum CodingKeys: String, CodingKey {
        case code
        case clientId = "client_id"
    }
}

private struct DeviceTokenResponse: Decodable {
    let accessToken: String
    let refreshToken: String
    let expiresIn: Int

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
    }
}

@MainActor
final class TraktService: ObservableObject {
    @Published private(set) var token: TraktToken?
    @Published private(set) var deviceCode: TraktDeviceCode?
    @Published private(set) var watchlist: [TraktWatchlistItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let client = JSONClient()
    private let keychain = KeychainStore()
    private let tokenAccount = "trakt-token"
    private let baseURL = "https://api.trakt.tv"

    init() {
        token = keychain.load(TraktToken.self, account: tokenAccount)
    }

    var isConnected: Bool {
        token != nil
    }

    func beginDeviceLink() async {
        guard AppConfig.isTraktConfigured else {
            errorMessage = "Trakt is not configured for iOS"
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            guard let url = URL(string: "\(baseURL)/oauth/device/code") else { return }
            deviceCode = try await client.request(
                url,
                method: "POST",
                headers: ["trakt-api-version": "2", "trakt-api-key": AppConfig.traktClientID],
                body: DeviceCodeRequest(clientId: AppConfig.traktClientID)
            )
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func pollForToken() async {
        guard let code = deviceCode?.deviceCode else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            guard let url = URL(string: "\(baseURL)/oauth/device/token") else { return }
            let response: DeviceTokenResponse = try await client.request(
                url,
                method: "POST",
                headers: ["trakt-api-version": "2", "trakt-api-key": AppConfig.traktClientID],
                body: DeviceTokenRequest(code: code, clientId: AppConfig.traktClientID)
            )
            let newToken = TraktToken(
                accessToken: response.accessToken,
                refreshToken: response.refreshToken,
                expiresAt: Date().addingTimeInterval(TimeInterval(response.expiresIn))
            )
            try keychain.save(newToken, account: tokenAccount)
            token = newToken
            deviceCode = nil
            errorMessage = nil
            await loadWatchlist()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func disconnect() {
        token = nil
        watchlist = []
        deviceCode = nil
        keychain.delete(account: tokenAccount)
    }

    func loadWatchlist() async {
        guard let token else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            guard let url = URL(string: "\(baseURL)/sync/watchlist") else { return }
            watchlist = try await client.request(
                url,
                headers: [
                    "trakt-api-version": "2",
                    "trakt-api-key": AppConfig.traktClientID,
                    "Authorization": "Bearer \(token.accessToken)"
                ]
            )
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
