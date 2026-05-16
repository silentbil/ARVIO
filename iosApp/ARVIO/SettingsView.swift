import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Settings")
                    .font(.system(size: 38, weight: .bold))
                    .foregroundStyle(ArvioTheme.textPrimary)

                cloudPanel
                traktPanel
                SettingsRow(title: "Cloud sync", value: appState.cloud.isSyncing ? "Syncing" : (appState.auth.isAuthenticated ? "Connected" : "Disconnected"))
                SettingsRow(title: "Addons", value: "\(appState.addons.addons.count) installed")
                SettingsRow(title: "Playback", value: "AVPlayer parity work pending")
            }
            .padding(28)
        }
    }

    private var cloudPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Cloud login")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(ArvioTheme.textPrimary)
                    Text(appState.auth.session?.email ?? "Use the same ARVIO account as Android.")
                        .font(.system(size: 14))
                        .foregroundStyle(ArvioTheme.textSecondary)
                }
                Spacer()
                if appState.auth.isAuthenticated {
                    Button("Sign out") { appState.auth.signOut() }
                        .buttonStyle(.bordered)
                }
            }

            if !appState.auth.isAuthenticated {
                TextField("Email", text: $email)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                    .settingsField()
                SecureField("Password", text: $password)
                    .settingsField()
                HStack {
                    Button("Sign in") {
                        Task { await appState.auth.signIn(email: email, password: password) }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(ArvioTheme.gold)

                    Button("Create account") {
                        Task { await appState.auth.signUp(email: email, password: password) }
                    }
                    .buttonStyle(.bordered)
                }
            }

            if let error = appState.auth.errorMessage {
                Text(error)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Color.red.opacity(0.9))
            }
        }
        .settingsPanel()
    }

    private var traktPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Trakt")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(ArvioTheme.textPrimary)
                    Text(appState.trakt.isConnected ? "Connected. Watchlist sync is active." : "Link Trakt to mirror Android watchlist/history.")
                        .font(.system(size: 14))
                        .foregroundStyle(ArvioTheme.textSecondary)
                }
                Spacer()
                if appState.trakt.isConnected {
                    Button("Disconnect") { appState.trakt.disconnect() }
                        .buttonStyle(.bordered)
                } else {
                    Button("Link") {
                        Task { await appState.trakt.beginDeviceLink() }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(ArvioTheme.gold)
                }
            }

            if let code = appState.trakt.deviceCode {
                VStack(alignment: .leading, spacing: 6) {
                    Text(code.userCode)
                        .font(.system(size: 34, weight: .bold, design: .monospaced))
                        .foregroundStyle(ArvioTheme.gold)
                    Text(code.verificationURL)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(ArvioTheme.textSecondary)
                    Button("I approved it") {
                        Task { await appState.trakt.pollForToken() }
                    }
                    .buttonStyle(.bordered)
                }
            }

            if let error = appState.trakt.errorMessage {
                Text(error)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Color.red.opacity(0.9))
            }
        }
        .settingsPanel()
    }
}

struct SettingsRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(ArvioTheme.textPrimary)
            Spacer()
            Text(value)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(ArvioTheme.textSecondary)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 8).fill(ArvioTheme.panel))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(ArvioTheme.border, lineWidth: 1))
    }
}

private extension View {
    func settingsPanel() -> some View {
        padding(18)
            .background(RoundedRectangle(cornerRadius: 8).fill(ArvioTheme.panel))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(ArvioTheme.border, lineWidth: 1))
    }

    func settingsField() -> some View {
        padding(14)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.28)))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(ArvioTheme.border, lineWidth: 1))
            .foregroundStyle(ArvioTheme.textPrimary)
    }
}
