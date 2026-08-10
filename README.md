# HyperDragShare

HyperDragShare 是一个 Android LSPosed 模块，通过无障碍服务识别文字和图片长按，再以 Root evdev 输入在同一手势内提供跟手预览与分享菜单。

## 功能

- 使用 Root evdev 输入，在无障碍长按识别后继续跟随当前手指。
- 支持文字分享、图片分享、保存图片到本地和文本分词。
- 提供简洁、流光、环形和现代四种可配置的分享菜单，以及深浅色外观、目标排序和隐藏设置。
- system_server 保活门闩：服务被关闭后由系统进程事件驱动自动重新启用（可选）。
- 支持可关闭的系统日志或 root 保护的诊断文件导出；调试模式会记录输入节点与运行环境信息。

## 要求

- Android 13 或更高版本。
- 已安装并启用 LSPosed；模块作用域仅选择系统进程 `android`。
- 已启用 HyperDragShare 无障碍服务。
- Root 权限用于读取 Linux evdev，从而可靠地跟随同一次拖拽。

## 安装

1. 从 [Releases](https://github.com/Leaf-lsgtky/HyperDragShare/releases) 下载 APK 并安装。
2. 在 LSPosed 中启用 HyperDragShare，作用域只勾选系统进程。
3. 在系统设置的无障碍页中开启 HyperDragShare，然后打开模块完成设置。

不要将 `com.miui.contentcatcher` 或任何普通应用加入 LSPosed 作用域，也不要强行停止它。

## 构建

项目使用 Java/Kotlin 17。Windows 下执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 发布

向 GitHub 推送形如 `v1.8.0` 的 tag 后，GitHub Actions 会执行测试、Lint，并通过 R8 构建已签名的 Release APK，随后自动创建对应的 GitHub Release 与 APK 附件。

## 许可证

HyperDragShare 以 [GNU General Public License v3.0](LICENSE)（`GPL-3.0-only`）发布。第三方组件的许可详见应用内“开放源代码许可”页面和 `app/src/main/cpp/NOTICE`。

## 说明

实现边界、输入源仲裁和兼容性约束记录在 [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md)。
