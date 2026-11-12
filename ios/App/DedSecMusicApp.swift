// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

@main struct DedSecMusicApp: App {
    @StateObject private var store = MusicStore()
    @AppStorage("appearance") private var appearance = "dark"
    @AppStorage("accent") private var accent = "blue"
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).tint(Accent.color(accent))
                .preferredColorScheme(appearance == "system" ? nil : appearance == "light" ? .light : .dark)
        }
    }
}
enum Accent {
    static func color(_ name: String) -> Color {
        switch name {
        case "violet": return Color(red: 0.76, green: 0.65, blue: 1)
        case "mint": return Color(red: 0.56, green: 0.86, blue: 0.75)
        case "rose": return Color(red: 1, green: 0.68, blue: 0.80)
        case "amber": return Color(red: 0.96, green: 0.78, blue: 0.47)
        default: return Color(red: 0.66, green: 0.78, blue: 0.98)
        }
    }
}
