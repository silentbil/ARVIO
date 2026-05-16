import Foundation

struct InstalledAddon: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let version: String
    let manifestURL: String
    let description: String?
    let catalogs: [String]
    let resources: [String]
}

private struct AddonManifest: Codable {
    let id: String?
    let name: String
    let version: String?
    let description: String?
    let catalogs: [AddonCatalog]?
    let resources: [AddonResourceValue]?
}

private struct AddonCatalog: Codable {
    let type: String?
    let id: String?
    let name: String?
}

private enum AddonResourceValue: Codable {
    case string(String)
    case object(name: String?)

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(String.self) {
            self = .string(value)
            return
        }
        let object = try container.decode(ResourceObject.self)
        self = .object(name: object.name)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value):
            try container.encode(value)
        case .object(let name):
            try container.encode(ResourceObject(name: name))
        }
    }

    var displayName: String {
        switch self {
        case .string(let value):
            return value
        case .object(let name):
            return name ?? "resource"
        }
    }

    private struct ResourceObject: Codable {
        let name: String?
    }
}

@MainActor
final class AddonService: ObservableObject {
    @Published private(set) var addons: [InstalledAddon] = []
    @Published var installURL = ""
    @Published var errorMessage: String?

    private let cloud: CloudSyncService
    private let storageKey = "arvio.ios.installedAddons"

    init(cloud: CloudSyncService) {
        self.cloud = cloud
        loadLocal()
    }

    func loadFromCloud() {
        if !cloud.payload.addons.isEmpty {
            addons = cloud.payload.addons
            saveLocal()
        }
    }

    func install() async {
        let trimmed = installURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            let addon = try await fetchAddon(from: trimmed)
            if let index = addons.firstIndex(where: { $0.id == addon.id }) {
                addons[index] = addon
            } else {
                addons.append(addon)
            }
            installURL = ""
            errorMessage = nil
            saveLocal()
            await cloud.save(addons: addons)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func remove(_ addon: InstalledAddon) async {
        addons.removeAll { $0.id == addon.id }
        saveLocal()
        await cloud.save(addons: addons)
    }

    private func fetchAddon(from rawURL: String) async throws -> InstalledAddon {
        let manifestURL = normalizedManifestURL(rawURL)
        guard let url = URL(string: manifestURL) else {
            throw ArvioError.invalidURL(rawURL)
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw ArvioError.requestFailed("Addon manifest failed to load")
        }
        let manifest = try JSONDecoder().decode(AddonManifest.self, from: data)
        let catalogs = manifest.catalogs?.compactMap { catalog in
            catalog.name ?? catalog.id ?? catalog.type
        } ?? []
        let resources = manifest.resources?.map(\.displayName) ?? []
        return InstalledAddon(
            id: manifest.id ?? manifestURL,
            name: manifest.name,
            version: manifest.version ?? "1.0.0",
            manifestURL: manifestURL,
            description: manifest.description,
            catalogs: catalogs,
            resources: resources
        )
    }

    private func normalizedManifestURL(_ rawURL: String) -> String {
        if rawURL.hasSuffix("/manifest.json") { return rawURL }
        return rawURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/manifest.json"
    }

    private func loadLocal() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let saved = try? JSONDecoder().decode([InstalledAddon].self, from: data) else {
            return
        }
        addons = saved
    }

    private func saveLocal() {
        guard let data = try? JSONEncoder().encode(addons) else { return }
        UserDefaults.standard.set(data, forKey: storageKey)
    }
}
