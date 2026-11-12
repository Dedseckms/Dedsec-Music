// swift-tools-version: 5.9
import PackageDescription

let package = Package(name: "MusicCore", platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "MusicCore", targets: ["MusicCore"])],
    targets: [.target(name: "MusicCore"), .testTarget(name: "MusicCoreTests", dependencies: ["MusicCore"])])
