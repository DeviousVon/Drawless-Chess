import AppKit
import CoreGraphics
import Foundation

guard let outputPath = CommandLine.arguments.dropFirst().last else {
    fputs("Usage: swift RenderAppIcon.swift <output.png>\n", stderr)
    exit(2)
}

let pixels = 1024
let colorSpace = CGColorSpaceCreateDeviceRGB()
guard let context = CGContext(
    data: nil,
    width: pixels,
    height: pixels,
    bitsPerComponent: 8,
    bytesPerRow: pixels * 4,
    space: colorSpace,
    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
) else {
    fatalError("Could not create the app-icon bitmap")
}

let green = CGColor(red: 0x17 / 255, green: 0x4E / 255, blue: 0x43 / 255, alpha: 1)
let gold = CGColor(red: 0xF7 / 255, green: 0xE9 / 255, blue: 0xB0 / 255, alpha: 1)
context.setFillColor(green)
context.fill(CGRect(x: 0, y: 0, width: pixels, height: pixels))

// This is the Android RC1 adaptive-icon vector, rendered without reinterpretation.
let scale = CGFloat(pixels) / 108
context.translateBy(x: 0, y: CGFloat(pixels))
context.scaleBy(x: scale, y: -scale)

let king = CGMutablePath()
king.move(to: CGPoint(x: 51, y: 22))
king.addLine(to: CGPoint(x: 57, y: 22))
king.addLine(to: CGPoint(x: 57, y: 31))
king.addLine(to: CGPoint(x: 65, y: 31))
king.addLine(to: CGPoint(x: 65, y: 37))
king.addLine(to: CGPoint(x: 57, y: 37))
king.addLine(to: CGPoint(x: 57, y: 45))
king.addCurve(
    to: CGPoint(x: 69, y: 62),
    control1: CGPoint(x: 66, y: 47),
    control2: CGPoint(x: 71, y: 54)
)
king.addCurve(
    to: CGPoint(x: 61, y: 75),
    control1: CGPoint(x: 68, y: 67),
    control2: CGPoint(x: 64, y: 71)
)
king.addLine(to: CGPoint(x: 68, y: 75))
king.addLine(to: CGPoint(x: 73, y: 87))
king.addLine(to: CGPoint(x: 35, y: 87))
king.addLine(to: CGPoint(x: 40, y: 75))
king.addLine(to: CGPoint(x: 47, y: 75))
king.addCurve(
    to: CGPoint(x: 39, y: 62),
    control1: CGPoint(x: 44, y: 71),
    control2: CGPoint(x: 40, y: 67)
)
king.addCurve(
    to: CGPoint(x: 51, y: 45),
    control1: CGPoint(x: 37, y: 54),
    control2: CGPoint(x: 42, y: 47)
)
king.addLine(to: CGPoint(x: 51, y: 37))
king.addLine(to: CGPoint(x: 43, y: 37))
king.addLine(to: CGPoint(x: 43, y: 31))
king.addLine(to: CGPoint(x: 51, y: 31))
king.closeSubpath()

context.addPath(king)
context.setFillColor(gold)
context.fillPath()

let inset = CGMutablePath()
inset.move(to: CGPoint(x: 43, y: 62))
inset.addCurve(
    to: CGPoint(x: 54, y: 68),
    control1: CGPoint(x: 46, y: 66),
    control2: CGPoint(x: 50, y: 68)
)
inset.addCurve(
    to: CGPoint(x: 65, y: 62),
    control1: CGPoint(x: 58, y: 68),
    control2: CGPoint(x: 62, y: 66)
)
inset.addCurve(
    to: CGPoint(x: 54, y: 74),
    control1: CGPoint(x: 65, y: 69),
    control2: CGPoint(x: 60, y: 74)
)
inset.addCurve(
    to: CGPoint(x: 43, y: 62),
    control1: CGPoint(x: 48, y: 74),
    control2: CGPoint(x: 43, y: 69)
)
inset.closeSubpath()
context.addPath(inset)
context.setFillColor(green)
context.fillPath()

context.setFillColor(gold)
context.fill(CGRect(x: 31, y: 88, width: 46, height: 7))

guard let image = context.makeImage() else { fatalError("Could not finish the app icon") }
let representation = NSBitmapImageRep(cgImage: image)
guard let png = representation.representation(using: .png, properties: [:]) else {
    fatalError("Could not encode the app icon")
}
try png.write(to: URL(fileURLWithPath: outputPath), options: .atomic)
