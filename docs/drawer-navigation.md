# 抽屉导航改造说明（feature/drawer-navigation）

## 目标
把原底部导航栏（仪表盘 / 节点 / 配置 / 设置）收进左侧抽屉；设置工具子页按分组平铺进抽屉，设置主页不再作为抽屉顶层入口；页面之间沿用现有 Compose 转场动画。

## 改动文件
- `app/src/main/java/com/mubox/app/ui/components/AppDrawer.kt`：抽屉分组列表、选中态、导航动作；底部 About 动作项。
- `app/src/main/java/com/mubox/app/MainActivity.kt`：外壳由 `Scaffold(bottomBar = ...)` 改为 `ModalNavigationDrawer`；移除底部导航栏渲染；左上角菜单按钮打开抽屉；返回键在抽屉打开时优先关闭抽屉。
- `app/build.gradle.kts`：Debug 预览包追加独立 `applicationIdSuffix`。
- `app/src/debug/res/values/strings.xml`：Debug 预览包应用名覆盖。
- `.github/workflows/android-ci.yml`：PR/分支推送时的单元测试与 Debug APK 构建验证。
- `.github/workflows/release-apk.yml`：标签触发的签名 Release APK 构建与 GitHub Release 上传流程。

## 抽屉分组
### 主页
- 仪表盘（Dashboard）
- 节点（Nodes）
- 配置（Profiles）

### 设置工具
- 连接与启动（ConnectionSettings）
- 路由设置（RoutingSettings）
- DNS 设置（DnsSettings）
- 流量接管设置（TunSettings）
- 规则集管理（RuleSets）
- 自定义规则（CustomRules）
- 域名分流（DomainRules）
- 应用分流（AppRouting）
- 规则集中心（RuleSetHub）
- 网络诊断（Diagnostics）
- 运行日志（Logs）
- 流量统计（TrafficStats）
- 连接信息（ConnectionInfo）

### 抽屉底部
- 关于（About）：不对应独立路由；点击后关闭抽屉并显示现有 About 对话框。

## 交互与流畅性
- 点击抽屉项：先关闭抽屉，再执行导航；抽屉开合与页面转场不叠加，避免位移和动画互相干扰。
- 顶层主页沿用原 `popUpTo(Dashboard) { saveState = true } + restoreState` 的标签切换逻辑；子页直接 `navigate(route)`，`launchSingleTop = true`。
- 现有 NavHost 转场参数（时长 450ms、`FastOutSlowInEasing`、横向滑动 + 淡入淡出）保持不变，抽屉改造不修改页面转场。
- 列表使用稳定路由作为条目标识；抽屉滚动与页面内容滚动分离。

## 共存版调试 APK（与原版同时安装）
> 云端环境没有 Android SDK / NDK / Gradle 构建链，无法直接产出 APK；以下配置由 CI 和本地 Android Studio 使用。

### 1. 包名与应用名隔离
`debug` 构建类型已追加 `applicationIdSuffix = ".preview"`；预览包名为 `com.mubox.slim.preview`，可与正式包同时安装。

应用名由调试资源集覆盖：`app/src/debug/res/values/strings.xml` 中的 `app_name` 为 `MuBox Slim Preview`。这里不使用 `resValue`，避免与 `main` 源集中已有的 `app_name` 字符串资源重复。

- 基础 applicationId 为 `com.mubox.slim`，追加后缀后预览版为 `com.mubox.slim.preview`。
- `AndroidManifest.xml` 中 FileProvider、WorkManager 的 authorities 使用 `${applicationId}` 占位符，会随包名自动隔离，无需手改。
- `:bg` 进程按包名隔离，两个版本互不干扰。

### 2. 本地构建命令
```bash
./gradlew assembleDebug
```
产物位于：
```
app/build/outputs/apk/debug/
```
开启 ABI 分包时会输出 `app-arm64-v8a-debug.apk`、`app-armeabi-v7a-debug.apk` 等分架构 APK。

### 3. 构建前置条件
- 按仓库 README 配置 Android SDK（compileSdk 37）与 NDK（默认 `29.0.14206865`）。
- 本地构建需要 `app/libs/libbox.aar`；CI 与 Release 工作流会按固定 `sing-box v1.14.4` 源码通过 gomobile 生成该 AAR。
- Debug 包使用调试签名即可安装验证；Release 包使用仓库现有 keystore 与 CI 密钥。

## CI 验证与 Release 自动发版
`.github/workflows/android-ci.yml` 在 PR、`main` 或 `feature/*` 分支推送时触发：

1. 恢复仓库内置二进制资源并校验哈希。
2. 安装 JDK 17、Android NDK r28c、Go 与 Gradle。
3. 检出固定版本 sing-box v1.14.4，并通过 gomobile 构建 `libbox.aar`。
4. 运行单元测试，再构建 arm64-v8a Debug APK。
5. Debug APK 作为 Actions artifact 上传，保留 14 天。

`.github/workflows/release-apk.yml` 在推送语义化版本标签（如 `v1.0.4`）或 `latest` 标签时触发：

1. 恢复仓库内置二进制资源并校验哈希。
2. 安装 JDK 17、Android NDK r28c、Go 与 Gradle。
3. 检出固定版本 sing-box v1.14.4，并通过 gomobile 构建 `libbox.aar`。
4. 使用仓库现有密钥解码 Release keystore。
5. 运行单元测试，再构建 arm64-v8a Release APK。
6. 校验包名、APK 完整性与签名证书指纹。
7. 通过 GitHub Release 动作把 APK 上传到对应 Release。

现有工作流边界保持不变：`codeql.yml` 只做安全扫描；`vc51-candidate.yml` 只生成旧候选分支的 review artifact，不发布 Release；`palmagine-build.yml` 与 Android APK 无关。PR 的 CI 通过只能证明 Gradle 编译、单元测试和 Debug APK 构建成功；抽屉界面与跳转仍需在安装 Debug 产物后人工确认。合并到 `main` 后再打标签触发正式发版。

Release 工作流依赖现有仓库密钥：`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

## 复核事项
- CI 通过表示 Gradle 编译、单元测试和 Debug APK 构建成功；抽屉界面、选中态和页面跳转仍需安装 Debug 产物后人工确认。
- `主页`、`仪表盘` 目前为中文字面量；如需英文界面，后续在 `strings.xml` 补对应字符串并替换。
- 各子页内的返回按钮行为未改动；抽屉仅负责顶层与设置子页入口导航。
