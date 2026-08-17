import SwiftUI

struct DrawerView: View {
    @Binding var selection: Destination
    @Binding var isOpen: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Destination.allCases) { destination in
                        row(destination)
                    }

                    Divider().padding(.vertical, 8)

                    disabledRow("Downloads", icon: "arrow.down.circle")
                    disabledRow("Categories", icon: "folder")
                    disabledRow("Settings", icon: "gearshape")
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)
            }

            Spacer(minLength: 0)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(.regularMaterial)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: "books.vertical.fill")
                .font(.system(size: 30))
                .foregroundStyle(.tint)
            Text("TachiyomiAZ")
                .font(.title3.weight(.semibold))
            Text("iOS port")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 64)
        .padding(.bottom, 16)
    }

    private func row(_ destination: Destination) -> some View {
        Button {
            selection = destination
            isOpen = false
        } label: {
            HStack(spacing: 16) {
                Image(systemName: destination.icon)
                    .frame(width: 24)
                Text(destination.title)
                Spacer()
            }
            .padding(.vertical, 11)
            .padding(.horizontal, 12)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(selection == destination ? Color.accentColor.opacity(0.15) : .clear)
            )
            .foregroundStyle(selection == destination ? Color.accentColor : Color.primary)
        }
        .buttonStyle(.plain)
    }

    private func disabledRow(_ title: String, icon: String) -> some View {
        HStack(spacing: 16) {
            Image(systemName: icon).frame(width: 24)
            Text(title)
            Spacer()
        }
        .padding(.vertical, 11)
        .padding(.horizontal, 12)
        .foregroundStyle(.tertiary)
    }
}
