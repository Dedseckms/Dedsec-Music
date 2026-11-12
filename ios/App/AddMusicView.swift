// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import UniformTypeIdentifiers
import MusicCore

struct AddMusicView: View {
    @EnvironmentObject private var store: MusicStore
    @State private var token = ""
    @State private var uploadServer = ""
    @State private var uploadUser = ""
    @State private var uploadPassword = ""
    @State private var pick = false
    var body: some View {
        Form {
            Section {
                Label("Из Яндекс Музыки", systemImage: "waveform.badge.plus").font(.headline)
                Text("Перенос выполняется на сервере. Уже скачанные треки не дублируются, а твои плейлисты восстанавливаются отдельно.").font(.subheadline).foregroundStyle(.secondary)
                if store.importJob?.active != true {
                    SecureField("Токен Яндекс Музыки", text: $token).textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(nil)
                    Link(destination: MusicAPI.tokenGuide) { Label("Как получить токен", systemImage: "questionmark.circle") }.buttonStyle(.bordered)
                    Button { let value = token.trimmingCharacters(in: .whitespacesAndNewlines); token = ""; Task { await store.submitImport(value) } } label: { Label(store.importJob?.resumable == true ? "Продолжить перенос" : "Перенести коллекцию", systemImage: "icloud.and.arrow.down") }
                        .disabled(token.isEmpty || store.importBusy)
                    Text("Токен отправляется только твоему серверу и не сохраняется в приложении.").font(.caption2).foregroundStyle(.secondary)
                }
                if store.importBusy { ProgressView() }
                if let error = store.importError { Text(error).font(.caption).foregroundStyle(.red) }
                if let job = store.importJob {
                    Text(job.label).font(.subheadline.bold())
                    if let progress = job.progress { ProgressView(value: progress); Text("\(Int(progress * 100))% · \(job.processed) из \(job.total)").font(.caption.monospacedDigit()) }
                    else if job.active { ProgressView(); Text("Найдено: \(job.discovered)").font(.caption) }
                    Text("Скачано: \(job.downloaded) · Уже на сервере: \(job.skipped)\nОшибки: \(job.failed) · Недоступно: \(job.unavailable) · Исключено: \(job.excluded)").font(.caption)
                    if let count = job.playlistCount, count > 0 { Text("Плейлисты: \(job.completedPlaylistCount ?? 0) из \(count)").font(.caption) }
                    if let current = job.current { Text("\(current.artist) — \(current.title)").font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                    if job.syncingPlaylists { Text("Не редактируй импортируемые списки до завершения").font(.caption) }
                    if job.active {
                        Text("Можно закрыть приложение — сервер продолжит работу").font(.caption2).foregroundStyle(.secondary)
                        Button("Остановить перенос", role: .destructive) { Task { await store.cancelImport() } }.disabled(store.importBusy || job.state == "cancelling")
                    }
                }
                Button("Обновить статус", systemImage: "arrow.clockwise") { store.refreshImport() }.disabled(store.importBusy)
            }
            Section("Загрузить свой файл") {
                TextField("HTTPS-адрес загрузчика", text: $uploadServer).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Логин загрузки", text: $uploadUser).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Пароль загрузки", text: $uploadPassword)
                Text("До 100 МБ и до 20 минут. Сервер проверяет фактическое аудио; пароль загрузки вводится отдельно.").font(.caption).foregroundStyle(.secondary)
                Button { pick = true } label: { Label("Выбрать аудиофайл", systemImage: "doc.badge.plus") }.disabled(store.uploading || uploadPassword.isEmpty || uploadUser.isEmpty || uploadServer.isEmpty)
                if store.uploading { ProgressView("Загружаем на сервер") }
            }
        }.navigationTitle("Добавить музыку").task {
            if uploadUser.isEmpty { uploadUser = store.account?.username ?? "" }
            if uploadServer.isEmpty {
                let music = store.account?.server ?? MusicAPI.defaultServer
                uploadServer = music.trimmingCharacters(in: CharacterSet(charactersIn: "/")) == MusicAPI.defaultServer ? MusicAPI.defaultUploadServer : music
            }
            store.refreshImport()
        }
        .fileImporter(isPresented: $pick, allowedContentTypes: [.audio], allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                if let file = urls.first { let password = uploadPassword; uploadPassword = ""; Task { await store.upload(file, server: uploadServer, username: uploadUser, password: password) } }
            case .failure: store.message = "Не удалось выбрать файл"
            }
        }
    }
}
