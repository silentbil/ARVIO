import SwiftUI

struct AddonsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var installURL = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Addons")
                        .font(.system(size: 38, weight: .bold))
                        .foregroundStyle(ArvioTheme.textPrimary)
                    Text("Install Stremio-compatible addon manifests and sync them through your ARVIO cloud account.")
                        .font(.system(size: 17))
                        .foregroundStyle(ArvioTheme.textSecondary)
                }

                HStack(spacing: 12) {
                    TextField("https://example.com/manifest.json", text: $installURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .padding(16)
                        .background(RoundedRectangle(cornerRadius: 8).fill(ArvioTheme.panel))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(ArvioTheme.border, lineWidth: 1))
                        .foregroundStyle(ArvioTheme.textPrimary)

                    Button("Install") {
                        appState.addons.installURL = installURL
                        Task {
                            await appState.addons.install()
                            installURL = appState.addons.installURL
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(ArvioTheme.gold)
                }

                if let error = appState.addons.errorMessage {
                    Text(error)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Color.red.opacity(0.9))
                }

                if appState.addons.addons.isEmpty {
                    EmptyStatePanel(
                        title: "No addons installed",
                        message: appState.auth.isAuthenticated ? "Install an addon URL to make it available across your ARVIO devices." : "Sign in first so addon changes sync to cloud."
                    )
                } else {
                    LazyVStack(spacing: 12) {
                        ForEach(appState.addons.addons) { addon in
                            AddonRow(addon: addon) {
                                Task { await appState.addons.remove(addon) }
                            }
                        }
                    }
                }
            }
            .padding(28)
        }
    }
}

struct AddonRow: View {
    let addon: InstalledAddon
    let onRemove: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Text(addon.name)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(ArvioTheme.textPrimary)
                    Text(addon.version)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ArvioTheme.gold)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(RoundedRectangle(cornerRadius: 6).fill(ArvioTheme.gold.opacity(0.14)))
                }
                if let description = addon.description, !description.isEmpty {
                    Text(description)
                        .font(.system(size: 14))
                        .foregroundStyle(ArvioTheme.textSecondary)
                        .lineLimit(2)
                }
                Text((addon.catalogs + addon.resources).prefix(6).joined(separator: " - "))
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(ArvioTheme.textTertiary)
                    .lineLimit(1)
            }

            Spacer()

            Button("Remove", role: .destructive, action: onRemove)
                .buttonStyle(.bordered)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 8).fill(ArvioTheme.panel))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(ArvioTheme.border, lineWidth: 1))
    }
}
