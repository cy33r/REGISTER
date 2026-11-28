import SwiftUI
import UIKit
import ObjectiveC

/// REGISTER: the app's own language (Persian or English in capitals) and look (like the phone, light or dark), chosen
/// inside the app and applied at once, whatever the iPhone's own language and appearance are.
enum AppLang: String, CaseIterable, Identifiable {
    case fa, en
    var id: String { rawValue }

    static let key = "register.lang"

    static var current: AppLang { AppLang(rawValue: UserDefaults.standard.string(forKey: key) ?? "fa") ?? .fa }
    static var isFa: Bool { current == .fa }

    /// The .lproj the texts come from.
    fileprivate(set) static var bundle: Bundle = load(current)

    private static func load(_ l: AppLang) -> Bundle {
        Bundle.main.path(forResource: l.rawValue, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
    }

    var locale: Locale { self == .fa ? Locale(identifier: "fa_IR") : Locale(identifier: "en_US") }
    var direction: LayoutDirection { self == .fa ? .rightToLeft : .leftToRight }
    var label: String { self == .fa ? "فارسی" : "ENGLISH" }

    /// Called once at launch: every lookup in the main bundle (SwiftUI texts, String(localized:)) goes to the chosen
    /// language, and the system's own parts (alerts, permission prompts) follow it from the next launch.
    static func install() {
        object_setClass(Bundle.main, LangBundle.self)
        apply(current)
    }

    static func set(_ l: AppLang) {
        UserDefaults.standard.set(l.rawValue, forKey: key)
        apply(l)
    }

    private static func apply(_ l: AppLang) {
        bundle = load(l)
        UserDefaults.standard.set([l.rawValue], forKey: "AppleLanguages")
        let dir: UISemanticContentAttribute = l == .fa ? .forceRightToLeft : .forceLeftToRight
        UIView.appearance().semanticContentAttribute = dir
        UINavigationBar.appearance().semanticContentAttribute = dir
        UITabBar.appearance().semanticContentAttribute = dir
    }
}

/// The main bundle, answering from the chosen language's table.
private final class LangBundle: Bundle, @unchecked Sendable {
    override func localizedString(forKey key: String, value: String?, table tableName: String?) -> String {
        let b = AppLang.bundle
        return b === self ? super.localizedString(forKey: key, value: value, table: tableName)
            : b.localizedString(forKey: key, value: value, table: tableName)
    }
}

enum AppTheme: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    static let key = "register.theme"

    static var current: AppTheme { AppTheme(rawValue: UserDefaults.standard.string(forKey: key) ?? "system") ?? .system }

    var style: UIUserInterfaceStyle { switch self { case .system: .unspecified; case .light: .light; case .dark: .dark } }

    var title: LocalizedStringKey {
        switch self { case .system: "Like the phone"; case .light: "Light"; case .dark: "Dark" }
    }

    /// Applies the look to every window of the app (the call screen too).
    static func apply(_ t: AppTheme = current) {
        for scene in UIApplication.shared.connectedScenes {
            for w in (scene as? UIWindowScene)?.windows ?? [] { w.overrideUserInterfaceStyle = t.style }
        }
    }
}

/// Language and look, in the Link tab.
struct AppearanceSection: View {
    @AppStorage(AppLang.key) private var lang = AppLang.fa.rawValue
    @AppStorage(AppTheme.key) private var theme = AppTheme.system.rawValue

    var body: some View {
        Section {
            Picker(selection: Binding(get: { lang }, set: { lang = $0; AppLang.set(AppLang(rawValue: $0) ?? .fa) })) {
                ForEach(AppLang.allCases) { l in Text(verbatim: l.label).tag(l.rawValue) }
            } label: { Text("Language") }
            Picker(selection: Binding(get: { theme }, set: { theme = $0; AppTheme.apply(AppTheme(rawValue: $0) ?? .system) })) {
                ForEach(AppTheme.allCases) { t in Text(t.title).tag(t.rawValue) }
            } label: { Text("Appearance") }
        } header: {
            Text("Language and look")
        } footer: {
            JustifiedText("Some parts change after you close and reopen the app.")
        }
    }
}
