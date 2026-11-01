// Prints every QR code Core Image finds in an image file, to prove that a code the app drew on screen reads.
// Build once: swiftc -O qrdecode.swift -o /tmp/qrdecode
// Run:        /tmp/qrdecode screenshot.png   (exit 0 when a code was found, 1 when none, 2 on a bad argument)
import CoreImage
import Foundation

let args = CommandLine.arguments
guard args.count >= 2 else {
    print("usage: qrdecode image.png")
    exit(2)
}
guard let image = CIImage(contentsOf: URL(fileURLWithPath: args[1])) else {
    print("cannot read \(args[1])")
    exit(2)
}
let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
let codes = detector.features(in: image).compactMap { $0 as? CIQRCodeFeature }
print("found \(codes.count)")
for code in codes {
    print(code.messageString ?? "<no text>")
}
exit(codes.isEmpty ? 1 : 0)
