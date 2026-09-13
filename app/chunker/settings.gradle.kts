rootProject.name = "chunker"

// 只构建 cli 模块。上游还包含一个 Electron + React 的图形界面（app/），
// 本工具不用它 —— 界面是自建的网页界面，就在 cli/src/main/resources/web 下。
// 实测：只构建 cli 时产物与包含 app 时字节完全相同（31926310 字节）。
// 这样仓库不用带上 app/ 里的 580 MB 数据副本，克隆下来也能直接构建。
include("cli")
