// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import AVFoundation
import MediaPlayer
import ImageIO
import MusicCore

@MainActor final class MusicStore: ObservableObject {
    @Published var account: Credentials?
    @Published var busy = false
    @Published var message: String?
    @Published var songs: [Song] = []
    @Published var albums: [Album] = []
    @Published var playlists: [MusicPlaylist] = []
    @Published var favorites: [Song] = []
    @Published var offline: [Song] = []
    @Published var now: Song?
    @Published var playing = false
    @Published var buffering = false
    @Published var position = 0.0
    @Published var duration = 0.0
    @Published var shuffled = false
    @Published var repeated = false
    @Published var source = "Сейчас играет"
    @Published var search: SearchResult?
    @Published var searching = false
    @Published var query = ""
    @Published var importJob: ImportJob?
    @Published var importError: String?
    @Published var importBusy = false
    @Published var downloading: String?
    @Published var uploading = false
    @Published var playlistBusy = false
    @Published var covers: [String: UIImage] = [:]
    var api: MusicAPI?
    private let player = AVPlayer()
    private var observer: Any?
    private var subscriptions: [NSObjectProtocol] = []
    private var transportTargets: [(MPRemoteCommand, Any)] = []
    private var queue = PlaybackQueue()
    private var queueSongs: [Song] = []
    private var playTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?
    private var importTask: Task<Void, Never>?
    @Published private(set) var downloadsPaused = false
    @Published private(set) var pendingOffline: [Song] = []
    private var offlineDrain: Task<Void, Never>?
    private var offlineGate = OfflineListenGate()
    private func offlineKey(_ name: String) -> String? { account.map { "offline." + DeviceStorage.hash($0.server + "|" + $0.username.lowercased()) + "." + name } }
    var autoDownloadListened: Bool {
        get { offlineKey("listened").map { UserDefaults.standard.bool(forKey: $0) } ?? false }
        set { if let key = offlineKey("listened") { objectWillChange.send(); UserDefaults.standard.set(newValue, forKey: key) } }
    }
    var autoDownloadFavorites: Bool {
        get { offlineKey("favorites").map { UserDefaults.standard.bool(forKey: $0) } ?? false }
        set { if let key = offlineKey("favorites") { objectWillChange.send(); UserDefaults.standard.set(newValue, forKey: key) } }
    }
    func downloadAll(_ songs: [Song]) {
        guard let account else { return }
        var known = Set(offline.map(\.id) + pendingOffline.map(\.id))
        if let downloading { known.insert(downloading) }
        let added = songs.filter { known.insert($0.id).inserted }
        pendingOffline.append(contentsOf: added)
        let version = revision
        Task {
            do { try await Task.detached(priority: .utility) { try DeviceStorage.saveQueued(added, account) }.value }
            catch { if version == revision { downloadsPaused = true; report(error) }; return }
            guard version == revision else { return }; beginOfflineDrain()
        }
    }
    private func beginOfflineDrain() {
        guard !downloadsPaused, offlineDrain == nil, !pendingOffline.isEmpty else { return }
        let version = revision
        offlineDrain = Task { [weak self] in
            guard let self else { return }
            defer { if version == self.revision { self.offlineDrain = nil } }
            while !Task.isCancelled && version == self.revision && !self.pendingOffline.isEmpty {
                let song = self.pendingOffline[0]
                let success = await self.performDownload(song)
                guard !Task.isCancelled && version == self.revision else { return }
                if !success { self.downloadsPaused = true; return }
                if let account = self.account { try? await Task.detached(priority: .utility) { try DeviceStorage.finishQueued(song, account) }.value }
                guard !Task.isCancelled && version == self.revision else { return }
                self.pendingOffline.removeFirst()
            }
        }
    }
    private func restoreOfflineQueue() {
        guard let account else { return }; let version = revision
        Task { let songs = await Task.detached(priority: .utility) { DeviceStorage.queued(account) }.value; guard version == revision else { return }; downloadAll(songs) }
    }
    private var downloadTask: Task<Void, Error>?
    private var mixTask: Task<Void, Never>?
    private var mixRevision = 0
    private var revision = 0
    private var waveMood: Mood?
    private var waveFavoritesOnly = false
    private var recordedPlayback = false
    private var coverPending: Set<String> = []
    init() {
        player.automaticallyWaitsToMinimizeStalling = false
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.updatePlayback() }
        }
        subscriptions.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] notification in
            Task { @MainActor in
                guard let self, let item = notification.object as? AVPlayerItem, item === self.player.currentItem else { return }
                self.ended()
            }
        })
        subscriptions.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue
            if began { Task { @MainActor in self?.pause() } }
        })
        subscriptions.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let removed = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
            if removed { Task { @MainActor in self?.pause() } }
        })
        let commands = MPRemoteCommandCenter.shared()
        bind(commands.playCommand) { $0.resume() }; bind(commands.pauseCommand) { $0.pause() }
        bind(commands.togglePlayPauseCommand) { $0.toggle() }; bind(commands.nextTrackCommand) { $0.skip(true) }; bind(commands.previousTrackCommand) { $0.skip(false) }
        let target = commands.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seek(event.positionTime) }; return .success
        }
        transportTargets.append((commands.changePlaybackPositionCommand, target))
        if let saved = DeviceStorage.remembered() {
            account = saved; api = try? MusicAPI(saved); offline = DeviceStorage.offline(saved); restoreOfflineQueue()
            Task { await refresh(); refreshImport() }
        }
    }
    private func bind(_ command: MPRemoteCommand, _ action: @escaping @MainActor (MusicStore) -> Void) {
        let target = command.addTarget { [weak self] _ in Task { @MainActor in if let self { action(self) } }; return .success }
        transportTargets.append((command, target))
    }
    deinit {
        if let observer { player.removeTimeObserver(observer) }
        for item in subscriptions { NotificationCenter.default.removeObserver(item) }
        for (command, target) in transportTargets { command.removeTarget(target) }
        playTask?.cancel(); searchTask?.cancel(); importTask?.cancel(); downloadTask?.cancel(); mixTask?.cancel()
    }
    func report(_ error: Error) {
        if error is CancellationError { return }
        if let error = error as? MusicError { message = error.localizedDescription }
        else { message = "Не удалось выполнить операцию. Проверь соединение" }
    }
    func login(server: String, username: String, password: String, remember: Bool) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            let normalized = try MusicAPI.validateServer(server).absoluteString
            let candidate = Credentials(server: normalized, username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
            let service = try MusicAPI(candidate); try await service.login()
            try DeviceStorage.remember(remember ? candidate : nil)
            reset(); account = candidate; api = service; offline = DeviceStorage.offline(candidate); restoreOfflineQueue()
            await refresh(); refreshImport()
        } catch { report(error) }
    }
    private func reset() {
        invalidateMix()
        revision += 1; offlineDrain?.cancel(); offlineDrain = nil; pendingOffline = []; downloadsPaused = false; offlineGate = OfflineListenGate(); playTask?.cancel(); searchTask?.cancel(); importTask?.cancel(); downloadTask?.cancel(); player.pause(); player.replaceCurrentItem(with: nil)
        if let api { Task { await api.invalidate() } }
        api = nil; account = nil; now = nil; songs = []; albums = []; playlists = []; favorites = []; offline = []; covers = [:]
        coverPending = []; importJob = nil; importError = nil; importBusy = false; source = "Сейчас играет"; waveMood = nil
        queueSongs = []; queue = PlaybackQueue(); search = nil; query = ""; searching = false; shuffled = false; repeated = false
        playing = false; buffering = false; position = 0; duration = 0; MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        downloading = nil; downloadTask = nil; uploading = false; playlistBusy = false
    }
    func logout() { reset(); try? DeviceStorage.remember(nil) }
    func refresh() async {
        guard let api else { return }; let version = revision
        do {
            async let tracks = api.songs(); async let records = api.albums(); async let lists = api.playlists(); async let liked = api.favorites()
            let values = try await (tracks, records, lists, liked)
            guard version == revision else { return }
            songs = values.0; albums = values.1; playlists = values.2; favorites = values.3
        } catch { if version == revision { report(error) } }
    }
    func more() async {
        guard let api else { return }; let version = revision
        do { let next = try await api.songs(offset: songs.count); if version == revision { songs += next.filter { song in !songs.contains { $0.id == song.id } } } }
        catch { if version == revision { report(error) } }
    }
    func searchChanged() {
        searchTask?.cancel(); let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let api else { search = nil; searching = false; return }
        searching = true; let version = revision
        searchTask = Task {
            do { try await Task.sleep(for: .milliseconds(280)); let result = try await api.search(text)
                try Task.checkCancellation(); guard version == revision else { return }; search = result; searching = false
            } catch { if !Task.isCancelled && version == revision { searching = false; report(error) } }
        }
    }
    func play(_ songs: [Song], at index: Int = 0, source: String = "Библиотека") {
        guard songs.indices.contains(index) else { return }
        invalidateMix(); waveMood = nil; setQueue(songs, at: index, source: source)
    }
    private func setQueue(_ songs: [Song], at index: Int = 0, source: String) {
        self.source = source; queueSongs = songs; queue.set(count: songs.count, selected: index); queue.shuffle(shuffled); select(index)
    }
    private func invalidateMix() { mixRevision += 1; mixTask?.cancel(); mixTask = nil }
    private func select(_ index: Int) {
        guard queueSongs.indices.contains(index), let api, let account else { return }
        playTask?.cancel(); player.pause(); player.replaceCurrentItem(with: nil)
        let song = queueSongs[index]; let version = revision
        now = song; position = 0; duration = song.duration ?? 0; buffering = true; playing = false; recordedPlayback = false
        loadCover(song.coverArt)
        if let next = queue.order.dropFirst(queue.position + 1).first { loadCover(queueSongs[next].coverArt) }
        playTask = Task {
            do {
                let local = try DeviceStorage.audio(song, account)
                let url = FileManager.default.fileExists(atPath: local.path) ? local : try await api.mediaURL("stream", id: song.id)
                try Task.checkCancellation(); guard version == revision else { return }
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
                try AVAudioSession.sharedInstance().setActive(true)
                player.replaceCurrentItem(with: AVPlayerItem(url: url)); player.play(); updatePlayback()
            } catch { if !Task.isCancelled && version == revision { buffering = false; report(error) } }
        }
    }
    func toggle() { if playing || buffering { pause() } else { resume() } }
    func resume() { invalidateMix(); guard now != nil else { return }; if player.currentItem == nil, let index = queue.current { select(index); return }; player.play(); updatePlayback() }
    func pause() { invalidateMix(); player.pause(); playTask?.cancel(); buffering = false; updatePlayback() }
    func seek(_ seconds: Double) { player.seek(to: CMTime(seconds: max(0, min(duration, seconds)), preferredTimescale: 600)); position = seconds }
    func skip(_ forward: Bool) {
        invalidateMix()
        if let index = queue.step(forward, wrap: repeated) {
            if forward, waveMood != nil, let api, let now {
                let position = position; let duration = duration
                Task { try? await api.radioEvent(now.id, kind: "skipped", position: position, duration: duration) }
            }
            select(index)
        } else if !forward { seek(0) }
    }
    func shuffle() { invalidateMix(); shuffled.toggle(); queue.shuffle(shuffled) }
    private func ended() {
        if repeated { seek(0); player.play() }
        else if let index = queue.step(true) { select(index) }
        else if let waveMood {
            player.pause(); seek(0); updatePlayback()
            let token = mixRevision; let version = revision
            Task { if token == mixRevision && version == revision { await wave(waveMood, favoritesOnly: waveFavoritesOnly) } }
        }
        else { player.pause(); seek(0); updatePlayback() }
    }
    func wave(_ mood: Mood, favoritesOnly: Bool = false) async {
        guard let api else { return }; invalidateMix()
        let version = revision; let token = mixRevision
        let task = Task {
            do {
                let mix = try await api.radio(mood, favoritesOnly: favoritesOnly, exclude: Array(queueSongs.suffix(100).map(\.id)))
                try Task.checkCancellation()
                guard version == revision && token == mixRevision else { return }
                guard !mix.songs.isEmpty else { message = mix.emptyMessage; return }
                setQueue(mix.songs, source: "Моя волна · \(mood.rawValue)"); waveMood = mood; waveFavoritesOnly = favoritesOnly
            } catch { if !Task.isCancelled && version == revision && token == mixRevision { report(error) } }
        }
        mixTask = task
        defer { if token == mixRevision { mixTask = nil } }
        await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
    }
    private func updatePlayback() {
        playing = player.timeControlStatus == .playing
        buffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        let seconds = player.currentTime().seconds
        if seconds.isFinite { position = max(0, seconds) }
        if let seconds = player.currentItem?.duration.seconds, seconds.isFinite, seconds > 0 { duration = seconds }
        if player.currentItem?.status == .failed { buffering = false; message = "Трек не удалось воспроизвести. Проверь сервер и попробуй другой трек" }
        guard let now else { return }
        if offlineGate.observe(key: (offlineKey("track") ?? "") + now.id, playing: playing, position: position, duration: duration, clock: ProcessInfo.processInfo.systemUptime), autoDownloadListened { downloadAll([now]) }
        if playing && !recordedPlayback && position >= min(30, max(1, duration / 2)), let api {
            recordedPlayback = true
            let position = position; let duration = duration
            Task { try? await api.radioEvent(now.id, kind: "played", position: position, duration: duration) }
        }
        var info: [String: Any] = [MPMediaItemPropertyTitle: now.title, MPMediaItemPropertyArtist: now.artist ?? "",
            MPMediaItemPropertyAlbumTitle: now.album ?? "", MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position, MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0]
        if let id = now.coverArt, let image = covers[id] { info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image } }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
    func loadCover(_ id: String?) {
        guard let id, covers[id] == nil, !coverPending.contains(id), coverPending.count < 6, let api else { return }
        coverPending.insert(id); let version = revision
        Task {
            defer { if version == revision { coverPending.remove(id) } }
            guard let bytes = try? await api.cover(id), version == revision else { return }
            let thumbnail = await Task.detached(priority: .utility) { Self.thumbnail(bytes) }.value
            guard version == revision, let thumbnail else { return }
            if covers.count >= 60, let first = covers.keys.first { covers.removeValue(forKey: first) }
            covers[id] = UIImage(cgImage: thumbnail)
        }
    }
    nonisolated private static func thumbnail(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
              width.doubleValue > 0, height.doubleValue > 0, width.doubleValue <= 8192, height.doubleValue <= 8192,
              width.doubleValue * height.doubleValue <= 16_777_216 else { return nil }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 512,
            kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }
    func favorite(_ song: Song) async {
        guard let api else { return }; let version = revision
        do { let enabled = !favorites.contains { $0.id == song.id }; try await api.favorite(song, enabled: enabled)
            guard version == revision else { return }; if enabled { favorites.append(song); if autoDownloadFavorites { downloadAll([song]) } } else { favorites.removeAll { $0.id == song.id } }
        } catch { if version == revision { report(error) } }
    }
    func playlistMutation(_ operation: (MusicAPI) async throws -> Void) async {
        guard let api else { return }
        guard !playlistBusy else { message = "Дождись завершения изменения плейлиста"; return }
        if importJob?.syncingPlaylists == true { message = "Дождись завершения переноса плейлистов"; return }
        let version = revision; playlistBusy = true
        defer { if version == revision { playlistBusy = false } }
        do { try Task.checkCancellation(); try await operation(api); try Task.checkCancellation()
            guard version == revision else { return }
            let lists = try await api.playlists(); try Task.checkCancellation(); if version == revision { playlists = lists }
        }
        catch { if version == revision { report(error) } }
    }
    func reloadPlaylists() async {
        guard let api else { return }; let version = revision
        do { let lists = try await api.playlists(); try Task.checkCancellation(); if version == revision { playlists = lists } }
        catch { if !Task.isCancelled && version == revision { report(error) } }
    }
    func download(_ song: Song) async { downloadAll([song]) }
    func resumeDownloads() { downloadsPaused = false; downloadAll([]) }
    func downloadNetworkChanged() {
        let previous = offlineDrain; let version = revision
        previous?.cancel(); downloadTask?.cancel()
        Task { await previous?.value; guard version == revision else { return }; resumeDownloads() }
    }
    private func performDownload(_ song: Song) async -> Bool {
        if offline.contains(where: { $0.id == song.id }) { return true }
        guard downloading == nil, let api, let account else { return false }
        let version = revision; downloading = song.id
        let task = Task<Void, Error> {
            let url = try await api.mediaURL("stream", id: song.id)
            try Task.checkCancellation(); guard version == revision else { throw CancellationError() }
            let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".audio")
            defer { try? FileManager.default.removeItem(at: temp) }
            let wifiOnly = UserDefaults.standard.object(forKey: "wifiOnly") as? Bool ?? true
            try await AudioDownload.save(url, to: temp, wifiOnly: wifiOnly)
            try Task.checkCancellation()
            guard version == revision else { return }
            let destination = try DeviceStorage.audio(song, account)
            if !FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.moveItem(at: temp, to: destination) }
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: destination.path)
            try await Task.detached(priority: .utility) { try DeviceStorage.saveDownloaded(song, account) }.value
            try Task.checkCancellation(); guard version == revision else { return }
            offline.removeAll { $0.id == song.id }; offline.append(song)
        }
        downloadTask = task
        defer { if version == revision { downloading = nil; downloadTask = nil } }
        do { try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }; return true }
        catch { if !Task.isCancelled && version == revision { report(error) }; return false }
    }
    func removeDownload(_ song: Song) {
        guard let account else { return }
        do { try FileManager.default.removeItem(at: DeviceStorage.audio(song, account)); offline.removeAll { $0.id == song.id }; try DeviceStorage.removeIndex(song, account) }
        catch { report(error) }
    }
    func refreshImport() {
        guard let api, !importBusy else { return }; importTask?.cancel(); let version = revision
        importTask = Task {
            do {
                repeat {
                    let job = try await api.currentImport(); try Task.checkCancellation()
                    guard version == revision else { return }; importJob = job; importError = nil
                    if job?.active != true { break }; try await Task.sleep(for: .seconds(3))
                } while !Task.isCancelled
            } catch { if !Task.isCancelled && version == revision { importError = (error as? MusicError)?.localizedDescription ?? "Нет связи с сервером переноса" } }
        }
    }
    func submitImport(_ token: String) async {
        guard let api, !importBusy else { return }; let version = revision
        importTask?.cancel(); importBusy = true; defer { if version == revision { importBusy = false } }
        do {
            let job = try await api.submitImport(token, resume: importJob?.resumable == true ? importJob?.id : nil)
            guard version == revision else { return }; importJob = job; importError = nil
            Task { await Task.yield(); if version == revision { refreshImport() } }
        } catch { if version == revision { importError = (error as? MusicError)?.localizedDescription ?? "Не удалось запустить перенос" } }
    }
    func cancelImport() async {
        guard let api, let id = importJob?.id, !importBusy else { return }; let version = revision
        importTask?.cancel(); importBusy = true; defer { if version == revision { importBusy = false } }
        do { let job = try await api.cancelImport(id); if version == revision { importJob = job; Task { await Task.yield(); if version == revision { refreshImport() } } } }
        catch { if version == revision { report(error) } }
    }
    func upload(_ file: URL, server: String, username: String, password: String) async {
        guard !uploading, let api else { return }; uploading = true; let version = revision
        defer { if version == revision { uploading = false } }
        let access = file.startAccessingSecurityScopedResource(); defer { if access { file.stopAccessingSecurityScopedResource() } }
        do { try await api.upload(file, server: server, username: username, password: password)
            if version == revision { message = "Файл на сервере. Он появится после сканирования музыкальной библиотеки" }
        } catch { if version == revision { report(error) } }
    }
}
