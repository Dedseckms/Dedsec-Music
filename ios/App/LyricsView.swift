// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import MusicCore

struct LyricsView: View {
    @EnvironmentObject private var store: MusicStore
    @Environment(\.dismiss) private var dismiss
    @State private var lyrics: SongLyrics?
    @State private var loading = false
    @State private var error: String?
    @State private var retry = 0
    @State private var follow = true
    @State private var offset = 0
    private var identity: String { (store.account?.server ?? "") + "|" + (store.account?.username ?? "") + "|" + (store.now?.id ?? "") }
    private var active: Int? { lyrics?.activeLine(at: Int(store.position * 1000) + offset) }
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text(store.now?.artist ?? "").font(.subheadline).foregroundStyle(.secondary)
                if loading { ProgressView("Ищем текст…") }
                if let error { Text(error).font(.caption).foregroundStyle(.secondary) }
                if let lyrics, !lyrics.lines.isEmpty {
                    if lyrics.timed { HStack {
                        Button("−0,25 с") { offset = max(-10000, offset - 250) }
                        Text("\(offset) мс").font(.caption)
                        Button("+0,25 с") { offset = min(10000, offset + 250) }
                        Spacer()
                        if !follow { Button("К строке") { follow = true } }
                    }.font(.caption) }
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 24) {
                                ForEach(Array(lyrics.lines.enumerated()), id: \.offset) { index, line in
                                    Text(line.text.isEmpty ? "♪" : line.text).font(.title2.weight(active == index ? .bold : .medium))
                                        .foregroundStyle(!lyrics.timed || active == index ? Color.primary : Color.secondary.opacity(0.65))
                                        .frame(maxWidth: .infinity, alignment: .leading).id(index)
                                        .onTapGesture { if let milliseconds = line.startMs { store.seek(Double(max(0, milliseconds - offset)) / 1000); follow = true } }
                                }
                            }.padding(.vertical, 24)
                        }.simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in follow = false })
                            .onChange(of: active) { _, index in if follow, let index { withAnimation { proxy.scrollTo(max(0, index - 1), anchor: .top) } } }
                            .onChange(of: follow) { _, enabled in if enabled, let active { withAnimation { proxy.scrollTo(max(0, active - 1), anchor: .top) } } }
                    }
                    Text("Источник: \(lyrics.source)\(lyrics.timed ? "" : " · без синхронизации")").font(.caption2).foregroundStyle(.secondary)
                } else { Spacer(); Text(lyrics?.status == "instrumental" ? "Инструментальная композиция" : loading ? "Загружаем текст…" : "Текст пока не найден").foregroundStyle(.secondary); Spacer() }
            }.padding(20).navigationTitle(store.now?.title ?? "Текст песни").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarLeading) { Button { retry += 1 } label: { Image(systemName: "arrow.clockwise") } }; ToolbarItem(placement: .topBarTrailing) { Button("Готово") { dismiss() } } }
                .task(id: identity + "|\(retry)") { await load() }
        }
    }
    @MainActor private func load() async {
        guard let account = store.account, let song = store.now else { return }
        lyrics = nil; error = nil; loading = true; follow = true; offset = 0
        defer { if !Task.isCancelled { loading = false } }
        let cache = try? DeviceStorage.folder(account).appendingPathComponent("lyrics/" + DeviceStorage.hash(song.id) + ".json")
        if let cache {
            let saved = await Task.detached(priority: .utility) { () -> SongLyrics? in
                guard let size = try? cache.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 524288,
                      let data = try? Data(contentsOf: cache) else { return nil }; return try? SongLyrics.parse(data)
            }.value
            guard !Task.isCancelled else { return }; lyrics = saved
        }
        guard let api = store.api else { loading = false; return }
        do {
            for _ in 0..<20 {
                let result = try await api.lyrics(song.id); try Task.checkCancellation()
                if result.status != "pending" { lyrics = result }
                if result.status != "pending" {
                    if let cache, ["ready", "instrumental"].contains(result.status) {
                        await Task.detached(priority: .utility) { try? FileManager.default.createDirectory(at: cache.deletingLastPathComponent(), withIntermediateDirectories: true); if let data = try? JSONEncoder().encode(result) { try? data.write(to: cache, options: .atomic) } }.value
                    }
                    return
                }
                try await Task.sleep(for: .milliseconds(1500))
            }
            error = "Поиск ещё выполняется. Обнови текст позже"
        } catch is CancellationError { }
        catch { if !Task.isCancelled { self.error = "Не удалось получить текст. Проверь соединение и версию сервера" } }
    }
}
