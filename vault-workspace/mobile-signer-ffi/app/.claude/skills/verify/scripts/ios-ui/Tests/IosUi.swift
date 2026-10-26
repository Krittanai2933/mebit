import XCTest

// One UI action against the installed com.mebit.app (MEBIT_BUNDLE_ID replaces the id on a phone whose team
// cannot own it). verify.sh ios-tap, ios-see and ios-gone set UI_ACTION (tap, see or gone), UI_KIND (label or
// identifier), UI_CONTAINS (0 or 1) and UI_LABEL through TEST_RUNNER_-prefixed environment variables.
final class IosUi: XCTestCase {
    func testAction() throws {
        let env = ProcessInfo.processInfo.environment
        let action = try XCTUnwrap(env["UI_ACTION"], "UI_ACTION is not set")
        let value = try XCTUnwrap(env["UI_LABEL"], "UI_LABEL is not set")
        let property = env["UI_KIND"] == "identifier" ? "identifier" : "label"
        let comparison = env["UI_CONTAINS"] == "1" ? "CONTAINS" : "=="

        let bundle = env["MEBIT_BUNDLE_ID"] ?? "com.mebit.app"
        let app = XCUIApplication(bundleIdentifier: bundle)
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "\(bundle) is not in the foreground")

        let predicate = NSPredicate(format: "\(property) \(comparison) %@", value)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        switch action {
        case "tap":
            // Lazy catalog rows may be absent from the a11y tree until scrolled into range.
            if !element.waitForExistence(timeout: 2) {
                var scrolls = 0
                while !element.exists && scrolls < 12 {
                    app.swipeUp()
                    scrolls += 1
                    _ = element.waitForExistence(timeout: 0.4)
                }
            }
            XCTAssertTrue(element.waitForExistence(timeout: 5), "no element with \(property) \(comparison) '\(value)'")
            // ModalBottomSheet rows can exist but sit off-screen (not hittable).
            if !element.isHittable {
                var scrolls = 0
                while !element.isHittable && scrolls < 12 {
                    app.swipeUp()
                    scrolls += 1
                }
            }
            XCTAssertTrue(
                element.isHittable,
                "element with \(property) \(comparison) '\(value)' is not hittable after scroll"
            )
            // Settle after scroll so the sheet finishes decelerating before the tap.
            Thread.sleep(forTimeInterval: 0.35)
            if element.isHittable {
                element.tap()
            } else {
                element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            }
        case "see":
            XCTAssertTrue(element.waitForExistence(timeout: 10), "no element with \(property) \(comparison) '\(value)'")
        case "gone":
            XCTAssertTrue(element.waitForNonExistence(timeout: 10), "element with \(property) \(comparison) '\(value)' is still on screen")
        default:
            XCTFail("UI_ACTION must be tap, see or gone, not '\(action)'")
        }
    }
}
