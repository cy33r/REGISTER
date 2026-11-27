import SwiftUI
import UIKit

/// REGISTER: the whole app in Vazirmatn, a Persian typeface (SIL Open Font License, Resources/Fonts/Vazirmatn-OFL.txt).
/// SwiftUI text gets it through `Font.v(_:)` and the root view's default font; bars, tabs and segmented controls
/// through UIKit appearance. Sizes follow Dynamic Type (`relativeTo:`), as the system text styles do.
enum AppFont {
    static let family = "Vazirmatn"

    /// Vazirmatn's letters are drawn larger than San Francisco's at the same point size (taller x-height and line
    /// height): scaled so Persian text lines up with the system text, numbers and icons around it.
    static let scale: CGFloat = 0.9

    /// The system text style sizes at the default Dynamic Type setting.
    static func size(_ style: Font.TextStyle) -> CGFloat {
        switch style {
        case .largeTitle: 34
        case .title: 28
        case .title2: 22
        case .title3: 20
        case .headline, .body: 17
        case .callout: 16
        case .subheadline: 15
        case .footnote: 13
        case .caption: 12
        case .caption2: 11
        @unknown default: 17
        }
    }

    static func ui(_ size: CGFloat, _ face: String = "Vazirmatn-Regular") -> UIFont {
        UIFont(name: face, size: (size * scale).rounded()) ?? .systemFont(ofSize: size)
    }

    /// Navigation bar titles, tab bar labels, bar buttons and segmented controls in Vazirmatn.
    static func applyAppearance() {
        let title: [NSAttributedString.Key: Any] = [.font: ui(17, "Vazirmatn-Bold")]
        let large: [NSAttributedString.Key: Any] = [.font: ui(34, "Vazirmatn-Bold")]

        let nav = UINavigationBarAppearance()
        nav.configureWithDefaultBackground()
        nav.titleTextAttributes = title
        nav.largeTitleTextAttributes = large
        let navEdge = UINavigationBarAppearance()
        navEdge.configureWithTransparentBackground()
        navEdge.titleTextAttributes = title
        navEdge.largeTitleTextAttributes = large
        let bar = UINavigationBar.appearance()
        bar.standardAppearance = nav
        bar.compactAppearance = nav
        bar.scrollEdgeAppearance = navEdge

        let item = UITabBarItemAppearance()
        item.normal.titleTextAttributes = [.font: ui(10, "Vazirmatn-Medium")]
        item.selected.titleTextAttributes = [.font: ui(10, "Vazirmatn-Bold")]
        let tabs = UITabBarAppearance()
        tabs.configureWithDefaultBackground()
        tabs.stackedLayoutAppearance = item
        tabs.inlineLayoutAppearance = item
        tabs.compactInlineLayoutAppearance = item
        UITabBar.appearance().standardAppearance = tabs
        UITabBar.appearance().scrollEdgeAppearance = tabs

        UIBarButtonItem.appearance().setTitleTextAttributes([.font: ui(17)], for: .normal)
        UIBarButtonItem.appearance().setTitleTextAttributes([.font: ui(17)], for: .highlighted)
        UISegmentedControl.appearance().setTitleTextAttributes([.font: ui(13, "Vazirmatn-Medium")], for: .normal)
        UISegmentedControl.appearance().setTitleTextAttributes([.font: ui(13, "Vazirmatn-Bold")], for: .selected)
    }
}

extension Font {
    /// A system text style in Vazirmatn (headline keeps its semibold weight).
    static func v(_ style: Font.TextStyle) -> Font {
        let f = Font.custom(AppFont.family, size: (AppFont.size(style) * AppFont.scale).rounded(), relativeTo: style)
        return style == .headline ? f.weight(.semibold) : f
    }
}
