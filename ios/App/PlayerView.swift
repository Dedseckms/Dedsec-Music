// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import MusicCore

struct PlayerView: View {
    @EnvironmentObject private var store: MusicStore
    @Environment(\.dismiss) private var dismiss
    @State private var add = false
    @State private var dragged: Double?
    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 8) {
                HStack { Button { dismiss() } label: { Image(systemName: "chevron.down").frame(width: 36, height: 36) }; Spacer(); Text(store.source).font(.caption).lineLimit(1); Spacer(); Color.clear.frame(width: 36, height: 36) }
                Spacer(minLength: 0)
                let side = min(geometry.size.width - 48, max(100, geometry.size.height - 420), 360)
                Artwork(id: store.now?.coverArt, size: side)
                    .gesture(DragGesture(minimumDistance: 35).onEnded { value in
                        if abs(value.translation.width) > abs(value.translation.height) && abs(value.translation.width) > 55 { store.skip(value.translation.width < 0) }
                        else if value.translation.height > 75 { dismiss() }
                    })
                VStack(spacing: 7) { Text(store.now?.title ?? "Выбери трек").font(.title2.bold()).lineLimit(2).multilineTextAlignment(.center); Text(store.now?.artist ?? "").foregroundStyle(.secondary).lineLimit(1) }
                VStack(spacing: 1) {
                    Slider(value: Binding(get: { dragged ?? min(store.position, max(1, store.duration)) }, set: { dragged = $0 }), in: 0...max(1, store.duration)) { editing in
                        if !editing, let dragged { store.seek(dragged); self.dragged = nil }
                    }
                    HStack { Text(time(dragged ?? store.position)); Spacer(); Text(time(store.duration)) }.font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
                HStack(spacing: 24) {
                    Button { store.shuffle() } label: { Image(systemName: "shuffle").foregroundStyle(store.shuffled ? Color.accentColor : .secondary) }
                    Button { store.skip(false) } label: { Image(systemName: "backward.end.fill").font(.title) }
                    Button { store.toggle() } label: { ZStack { RoundedRectangle(cornerRadius: 22).fill(Color.accentColor).frame(width: 72, height: 64); if store.buffering { ProgressView().tint(.black) } else { Image(systemName: store.playing ? "pause.fill" : "play.fill").font(.title).foregroundStyle(.black) } } }
                    Button { store.skip(true) } label: { Image(systemName: "forward.end.fill").font(.title) }
                    Button { store.repeated.toggle() } label: { Image(systemName: "repeat.1").foregroundStyle(store.repeated ? Color.accentColor : .secondary) }
                }.padding(.vertical, 5)
                HStack(spacing: 18) {
                    if let now = store.now {
                        Button { Task { await store.favorite(now) } } label: { Image(systemName: store.favorites.contains { $0.id == now.id } ? "heart.fill" : "heart").font(.title3) }
                        Button { add = true } label: { Label("В плейлист", systemImage: "text.badge.plus").font(.subheadline) }.buttonStyle(.bordered)
                        if store.offline.contains(where: { $0.id == now.id }) { Image(systemName: "checkmark.circle.fill").foregroundStyle(.secondary).accessibilityLabel("Скачано") }
                        else { Button { Task { await store.download(now) } } label: { if store.downloading == now.id { ProgressView() } else { Image(systemName: "arrow.down.circle").font(.title3) } } }
                    }
                }
                Spacer(minLength: 0)
            }.padding(.horizontal, 24).padding(.bottom, 12)
        }.background(Color(.systemBackground)).sheet(isPresented: $add) { if let now = store.now { PlaylistPicker(song: now) } }
    }
    private func time(_ seconds: Double) -> String { let value = max(0, Int(seconds)); return String(format: "%d:%02d", value / 60, value % 60) }
}
struct SettingsView: View {
    @EnvironmentObject private var store: MusicStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appearance") private var appearance = "dark"
    @AppStorage("accent") private var accent = "blue"
    @AppStorage("wifiOnly") private var wifiOnly = true
    @State private var isAdmin = false
    var body: some View {
        NavigationStack {
            Form {
                Section { HStack(spacing: 16) {
                    Text(String(store.account?.username.prefix(1) ?? "D").uppercased()).font(.title.bold()).frame(width: 56, height: 56).background(Color.accentColor.opacity(0.2), in: Circle())
                    VStack(alignment: .leading, spacing: 5) { Text(store.account?.username ?? "").font(.headline); Text("Личные плейлисты · Общая музыка").font(.caption).foregroundStyle(.secondary) }
                }.padding(.vertical, 8) }
                if isAdmin, let api = store.api { Section("Администрирование") { NavigationLink { AdminView(api: api) } label: { Label("Пользователи и их загрузки", systemImage: "person.2.badge.gearshape") } } }
                Section("Оформление") {
                    Picker("Тема", selection: $appearance) { Text("Тёмная").tag("dark"); Text("Светлая").tag("light"); Text("Как в системе").tag("system") }
                    Picker("Акцент", selection: $accent) { Text("Синий").tag("blue"); Text("Сирень").tag("violet"); Text("Мята").tag("mint"); Text("Роза").tag("rose"); Text("Янтарь").tag("amber") }
                }
                Section("Офлайн") {
                    Toggle("Скачивать только по Wi-Fi", isOn: $wifiOnly).onChange(of: wifiOnly) { _, _ in store.downloadNetworkChanged() }
                    Toggle("Скачивать прослушанное", isOn: Binding(get: { store.autoDownloadListened }, set: { store.autoDownloadListened = $0 }))
                    Text("После прослушивания 80% трека").font(.caption).foregroundStyle(.secondary)
                    Toggle("Скачивать любимое", isOn: Binding(get: { store.autoDownloadFavorites }, set: { store.autoDownloadFavorites = $0 }))
                    Button { store.downloadAll(store.favorites) } label: { Label("Скачать всё любимое", systemImage: "arrow.down.circle") }
                    Text("\(store.offline.count) на iPhone · \(store.pendingOffline.count) в очереди").foregroundStyle(.secondary)
                    if store.downloadsPaused { Button("Продолжить очередь скачивания") { store.resumeDownloads() } }
                }
                Section("Обновления") {
                    Link(destination: URL(string: "https://github.com/Dedseckms/Dedsec-Music/releases")!) { Label("Версии на GitHub", systemImage: "arrow.up.right.square") }
                    Text("Новую IPA установи через AltStore. Бесплатная подпись Apple требует регулярного обновления.").font(.caption).foregroundStyle(.secondary)
                }
                Section { Link("Лицензия GPL-3.0-or-later", destination: URL(string: "https://github.com/Dedseckms/Dedsec-Music/blob/main/LICENSE")!); Text("DedSec Music · iOS 0.1.2").font(.caption).foregroundStyle(.secondary)
                    Button("Выйти из аккаунта", role: .destructive) { store.logout(); dismiss() }
                }
            }.navigationTitle("Настройки").navigationBarTitleDisplayMode(.inline).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
                .task { isAdmin = (try? await store.api?.isAdmin()) == true }
        }
    }
}
