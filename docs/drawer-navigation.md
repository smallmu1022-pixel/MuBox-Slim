# 抽屉导航改造说明（feature/drawer-navigation）

## 目标
把原底部导航栏（仪表盘 / 节点 / 配置 / 设置）全部收进左侧抽屉；设置主页及其全部子页入口按分组平铺进抽屉，页面之间沿用现有 Compose 转场动画。

## 改动文件
- `app/src/main/java/com/mubox/app/ui/components/AppDrawer.kt`（新增）：抽屉分组列表、选中态、导航动作。
- `app/src/main/java/com/mubox/app/MainActivity.kt`：外壳由 `Scaffold(bottomBar = ...)` 改为 `ModalNavigationDrawer`；移除底部导航栏渲染；左上角菜单按钮打开抽屉；返回键在抽屉打开时优先关闭抽屉。

## 抽屉分组
### 主页
- 仪表盘（Dashboard）
- 节点（Nodes）
- 配置（Profiles）

### 设置
- 设置主页（Settings）
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

## 交互与流畅性
- 点击抽屉项：先关闭抽屉，再执行导航；抽屉开合与页面转场不叠加，避免位移和动画互相干扰。
- 顶层页面（主页组 + 设置主页）沿用原 `popUpTo(Dashboard) { saveState = true } + restoreState` 的标签切换逻辑；子页直接 `navigate(route)`，`launchSingleTop = true`。
- 现有 NavHost 转场参数（时长 450ms、`FastOutSlowInEasing`、横向滑动 + 淡入淡出）保持不变，抽屉改造不修改页面转场。
- 列表使用稳定路由作为条目标识；抽屉滚动与页面内容滚动分离。

## 共存版 APK（与原版同时安装）
> 云端环境没有 Android SDK / NDK / Gradle 构建链，无法直接产出 APK；以下配置在本地 Android Studio 接入后构建。

### 1. 包名与应用名隔离
在 `app/build.gradle.kts` 的 `debug` 构建类型中追加：

```kotlin
debug {
    applicationIdSuffix = ".preview"
    resValue("string", "app_name", "\"MuBox Slim Preview\"")
}
```

- 基础 applicationId 为 `com.mubox.slim`，追加后缀后预览版为 `com.mubox.slim.preview`，可与原版并存安装。
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
- `app/libs/libbox.aar` 内核 AAR 保持在位。
- Release 共存包如需独立签名，在 `signing.properties` 或环境变量中配置独立 keystore；debug 包使用调试签名即可安装验证。

## 待本地复核
- Android Studio 同步后确认无编译告警/错误（云端无法执行 Gradle 编译）。
- `主页`、`仪表盘` 目前为中文字面量；如需英文界面，后续在 `strings.xml` 补对应字符串并替换。
- 各子页内的返回按钮行为未改动；抽屉仅负责顶层与设置子页入口导航。
