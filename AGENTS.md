# HyperDragShare Agent Guide

本文是后续自动化代理在本工程内工作的约束。当前源码和
`docs/IMPLEMENTATION.md` 是实现事实来源；`E:\workspace\TaplusContentPreview`
只可作为早期传送门内容抓取思路的参考，不代表可运行基线。

如果文档内部分内容与实际工程不符，需实时修订文档。

## 工程基线

- 工程类型：Android LSPosed 模块，Java/Kotlin 17，minSdk 33，targetSdk 34，compileSdk 37。
- 当前版本：`1.8.1`，`versionCode 75`。
- LSPosed 入口为 `com.leaf.hyperdragshare.codex.MainHook`；默认作用域为系统进程
  `android`，仅在 system_server 内安装无障碍保护门闩 Hook。
- 已删除传送门（`com.miui.contentextension`）捕获运行方案；内容获取只保留无障碍模式。
- LSPosed API：82，入口为 `com.leaf.hyperdragshare.codex.MainHook`。
- 可靠的同手势跟手依赖 root 读取 Linux evdev；这是唯一输入源。
- 当前设备记录：`Xiaomi_Touch_Input_0`、`/dev/input/event7`、原始范围
  `121999 x 265599`、屏幕 `1220 x 2656`。代码必须继续动态探测，不能硬编码这些值。

## 不可破坏的约束

1. LSPosed 作用域只能包含 `android`（system_server），用于
   `AccessibilityServiceEnforcer` 保活门闩。不要加入任何普通应用包名，不要重新引入
   `com.miui.contentextension`，不要强停 `com.miui.contentcatcher`。
2. root 输入源一旦 `isReady()`，它就是一次拖拽的唯一权威输入源。没有其他输入回退通道，
   不要引入 `MotionEvent` 监听或宿主控制码与 root 事件混用。
3. 触发的根因是系统进程内的 `Settings` 变更：无需看门狗 App 写 enabled 残影。所有开关
   状态只由 system_server 的 `ContentObserver` 观察后统一写回。
4. 预览窗和菜单必须保持 `FLAG_NOT_TOUCHABLE`。它们通过
   `WindowManager.updateViewLayout()` 被动跟随坐标，不接管当前手势。原页面仍可能滚动是
   当前旁路观察方案的预期行为。
5. 不要把图片 URI 描述成“只靠临时 URI grant 才能读取”。当前 Provider 会给目标包显式
   grant，但 `openFile()` 故意不再额外调用 `checkUriPermission()`，以兼容会丢失 grant
   信息的系统分享代理和应用内二次转发。读取能力同时依赖不可枚举的 UUID URI；
   `stage/grant/revoke` RPC 仍只允许模块自身 UID。
6. 图片分享 Intent 的 `data`、`EXTRA_STREAM`、`ClipData`、MIME type 和
   `FLAG_GRANT_READ_URI_PERMISSION` 是兼容性组合，不能只保留其中一项。
7. 设置通过模块 UID 的 SharedPreferences 保存，由 `DragShareSettings` 的本地持久化
   写入；不要改成 world-readable 文件。边缘触发值以 dp 保存并在运行时限制为不超过屏幕
   半宽，速度以 dp/s 保存。
8. “拖拽时阻止背景滑动”只在 root 输入源已经送入活动会话后尝试调用隐藏输入 API。所有
   取消/服务销毁路径都必须释放 `InputMonitor`（若 ROM 走 monitor 回退）。
9. 近手方向的物理映射是“左高右低显示在右边，右高左低显示在左边”。菜单第一次展开后
   必须锁定该侧并注销传感器，直到本次手势结束；不要在菜单暂时收起后重新选边。
10. “手指移开时关闭分享菜单”是四种拖拽样式的公共行为。简洁/现代/流光离开菜单与触发带时移除
    线性菜单，环形离开展开面板区域时折叠；同一手势重新进入触发区后都应允许再次展开。
11. 首页激活状态按无障碍来源解释：“无 Root → 服务未启用 → 服务/Root 输入连接中 →
    已激活”。激活状态由 `SettingsScreen` 读取 `AccessibilityRuntimeStatus` 现实状态得出，
    不要伪造注入证明；模块包含的 system_server 保活门闩不影响首页激活逻辑。
12. 内容获取方式只有无障碍，由 `DragShareAccessibilityService` 在模块进程中工作，依赖
    Root evdev。公共运行时不得导入 Xposed API；只允许 `MainHook` 和
    `AccessibilityProtectionHooks` 使用 Xposed 接口，且它们只能在 system_server
    作用域内安装保活门闩。
13. 无障碍节点树只允许在一次长按超时后读取。不得在 `onAccessibilityEvent()` 中持续遍历、
    截图或记录文字；密码节点、锁屏和无障碍覆盖层必须被忽略，图片只截取已选择的节点区域。
14. 未经用户在当前轮明确授权，自动化代理不得自行启动、切换或操控任何设备/桌面应用（包括
    `adb shell am start`、`monkey`、`input` 和 GUI 自动化），也不得自行执行设备或本地截图
    （包括 `screencap`、`adb exec-out screencap`、`PixelCopy`）。可读取用户主动提供的截图；
    此约束不改变模块运行时为现代悬浮 View 使用原生局部背景模糊的实现。

## 代码地图

- `MainHook.java`：限制注入包名 `android`，安装 system_server 保活门闩。
- `AccessibilityProtectionHooks.java`：hook `SystemServer.startOtherServices` 安装保活门闩。
- `AccessibilityServiceEnforcer.java`：system_server 侧事件驱动保活（ContentObserver 观察
  `Settings`，repair/backoff/limiter，签名钉扎）。
- `AccessibilityProtectionProtocol.java`、`AccessibilityProtectionClient.java`：状态广播/恢复
  RPC 协议与客户端。
- `AccessibilityServiceMerge.java`：纯合并工具（enabled 服务串、签名身份、控制请求校验）。
- `AccessibilityHealthProvider.java`：SYSTEM_UID-only 的健康检查 ContentProvider。
- `AccessibilityKeepAlive.java`：模块侧开关与 system_server 后端同步的客户端 Facade。
- `RootTouchSource.java`、`EvdevTouchParser.java`：发现触摸设备、解析 evdev 多点触控帧，稳定生成 DOWN/MOVE/UP/CANCEL。
- `CapturedContent.java`、`OverlayWindowPolicy.java`：来源无关的文字/图片模型和无障碍窗口类型。
- `DragShareController.java`：拖拽会话、悬浮预览、方向菜单、边缘滚动、近手传感器和落点选择；不依赖 Xposed。
- `PortalGlowView.java`：流光样式的全屏不可触摸光效、下拉进度和托盘展开绘制。
- `CircleMenuOverlayView.java`、`CircleMenuGeometry.java`：按 JADX MCP 圆菜单类重建的左右贴边半圆样式。
- `ModernOverlayViews.kt`、`ModernOverlayWindow.java`、`ModernPreviewSizer.java`：现代 Compose 内容、View outline、公开 Window 局部背景模糊和自适应正方形预览。
- `DragShareSettings.java`：设置默认值、范围校验、本地持久化和 Provider 配置 RPC。
- `BackgroundTouchBlocker.java`、`FrameworkBinderTransactionResolver.java`：可选地通过系统手势监视器取消原前台窗口的触摸流；直接 API 被拒绝或被隐藏 API 策略屏蔽时，从当前 ROM 的 framework DEX 动态解析输入 Binder 事务号并以 root 回退，失败时旁路观察。
- `SettingsScreen.kt`：Miuix 设置页（内容开关、目标可见性、批量操作、拖拽排序、外观和触摸参数）。
- `ModuleActivation.java`：Root 探测（`su`）。
- `DragAndDrop.kt`：参考 XiaomiHelper 的 LazyColumn 实时换位、边缘自动滚动和回弹状态。
- `DragShareAccessibilityService.java`、`AccessibilityContentCaptureSource.java`：无障碍生命周期、长按协调和来源隔离。
- `AccessibilityRuntimeStatus.java`：无障碍服务/连接/Root 输入就绪状态，供首页激活卡读取。
- `LongPressGestureDetector.java`、`AccessibilityNodeClassifier.java`、`AccessibilityCandidateSelector.java`：可单测的长按、节点分类和命中优先级。
- `AccessibilityScreenshotter.java`、`RootScreenshotter.java`、`ScreenshotRectMapper.java`：安全的一次性区域截图与 API 28/29 回退。
- `ShareTargetRepository.java`：查询可处理对应 MIME 的导出 Activity，并克隆/着色内置目标的旧矢量图标。
- `BitmapEncoder.java`、`ImageStagingClient.java`、`ShareImageProvider.java`：图片压缩、
  跨 UID 暂存、能力 URI 和授权。
- `LocalImageSaver.java`：把图片首项保存到系统 Pictures，按秒生成文件名。
- `ShareLauncher.java`：构造显式 `ACTION_SEND` 并启动目标 Activity。
- `GestureMath.java`、`ShareUriToken.java`：可单测的纯逻辑。

## 修改流程

修改前先读 `docs/IMPLEMENTATION.md` 及相关源码，不要根据历史聊天或参考工程推断现状。
保持现有小类和直接调用风格，只有共享逻辑确实需要单测时才抽取新工具类。不要把设备专用
事件节点、分辨率或旋转写死。

每次行为变更至少运行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

当前应有 72 个单元测试通过，APK 输出到
`app\build\outputs\apk\debug\app-debug.apk`。交付新的可安装行为时同步递增
`versionCode` 和 `versionName`；纯文档修改不要求增版。

本机发布签名使用 Git 忽略的 `signing/` 目录及同样被忽略的 `local.properties`：

```properties
signingStoreFile=signing/my-release-key.jks
signingInfoFile=signing/qianming.txt
```

签名信息文件按 `ALIAS`、`KEY_PASSWORD`、`KEYSTORE_PASSWORD` 的字段名与值交替保存。
不要把 keystore、签名信息、口令或绝对本机路径提交到仓库。配置存在时，`assembleRelease` 会构建
经 R8 压缩且已签名的 APK，输出为 `app\build\outputs\apk\release\app-release.apk`。
云端 Release 使用 `SIGNING_KEYSTORE_PATH`、`SIGNING_KEYSTORE_PASSWORD`、`SIGNING_KEY_ALIAS` 和
`SIGNING_KEY_PASSWORD` 环境变量配置同一 signingConfig；工作流只发布非 `-unsigned` 的 APK。

实机排查优先看以下日志：

```powershell
adb logcat -c
adb logcat -v time | Select-String "DragShare|AndroidRuntime"
```

正常 root 链路应出现 `DragShare/RootInput: ready`、
`root input is authoritative`、`input source=root`，并最终由 root `ACTION_UP` 输出
`gesture finished`。图片链路应看到 `DragShareProvider` 的 `staged`、`granted` 和
目标 UID 的 `open`。

`probe/` 当前不是 `settings.gradle` 的模块，只可能残留历史验证构建缓存；除非用户明确要求，
不要重新接入或围绕它改造正式实现。
