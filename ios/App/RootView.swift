// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import UniformTypeIdentifiers
import MusicCore

enum Route: Hashable { case album(String, String), playlist(String, String), artist(String, String), favorites, offline }

struct RootView: View {
    @EnvironmentObject private var store: MusicStore
    @Environment(\.scenePhase) private var scene
    @State private var fullPlayer = false
    @State private var settings = false
    @State private var addSong: Song?
    var body: some View {
        Group {
            if let account = store.account {
                TabView {
                    stack { HomeView() }.tabItem { Label("Главная", systemImage: "house.fill") }
                    stack { SearchView() }.tabItem { Label("Поиск", systemImage: "magnifyingglass") }
                    stack { LibraryView() }.tabItem { Label("Музыка", systemImage: "music.note.list") }
                    stack { AddMusicView() }.tabItem { Label("Добавить", systemImage: "square.and.arrow.up") }
                }
                .id(DeviceStorage.hash(account.server + account.username))
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if let now = store.now {
                        HStack(spacing: 12) {
                            Artwork(id: now.coverArt, size: 44)
                            VStack(alignment: .leading, spacing: 3) { Text(now.title).font(.subheadline.bold()).lineLimit(1); Text(now.artist ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()).onTapGesture { fullPlayer = true }
                            Button { store.toggle() } label: { Image(systemName: store.playing ? "pause.fill" : "play.fill").frame(width: 32, height: 40) }
                            Button { store.skip(true) } label: { Image(systemName: "forward.end.fill").frame(width: 32, height: 40) }
                        }.padding(10).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20)).padding(.horizontal, 10).padding(.bottom, 4)
                    }
                }
                .environment(\.addSong, { addSong = $0 })
            } else { LoginView() }
        }
        .sheet(isPresented: $fullPlayer) { PlayerView().presentationDragIndicator(.visible) }
        .sheet(isPresented: $settings) { SettingsView() }
        .sheet(item: $addSong) { PlaylistPicker(song: $0) }
        .alert("DedSec Music", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) { Button("Понятно") { store.message = nil } } message: { Text(store.message ?? "") }
        .onChange(of: scene) { _, phase in if phase == .active { store.refreshImport() } }
        .onChange(of: store.account.map { DeviceStorage.hash($0.server + "|" + $0.username) } ?? "") { _, _ in
            fullPlayer = false; settings = false; addSong = nil
        }
    }
    private func stack<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        NavigationStack {
            content().toolbar { ToolbarItem(placement: .topBarTrailing) { Button { settings = true } label: { Image(systemName: "person.crop.circle").accessibilityLabel("Профиль и настройки") } } }
                .navigationDestination(for: Route.self) { DetailView(route: $0) }
        }
    }
}
private struct AddSongKey: EnvironmentKey { static let defaultValue: (Song) -> Void = { _ in } }
extension EnvironmentValues { var addSong: (Song) -> Void { get { self[AddSongKey.self] } set { self[AddSongKey.self] = newValue } } }

struct LoginView: View {
    @EnvironmentObject private var store: MusicStore
    @State private var server = MusicAPI.defaultServer
    @State private var username = ""
    @State private var password = ""
    @State private var remember = true
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Image("BrandMark").resizable().frame(width: 72, height: 72).clipShape(RoundedRectangle(cornerRadius: 22)).padding(.top, 44)
                Text("Своя музыка.\nСвой ритм.").font(.system(size: 40, weight: .bold, design: .rounded))
                Text("Подключись к своей библиотеке Navidrome").foregroundStyle(.secondary)
                VStack(spacing: 14) {
                    TextField("HTTPS-адрес сервера", text: $server).keyboardType(.URL)
                    TextField("Логин", text: $username).textContentType(.username)
                    SecureField("Пароль", text: $password).textContentType(.password)
                }.textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled()
                Toggle("Запомнить вход", isOn: $remember)
                Text("Сохранённый вход защищён Связкой ключей iPhone").font(.caption).foregroundStyle(.secondary)
                Button {
                    let value = password; password = ""
                    Task { await store.login(server: server, username: username, password: value, remember: remember) }
                } label: { HStack { if store.busy { ProgressView() }; Text("Войти в музыку").fontWeight(.semibold); Image(systemName: "arrow.right") }.frame(maxWidth: .infinity).padding(10) }
                    .buttonStyle(.borderedProminent).disabled(store.busy || username.isEmpty || password.isEmpty)
                Text("DEDSEC MUSIC · iOS").font(.caption2).tracking(3).foregroundStyle(.secondary).padding(.top, 20)
            }.padding(28)
        }
    }
}
struct Artwork: View {
    @EnvironmentObject private var store: MusicStore
    let id: String?
    var size: CGFloat = 52
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.18).fill(LinearGradient(colors: [.blue.opacity(0.55), .purple.opacity(0.3)], startPoint: .topLeading, endPoint: .bottomTrailing))
            if let id, let image = store.covers[id] { Image(uiImage: image).resizable().scaledToFill() }
            else { Image(systemName: "waveform").font(.system(size: size * 0.32)).foregroundStyle(.white.opacity(0.7)) }
        }.frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size * 0.18))
            .task(id: id) { store.loadCover(id) }
    }
}
struct SongRows: View {
    @EnvironmentObject private var store: MusicStore
    @Environment(\.addSong) private var add
    let songs: [Song]
    var source = "Библиотека"
    var remove: ((Int) -> Void)? = nil
    var body: some View {
        ForEach(Array(songs.enumerated()), id: \.offset) { index, song in
            HStack(spacing: 12) {
                Button { store.play(songs, at: index, source: source) } label: {
                    HStack(spacing: 12) {
                        Artwork(id: song.coverArt)
                        VStack(alignment: .leading, spacing: 5) {
                            Text(song.title).font(.subheadline.weight(.medium)).foregroundStyle(store.now?.id == song.id ? Color.accentColor : .primary).lineLimit(1)
                            Text(song.artist ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain)
                Menu {
                    Button("Добавить в плейлист", systemImage: "text.badge.plus") { add(song) }
                    Button(store.favorites.contains { $0.id == song.id } ? "Убрать из любимого" : "Мне нравится", systemImage: "heart") { Task { await store.favorite(song) } }
                    if store.offline.contains(where: { $0.id == song.id }) { Button("Удалить офлайн-копию", systemImage: "trash", role: .destructive) { store.removeDownload(song) } }
                    else { Button("Скачать на iPhone", systemImage: "arrow.down.circle") { Task { await store.download(song) } } }
                    if let artistId = song.artistId { NavigationLink("Открыть исполнителя", value: Route.artist(artistId, song.artist ?? "Исполнитель")) }
                    if let remove { Button("Убрать из плейлиста", systemImage: "minus.circle", role: .destructive) { remove(index) } }
                } label: { if store.downloading == song.id { ProgressView() } else { Image(systemName: "ellipsis").frame(width: 30, height: 42) } }
            }.padding(.vertical, 3)
        }
    }
}
struct HomeView: View {
    @EnvironmentObject private var store: MusicStore
    @State private var moods = false
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 20) {
                    Label("ПЕРСОНАЛЬНЫЙ МИКС", systemImage: "sparkles").font(.caption2).tracking(2)
                    Text("Моя волна").font(.system(size: 34, weight: .bold, design: .rounded))
                    Text("Твоя коллекция. Твоё настроение.").font(.subheadline)
                    HStack {
                        Button { Task { await store.wave(.any) } } label: { Label("Слушать", systemImage: "play.fill") }.buttonStyle(.borderedProminent)
                        Button("Настроить") { moods = true }.buttonStyle(.bordered)
                    }
                }.padding(.vertical, 16).frame(maxWidth: .infinity, alignment: .leading)
            }
            Section {
                NavigationLink(value: Route.favorites) { Label("Любимые треки", systemImage: "heart.fill") }
                NavigationLink(value: Route.offline) { Label("Музыка с собой · \(store.offline.count)", systemImage: "arrow.down.circle.fill") }
            }
            Section("Недавно в библиотеке") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 16) {
                        ForEach(store.albums.prefix(12)) { album in
                            NavigationLink(value: Route.album(album.id, album.name)) { VStack(alignment: .leading, spacing: 8) {
                                Artwork(id: album.coverArt, size: 132)
                                Text(album.name).font(.caption.weight(.semibold)).lineLimit(2).frame(width: 132, alignment: .leading)
                                Text(album.artist ?? "").font(.caption2).foregroundStyle(.secondary).lineLimit(1).frame(width: 132, alignment: .leading)
                            } }.buttonStyle(.plain)
                        }
                    }.padding(.vertical, 8)
                }
            }
            Section("Включи любимое") { SongRows(songs: Array(store.songs.prefix(12))) }
        }.navigationTitle("Поймай свой ритм").refreshable { await store.refresh() }
            .sheet(isPresented: $moods) { MoodSheet() }
    }
}
struct MoodSheet: View {
    @EnvironmentObject private var store: MusicStore
    @Environment(\.dismiss) private var dismiss
    @State private var mood: Mood = .any
    @State private var favoritesOnly = false
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 24) {
                Text("Как ты сейчас?").font(.title2.bold())
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 14) {
                    ForEach(Mood.allCases) { value in
                        Button { mood = value } label: { VStack(spacing: 10) { Image(systemName: value.icon).font(.title2); Text(value.rawValue).font(.caption) }.frame(maxWidth: .infinity).padding(.vertical, 16) }
                            .buttonStyle(.bordered).tint(value == mood ? .accentColor : .secondary)
                    }
                }
                Toggle("Только любимое", isOn: $favoritesOnly)
                Text("Микс собирается из музыки сервера и её жанровых тегов").font(.caption).foregroundStyle(.secondary)
                Spacer(minLength: 0)
                Button { Task { await store.wave(mood, favoritesOnly: favoritesOnly) }; dismiss() } label: { Label("Слушать волну", systemImage: "play.fill").frame(maxWidth: .infinity).padding(10) }.buttonStyle(.borderedProminent)
            }.padding(24).navigationTitle("Моя волна").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } } }
        }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
    }
}
struct LibraryView: View {
    @EnvironmentObject private var store: MusicStore
    @State private var section = "Треки"
    @State private var create = false
    @State private var name = ""
    var body: some View {
        List {
            Picker("Раздел", selection: $section) { ForEach(["Треки", "Альбомы", "Плейлисты"], id: \.self) { Text($0) } }.pickerStyle(.segmented).listRowSeparator(.hidden)
            if section == "Треки" { SongRows(songs: store.songs); Button("Ещё треки") { Task { await store.more() } } }
            if section == "Альбомы" { ForEach(store.albums) { album in NavigationLink(value: Route.album(album.id, album.name)) { Label(album.name, systemImage: "square.stack") } } }
            if section == "Плейлисты" {
                Button { create = true } label: { Label("Создать плейлист", systemImage: "plus.circle.fill") }
                ForEach(store.playlists) { list in NavigationLink(value: Route.playlist(list.id, list.name)) { VStack(alignment: .leading, spacing: 5) { Text(list.name); Text("\(list.songCount ?? 0) треков · Личный").font(.caption).foregroundStyle(.secondary) } } }
            }
        }.navigationTitle("Библиотека").refreshable { await store.refresh() }
            .alert("Новый личный плейлист", isPresented: $create) { TextField("Название", text: $name); Button("Создать") { let title = name; name = ""; Task { await store.playlistMutation { try await $0.createPlaylist(title) } } }; Button("Отмена", role: .cancel) {} }
    }
}
struct SearchView: View {
    @EnvironmentObject private var store: MusicStore
    @FocusState private var focused: Bool
    var body: some View {
        List {
            TextField("", text: $store.query, prompt: Text(focused ? "" : "Трек, исполнитель или жанр")).focused($focused).lineLimit(1)
                .textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.search).onChange(of: store.query) { _, _ in store.searchChanged() }
            if store.searching { ProgressView("Ищем музыку") }
            if let result = store.search {
                if let artists = result.artist, !artists.isEmpty { Section("Исполнители") { ForEach(artists) { artist in NavigationLink(value: Route.artist(artist.id, artist.name)) { Label(artist.name, systemImage: "person.crop.circle") } } } }
                if let albums = result.album, !albums.isEmpty { Section("Альбомы") { ForEach(albums) { album in NavigationLink(value: Route.album(album.id, album.name)) { Text(album.name) } } } }
                Section("Треки") { SongRows(songs: result.song ?? [], source: "Поиск") }
            } else if !store.searching { Section { Text("Найди следующий любимый трек").foregroundStyle(.secondary); Label("Начинай вводить название — поиск начнётся сразу", systemImage: "sparkle.magnifyingglass").font(.subheadline) } }
        }.navigationTitle("Что включим?")
    }
}
struct DetailView: View {
    @EnvironmentObject private var store: MusicStore
    let route: Route
    @State private var songs: [Song] = []
    @State private var albums: [Album] = []
    @State private var playlist: MusicPlaylist?
    @State private var loading = true
    @State private var error: String?
    private var title: String { switch route { case .album(_, let title), .playlist(_, let title), .artist(_, let title): return title; case .favorites: return "Любимые треки"; case .offline: return "Скачанное" } }
    var body: some View {
        List {
            if loading { ProgressView("Загружаем") }
            if let error { Text(error).foregroundStyle(.secondary); Button("Повторить") { Task { await load() } } }
            if !songs.isEmpty { Button { store.play(songs, source: title) } label: { Label("Слушать", systemImage: "play.fill") } }
            if !songs.isEmpty { Button { store.downloadAll(songs) } label: { Label("Скачать список · \(songs.count)", systemImage: "arrow.down.circle") } }
            ForEach(albums) { album in NavigationLink(value: Route.album(album.id, album.name)) { Text(album.name) } }
            SongRows(songs: songs, source: title, remove: playlist == nil ? nil : { index in Task {
                guard let playlist else { return }; await store.playlistMutation { try await $0.remove(index, from: playlist) }; await load()
            } })
            if !loading && error == nil && songs.isEmpty && albums.isEmpty { ContentUnavailableView("Здесь пока тихо", systemImage: "music.note") }
        }.navigationTitle(title).navigationBarTitleDisplayMode(.inline).task(id: route) { await load() }.refreshable { await load() }
    }
    private func load() async {
        loading = true; error = nil; defer { loading = false }
        do {
            switch route {
            case .favorites: songs = store.favorites
            case .offline: songs = store.offline
            case .album(let id, _): if let api = store.api { songs = try await api.album(id) }
            case .artist(let id, _): if let api = store.api { albums = try await api.artist(id) }
            case .playlist(let id, _): if let api = store.api { let value = try await api.playlist(id); try Task.checkCancellation(); playlist = value; songs = value.entry ?? [] }
            }
        } catch { if !Task.isCancelled { self.error = (error as? MusicError)?.localizedDescription ?? "Не удалось загрузить" } }
    }
}
struct PlaylistPicker: View {
    @EnvironmentObject private var store: MusicStore
    @Environment(\.dismiss) private var dismiss
    let song: Song
    @State private var create = false
    @State private var name = ""
    var body: some View {
        NavigationStack {
            List {
                Section { Text(song.title).foregroundStyle(.secondary); Button("Создать плейлист", systemImage: "plus.circle") { create = true } }
                ForEach(store.playlists) { list in Button { Task { await store.playlistMutation { try await $0.add(song, to: list) }; dismiss() } } label: { VStack(alignment: .leading, spacing: 5) { Text(list.name); Text("\(list.songCount ?? 0) треков").font(.caption).foregroundStyle(.secondary) } } }
            }.navigationTitle("В какой плейлист?").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } } }
                .task { await store.reloadPlaylists() }
                .alert("Новый плейлист", isPresented: $create) { TextField("Название", text: $name); Button("Создать") { let title = name; name = ""; Task { await store.playlistMutation { try await $0.createPlaylist(title) } } }; Button("Отмена", role: .cancel) {} }
        }.presentationDragIndicator(.visible)
    }
}
