// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import MusicCore

struct AdminView: View {
    let api: MusicAPI
    @State private var users: [AdminUser] = []
    @State private var selected: AdminUser?
    @State private var uploads: [AdminUpload] = []
    @State private var total = 0
    @State private var busy = false
    @State private var message: String?
    @State private var creating = false
    @State private var deleting: AdminUser?
    @State private var username = ""
    @State private var password = ""
    @State private var operation: Task<Void, Never>?
    var body: some View {
        List {
            if busy { ProgressView("Обновляем") }
            if let message { Text(message).font(.callout).foregroundStyle(.secondary) }
            if let selected {
                Button("К пользователям", systemImage: "chevron.left") { self.selected = nil }.disabled(busy)
                Section("Добавлено · \(total)") {
                    Text("У старых загрузок автор может быть неизвестен.").font(.caption).foregroundStyle(.secondary)
                    ForEach(Array(uploads.enumerated()), id: \.offset) { _, upload in
                        VStack(alignment: .leading) { Text(upload.name); Text(upload.source == "yandex" ? "Перенос из Яндекса" : "Аудиофайл").font(.caption).foregroundStyle(.secondary) }
                    }
                    if uploads.count < total { Button("Показать ещё") { load(selected, offset: uploads.count) }.disabled(busy) }
                    if !busy && uploads.isEmpty { Text("Пока нет известных добавлений") }
                }
            } else {
                Button("Добавить пользователя", systemImage: "person.badge.plus") { creating = true }.disabled(busy)
                ForEach(users) { user in
                    HStack {
                        Button { load(user) } label: { VStack(alignment: .leading, spacing: 4) { Text(user.username); Text("\(user.isAdmin ? "Администратор · " : "")\(user.uploadCount) добавлений").font(.caption).foregroundStyle(.secondary) } }
                        Spacer()
                        if user.canDelete { Button(role: .destructive) { deleting = user } label: { Image(systemName: "person.badge.minus") }.accessibilityLabel("Удалить аккаунт") }
                    }.disabled(busy).buttonStyle(.borderless)
                }
            }
        }.navigationTitle(selected?.username ?? "Пользователи").navigationBarTitleDisplayMode(.inline)
            .task { await refresh() }
            .onDisappear { operation?.cancel(); password = "" }
            .alert("Новый пользователь", isPresented: $creating) {
                TextField("Логин", text: $username).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Пароль · от 12 символов", text: $password)
                Button("Создать") {
                    let username = username.trimmingCharacters(in: .whitespacesAndNewlines); let password = password; self.password = ""
                    perform {
                        let ready = try await api.adminCreate(username, password: password)
                        users = try await api.adminUsers()
                        message = ready ? "Аккаунт создан. Этот пароль подходит для музыки и загрузки" : "Аккаунт создан. Загрузку нужно настроить отдельно на сервере"
                    }
                }.disabled(username.isEmpty || password.count < 12 || password.utf8.count > 72)
                Button("Отмена", role: .cancel) { password = "" }
            } message: { Text("Личные плейлисты и доступ к общей музыке. Пароль передай другу самостоятельно.") }
            .confirmationDialog("Удалить \(deleting?.username ?? "аккаунт")?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button("Удалить аккаунт", role: .destructive) {
                    guard let user = deleting else { return }; deleting = nil
                    perform { try await api.adminDelete(user.id); users = try await api.adminUsers(); message = "Аккаунт удалён. Общая музыка сохранена" }
                }
            } message: { Text("Личные плейлисты и доступ к загрузке будут удалены. Общая музыка останется.") }
    }
    @MainActor private func perform(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }; busy = true; message = nil
        operation = Task {
            defer { busy = false }
            do { try await action() }
            catch { if !Task.isCancelled { message = (error as? MusicError)?.localizedDescription ?? "Не удалось выполнить операцию. Обнови список перед повтором" } }
        }
    }
    @MainActor private func refresh() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do { users = try await api.adminUsers() }
        catch { if !Task.isCancelled { message = "Не удалось загрузить пользователей" } }
    }
    private func load(_ user: AdminUser, offset: Int = 0) {
        perform {
            if offset == 0 { selected = user; uploads = []; total = 0 }
            let result = try await api.adminUploads(user.id, offset: offset)
            uploads += result.items; total = result.total
        }
    }
}
