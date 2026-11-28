import Foundation
import SwiftUI
import UIKit

/// REGISTER: looks up the newest stable release on GitHub (only api.github.com, only this project) and, when it is newer
/// than this app, offers its iPhone file. iOS does not let an app install itself: the file is downloaded in Safari and
/// the user saves it and signs it with their own tool (Feather, SideStore, …).
@MainActor
final class UpdateCheck: ObservableObject {
    static let shared = UpdateCheck()

    static let repo = "cy33r/REGISTER"
    static let releasesPage = URL(string: "https://github.com/\(repo)/releases/latest")!
    private static let latestAPI = URL(string: "https://api.github.com/repos/\(repo)/releases/latest")!
    private static let autoKey = "register.update.auto"
    private static let lastKey = "register.update.last"

    enum State: Equatable {
        case idle, checking, upToDate
        case available(version: String, ipa: URL?)
        case failed
    }

    @Published private(set) var state: State = .idle

    /// Weekly check when the app opens (on by default; a switch in About turns it off).
    var auto: Bool {
        get { UserDefaults.standard.object(forKey: Self.autoKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: Self.autoKey); objectWillChange.send() }
    }

    var installed: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }

    var hasNews: Bool { if case .available = state { return true } else { return false } }

    func checkIfDue() {
        guard auto, !AppModel.runningTests else { return }
        let last = UserDefaults.standard.double(forKey: Self.lastKey)
        if Date().timeIntervalSince1970 - last > 7 * 24 * 3600 { Task { await check() } }
    }

    func check() async {
        guard state != .checking else { return }
        state = .checking
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.lastKey)
        do {
            var req = URLRequest(url: Self.latestAPI, timeoutInterval: 20)
            req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            req.setValue("REGISTER-iPhone", forHTTPHeaderField: "User-Agent")
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode == 200,
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { state = .failed; return }
            state = Self.parse(json, installed: installed)
        } catch {
            state = .failed
        }
    }

    /// The newest stable release, if newer than [installed], with its iPhone file (only this project's own download address).
    nonisolated static func parse(_ json: [String: Any], installed: String) -> State {
        guard let tag = json["tag_name"] as? String, json["draft"] as? Bool != true, json["prerelease"] as? Bool != true
        else { return .failed }
        let version = tag.hasPrefix("v") || tag.hasPrefix("V") ? String(tag.dropFirst()) : tag
        guard isNewer(version, than: installed) else { return .upToDate }
        let prefix = "https://github.com/\(repo)/releases/download/"
        let ipa = (json["assets"] as? [[String: Any]] ?? [])
            .compactMap { $0["browser_download_url"] as? String }
            .first { $0.hasPrefix(prefix) && $0.hasSuffix(".ipa") && $0.contains("iPhone") }
            .flatMap(URL.init(string:))
        return .available(version: version, ipa: ipa)
    }

    /// "1.10.0" > "1.9.2"; only the numeric x.y.z part counts.
    nonisolated static func isNewer(_ a: String, than b: String) -> Bool {
        func parts(_ s: String) -> [Int] {
            s.split(separator: "-").first.map { $0.split(separator: ".").map { Int($0) ?? 0 } } ?? []
        }
        let x = parts(a), y = parts(b)
        for i in 0..<max(x.count, y.count) {
            let l = i < x.count ? x[i] : 0, r = i < y.count ? y[i] : 0
            if l != r { return l > r }
        }
        return false
    }
}

/// About › updates: check, the result, and the download (in Safari) or Feather.
struct UpdateRows: View {
    @ObservedObject private var updates = UpdateCheck.shared
    @Environment(\.openURL) private var openURL

    var body: some View {
        switch updates.state {
        case .available(let version, let ipa):
            VStack(alignment: .leading, spacing: 4) {
                Label { Text("Version \(version) is out") } icon: { Image(systemName: "sparkles").foregroundStyle(.green) }
                    .font(.v(.headline))
                JustifiedText("Download it, then sign and install it with your tool (Feather). Your data and pairing stay.")
            }
            .padding(.vertical, 2)
            Button {
                openURL(ipa ?? UpdateCheck.releasesPage)
            } label: {
                Label("Download the new version", systemImage: "arrow.down.circle.fill")
            }
            Button {
                // Feather's own address opens it; without Feather, the release page.
                if let f = URL(string: "feather://") { openURL(f) { ok in if !ok { openURL(UpdateCheck.releasesPage) } } }
            } label: {
                Label("Open Feather", systemImage: "signature")
            }
        case .checking:
            HStack { ProgressView(); Text("Checking…").foregroundStyle(.secondary).padding(.leading, 6) }
        default:
            Button {
                Task { await updates.check() }
            } label: {
                Label("Check for updates", systemImage: "arrow.triangle.2.circlepath")
            }
            if updates.state == .upToDate {
                Label("You have the newest version", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
            } else if updates.state == .failed {
                Label("Could not reach GitHub. Check the internet.", systemImage: "wifi.exclamationmark").foregroundStyle(.orange)
            }
        }
        Toggle(isOn: Binding(get: { updates.auto }, set: { updates.auto = $0 })) { Text("Check every week") }
    }
}
