import Foundation
import Combine

@MainActor
final class AppState: ObservableObject {
    let auth: AuthService
    let cloud: CloudSyncService
    let addons: AddonService
    let trakt: TraktService
    private var cancellables: Set<AnyCancellable> = []

    init() {
        let auth = AuthService()
        let cloud = CloudSyncService(auth: auth)
        self.auth = auth
        self.cloud = cloud
        self.addons = AddonService(cloud: cloud)
        self.trakt = TraktService()

        [auth.objectWillChange, cloud.objectWillChange, addons.objectWillChange, trakt.objectWillChange]
            .forEach { publisher in
                publisher
                    .sink { [weak self] _ in self?.objectWillChange.send() }
                    .store(in: &cancellables)
            }
    }

    func bootstrap() async {
        await auth.restore()
        await cloud.pull()
        addons.loadFromCloud()
        if trakt.isConnected {
            await trakt.loadWatchlist()
        }
    }
}
