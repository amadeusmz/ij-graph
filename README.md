# IJ Git Graph

Plugin cho IntelliJ IDEA, vẽ lại graph trong tab Log (VCS Log) theo kiểu extension [Git Graph](https://github.com/mhutchie/vscode-git-graph) của VS Code.

- Mỗi branch là một đường thẳng trên một lane riêng.
- Các đường không chồng chéo, không cắt ngang chấm commit như graph mặc định của IntelliJ.

## Tính năng

- Cột **Graph** mới trong tab Log, đặt ngay trước cột Commit.
- Ẩn graph mặc định được vẽ ở đầu cột Commit.
  - Chữ của cột Commit bắt đầu sát mép trái.
  - Click và hover vào vùng graph cũ không còn tác dụng.
- Làm nổi bật commit HEAD:
  - Chấm vòng rỗng trên cột Graph.
  - Mô tả commit in đậm.
- Nhãn branch/tag trong cột Commit (và trong tooltip của nhãn) lấy màu lane của commit.

## Yêu cầu

- IntelliJ IDEA 2026.2.3 trở lên (build `262.10968` trở lên).
- JDK 25 để build.

## Cài đặt

1. Build file zip (xem [Build](#build)).
2. Mở **Settings** → **Plugins** → ⚙ → **Install Plugin from Disk…**
3. Chọn file `build/distributions/ij-graph-<version>.zip`.
4. Restart IDE.
5. Mở cửa sổ **Git** → tab **Log**.
   - Nếu chưa thấy cột Graph: **Show Options** trên thanh công cụ Log → **Show Columns** → tích **Graph**.

## Build

```bash
./gradlew buildPlugin
```

- Kết quả nằm ở `build/distributions/ij-graph-<version>.zip`.
- Mặc định Gradle tải IntelliJ IDEA theo `platformVersion` trong `gradle.properties`.
- Có thể build với IDE đã cài để khỏi tải:

```bash
./gradlew buildPlugin -PlocalIde="C:/Program Files/JetBrains/IntelliJ IDEA"
```

Các task khác:

| Task | Mô tả |
|---|---|
| `test` | Chạy test của thuật toán chia lane |
| `runIde` | Mở một IDE sandbox đã cài sẵn plugin |
| `verifyPlugin` | Chạy IntelliJ Plugin Verifier |

## Cách hoạt động

| File | Vai trò |
|---|---|
| `GraphLayout.kt` | Thuật toán chia lane.<br />Port từ `determinePath` trong `web/graph.ts` của Git Graph. |
| `GitGraphColumn.kt` | Cột Graph (extension point `vcsLogCustomColumn`) và renderer vẽ graph. |
| `DefaultGraphHider.kt` | Ẩn graph mặc định trong cột Commit.<br />Đổi màu nhãn branch/tag và tooltip của nhãn theo lane. |
| `HeadHighlighter.kt` | In đậm mô tả của commit HEAD (extension point `logHighlighterFactory`). |

## Hạn chế

- Plugin dùng internal API và reflection của VCS Log, có thể hỏng khi IntelliJ đổi code nội bộ.
  - Khi reflection thất bại, plugin chỉ ghi cảnh báo vào log.
  - Lúc đó vùng graph cũ có thể vẫn nhận click, nhãn giữ màu mặc định.
- Popup tooltip của nhãn có thể hiện lệch sang phải khi nhãn đặt bên trái.
  - IntelliJ vẫn tính vị trí popup theo độ rộng của graph cũ.
- Tắt cột Graph giữa chừng thì graph mặc định vẫn ẩn cho tới khi mở lại tab Log.
- Layout được tính trên luồng giao diện (EDT) ở lần vẽ đầu, repo rất lớn có thể khựng.
- Khi log mới tải một phần, đường tới parent chưa tải sẽ dừng tại commit.

## Ghi công

- Thuật toán chia lane, bảng màu và cách làm nổi bật HEAD dựa trên [mhutchie/vscode-git-graph](https://github.com/mhutchie/vscode-git-graph).
