import AVFoundation
import CallKit
import XCTest
@_spi(TetherApp) import TetherCore
@testable import Tether

/// CallKit's provider callbacks (#15): they arrive on the main queue in CallKit's order and must take effect right
/// there. Wrapped in a Task each, a didDeactivate could run before the didActivate that came first.
@MainActor
final class CallControllerTests: XCTestCase {
    var m: AppModel { AppModel.shared }
    private var session: AVAudioSession { AVAudioSession.sharedInstance() }

    override func setUp() async throws {
        await MainActor.run {
            AppModel.shared.resetForTests()
            let c = AppModel.shared.calls
            c.providerDidReset(c.provider)
        }
    }

    func testSessionCallbacksTakeEffectAtOnceAndInOrder() {
        let c = m.calls
        let p = c.provider
        XCTAssertFalse(c.audioSessionActive)
        c.provider(p, didActivate: session)
        XCTAssertTrue(c.audioSessionActive, "applied before the callback returns, not in a later Task")
        c.provider(p, didDeactivate: session)
        XCTAssertFalse(c.audioSessionActive)
        // A quick deactivate / activate pair (a second call taking over the session) ends active.
        c.provider(p, didDeactivate: session)
        c.provider(p, didActivate: session)
        XCTAssertTrue(c.audioSessionActive)
        c.providerDidReset(p)
        XCTAssertFalse(c.audioSessionActive, "a reset forgets the session")
        XCTAssertNil(m.callScreen)
    }

    func testLostProviderMatchesUnknownCallProviderOnly() {
        let unknown = NSError(domain: CXErrorDomainRequestTransaction,
                              code: CXErrorCodeRequestTransactionError.Code.unknownCallProvider.rawValue)
        let empty = NSError(domain: CXErrorDomainRequestTransaction,
                            code: CXErrorCodeRequestTransactionError.Code.emptyTransaction.rawValue)
        let other = NSError(domain: NSCocoaErrorDomain,
                            code: CXErrorCodeRequestTransactionError.Code.unknownCallProvider.rawValue)
        XCTAssertTrue(CallController.lostProvider(unknown))
        XCTAssertFalse(CallController.lostProvider(empty))
        XCTAssertFalse(CallController.lostProvider(other))
    }

    func testRebuildCallKitReplacesProviderAndIgnoresStaleCallbacks() {
        let c = m.calls
        let old = c.provider
        c.rebuildCallKit("test")
        XCTAssertTrue(c.provider !== old, "idle rebuild replaces the provider")

        // A retried outgoing call already has its call screen and active session on the new provider:
        // a late providerDidReset or didDeactivate from the invalidated old provider must not wipe it out.
        let newProvider = c.provider
        m.callScreen = CallScreen(uuid: UUID(), number: "+10000000001", name: nil, state: "dialing", incoming: false, sub: nil)
        c.provider(newProvider, didActivate: session)
        XCTAssertTrue(c.audioSessionActive)

        c.provider(old, didDeactivate: session)
        c.providerDidReset(old)
        XCTAssertTrue(c.audioSessionActive, "stale provider callbacks are ignored")
        XCTAssertNotNil(m.callScreen, "stale providerDidReset does not clear the new call screen")
    }

    func testRebuildCallKitDefersWhileCallIsActiveAndRunsAfterLinkLost() {
        let c = m.calls
        let before = c.provider
        c.handle(CallInfo(cid: "c1", num: "+10000000001", name: nil, state: "active", dir: "out", sub: nil, since: nil, cause: nil, hold: true, elsewhere: nil, at: nil))
        c.rebuildCallKit("during call")
        XCTAssertTrue(c.provider === before, "rebuild waits while a call is up")

        c.linkLost()
        XCTAssertTrue(c.provider !== before, "pending rebuild runs once linkLost clears all calls")
    }

    private func call(_ state: String, tone: String? = nil, dir: String = "out", cid: String = "c1") -> CallInfo {
        CallInfo(cid: cid, num: "+10000000001", state: state, dir: dir, cause: tone == "busy" ? "BUSY" : nil, hold: true, tone: tone)
    }

    /// A dialed call that ends busy: the call screen says "Busy" and the tone plays for CallEndTone.seconds, then the
    /// call ends (CallKit is told after the tone).
    func testBusyToneKeepsTheCallForThreeSecondsThenEnds() async throws {
        let c = m.calls
        c.handle(call("dialing"))
        c.handle(call("ended", tone: "busy"))
        XCTAssertEqual(m.callScreen?.state, "ended")
        XCTAssertEqual(m.callScreen?.endTone, .busy)
        XCTAssertEqual(m.audio.endTone, .busy)
        XCTAssertFalse(m.audio.ringback, "the ringback gives way to the tone")
        try await Task.sleep(nanoseconds: UInt64((CallEndTone.seconds + 0.5) * 1e9))
        XCTAssertNil(m.callScreen, "the call ended after the tone")
        XCTAssertNil(m.audio.endTone)
    }

    func testEndingDuringTheToneStopsItAtOnce() {
        let c = m.calls
        c.handle(call("active"))
        c.handle(call("ended", tone: "congestion"))
        guard let uuid = m.callScreen?.uuid else { return XCTFail("no call screen") }
        XCTAssertEqual(m.callScreen?.endTone, .congestion)
        c.end(uuid)
        XCTAssertNil(m.callScreen)
        XCTAssertNil(m.audio.endTone)
    }

    func testANewCallStopsTheTone() {
        let c = m.calls
        c.handle(call("active"))
        c.handle(call("ended", tone: "busy"))
        c.handle(call("ringing", dir: "in", cid: "c2"))
        XCTAssertNil(m.audio.endTone)
        XCTAssertNotEqual(m.callScreen?.state, "ended")
    }

    func testNoToneEndsAtOnce() {
        let c = m.calls
        c.handle(call("active"))
        c.handle(call("ended", tone: "ended"))
        XCTAssertNil(m.callScreen)
        XCTAssertNil(m.audio.endTone)
    }
}
