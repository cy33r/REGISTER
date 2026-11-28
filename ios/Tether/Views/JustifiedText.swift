import SwiftUI
import UIKit

/// REGISTER: a paragraph set right to left and justified, so every full line is the same width (SwiftUI's Text cannot
/// justify). Vazirmatn at the footnote size, following Dynamic Type; used for section footers and longer notes.
struct JustifiedText: UIViewRepresentable {
    let text: String
    var color: UIColor = .secondaryLabel
    var size: CGFloat = 13

    /// A key of Localizable.strings (no interpolation), in Persian.
    init(_ key: String.LocalizationValue, color: UIColor = .secondaryLabel) {
        text = String(localized: key); self.color = color
    }

    init(verbatim: String, color: UIColor = .secondaryLabel) { text = verbatim; self.color = color }

    func makeUIView(context: Context) -> UILabel {
        let l = UILabel()
        l.numberOfLines = 0
        l.adjustsFontForContentSizeCategory = true
        l.semanticContentAttribute = AppLang.isFa ? .forceRightToLeft : .forceLeftToRight
        l.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        l.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return l
    }

    func updateUIView(_ l: UILabel, context: Context) {
        let p = NSMutableParagraphStyle()
        p.alignment = .justified
        p.baseWritingDirection = AppLang.isFa ? .rightToLeft : .leftToRight
        p.lineSpacing = 2
        let font = UIFontMetrics(forTextStyle: .footnote).scaledFont(for: AppFont.ui(size))
        l.attributedText = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: color, .paragraphStyle: p])
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UILabel, context: Context) -> CGSize? {
        let width = proposal.width ?? 320
        let fit = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: ceil(fit.height))
    }
}
