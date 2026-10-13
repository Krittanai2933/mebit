import XCTest

// One UI action against the installed com.mebit.app. verify.sh ios-tap, ios-see and ios-gone set
// UI_ACTION (tap, see or gone), UI_KIND (label or identifier), UI_CONTAINS (0 or 1) and UI_LABEL
// through TEST_RUNNER_-prefixed environment variables.
final class IosUi: XCTestCase {
    func testAction() throws {
        let env = ProcessInfo.processInfo.environment
        let action = try XCTUnwrap(env["UI_ACTION"], "UI_ACTION is not set")
        let value = try XCTUnwrap(env["UI_LABEL"], "UI_LABEL is not set")
        let property = env["UI_KIND"] == "identifier" ? "identifier" : "label"
        let comparison = env["UI_CONTAINS"] == "1" ? "CONTAINS" : "=="

        let app = XCUIApplication(bundleIdentifier: "com.mebit.app")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "com.mebit.app is not in the foreground")

        let predicate = NSPredicate(format: "\(property) \(comparison) %@", value)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        switch action {
        case "tap":
            XCTAssertTrue(element.waitForExistence(timeout: 10), "no element with \(property) \(comparison) '\(value)'")
            element.tap()
        case "see":
            XCTAssertTrue(element.waitForExistence(timeout: 10), "no element with \(property) \(comparison) '\(value)'")
        case "gone":
            XCTAssertTrue(element.waitForNonExistence(timeout: 10), "element with \(property) \(comparison) '\(value)' is still on screen")
        default:
            XCTFail("UI_ACTION must be tap, see or gone, not '\(action)'")
        }
    }
}
