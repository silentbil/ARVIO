import Foundation

enum AppConfig {
    static let supabaseURL = "__SUPABASE_URL__"
    static let supabaseAnonKey = "__SUPABASE_ANON_KEY__"
    static let traktClientID = "__TRAKT_CLIENT_ID__"

    static var isCloudConfigured: Bool {
        supabaseURL.hasPrefix("https://") &&
        supabaseURL.contains(".supabase.co") &&
        supabaseAnonKey.count > 40
    }

    static var isTraktConfigured: Bool {
        !traktClientID.isEmpty && !traktClientID.hasPrefix("__")
    }
}
