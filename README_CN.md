<div align="center">

# MuBox Slim for Android

> **MuBox 的精简版：只留常用协议，把体积砍掉三分之一。**
>
> 自编裁剪 sing-box 内核 + 应用层同步精简，
> 只保留 VLESS、Hysteria2、Trojan、AnyTLS、SOCKS、HTTP 六种协议。

[下载安装](#-下载安装) • [协议支持](#-协议支持) • [精简说明](#-精简说明) • [构建指南](#-构建指南)

**[English](README.md)**

</div>

> **Fork 说明**：本项目是 MuBox 的精简版；MuBox Fork 自 [KunBox](https://github.com/roseforljh/KunBox) v2.24.3（原作者 **roseforljh**），在此向原作者表示感谢。本项目在原作基础上独立维护，精简改动见下文。

---

## 📥 下载安装

前往 [Releases 页面](https://github.com/smallmu1022-pixel/MuBox-Slim/releases) 下载最新版本的 APK 文件。

> **提示**：发布产物包含 `arm64-v8a` 与 `armeabi-v7a` 两个架构版本，一般手机选 `arm64-v8a` 即可。

| 项目 | 要求 |
|:---|:---|
| Android 版本 | Android 7.0（API 24）及以上，已适配 Android 17（API 37） |
| 包名 | `com.mubox.slim`（与 MuBox 正式版可共存安装） |
| 体积 | arm64 版 APK 约 18 MB（MuBox 正式版约 27 MB） |

安装方式：下载 APK 后直接点击安装（需要允许安装未知来源应用）。

---

## 🌐 协议支持

**保留的 6 种协议：**

| 协议 | 链接格式 | 说明 |
|:---|:---|:---|
| **VLESS** | `vless://` | 含 REALITY / Vision / uTLS |
| **Hysteria2** | `hysteria2://` / `hy2://` | QUIC、端口跳跃 |
| **Trojan** | `trojan://` | |
| **AnyTLS** | `anytls://` | |
| **SOCKS** | `socks://` | SOCKS5 |
| **HTTP** | `http://` | HTTP/HTTPS 代理 |

**已移除的协议：** VMess、Shadowsocks、Hysteria（一代）、TUIC、WireGuard、SSH、Naive、Snell、Tor、ShadowTLS 等。

订阅或备份里混有被移除协议的节点时，会自动静默跳过并计数，不影响其余节点导入；手动粘贴被移除协议的链接会明确提示「该协议已在瘦身版中移除」。

---

## ✂️ 精简说明

| 项目 | MuBox 正式版 | MuBox Slim |
|:---|:---|:---|
| arm64 APK | 约 27.3 MB | 约 18.4 MB（−32%） |
| 内核 libbox.so | 67.5 MB | 45.2 MB（−33%） |
| 内核依赖包数 | 1112 | 739（−33.5%） |

- 内核基于 sing-box v1.14.2 自编裁剪：摘除被移除协议的注册与依赖（Naive 的 Cronet 库、OpenVPN、OpenConnect、WireGuard、Tailscale 模块等整块脱离依赖图），构建时有依赖图守卫防止被砍包回潮
- 应用层同步删除被移除协议的解析、编辑与配置生成代码
- 与 MuBox 正式版**共用同一把签名密钥**，备份格式完全兼容：MuBox 的 `mubox_backup` 备份可直接导入 Slim

---

## ✨ 功能保留

- 规则模式 / 全局模式、规则集中心（在线下载、自动更新）
- 订阅导入与自动更新、剪贴板 / 扫码 / 文件导入节点
- 批量延迟测试、连接信息查看
- 局域网共享（SOCKS/HTTP 入站对外提供）
- VPN 模式 / 仅代理模式 / Root 透明代理模式

---

## 🔧 构建指南

发布构建由 GitHub Actions 完成（打 `v*` 标签触发）：检出 sing-box v1.14.2 → 应用内核裁剪补丁 → 依赖图守卫校验 → gomobile 编译 libbox.aar → 打包双架构 APK 并附到正式发布。

本地构建需要 JDK 17、Android SDK/NDK 与 Go 1.26.5，细节见 `.github/workflows/`。

---

## 🙏 来源与致谢

- **应用基础**：[KunBox](https://github.com/roseforljh/KunBox) v2.24.3，原作者 **roseforljh**。MuBox 在其基础上换牌并独立维护，MuBox Slim 是 MuBox 的精简版。本项目的功能与界面基础来自原作者的工作，精简与后续改动为本项目所加。
- **代理内核**：[sing-box](https://github.com/SagerNet/sing-box)（SagerNet），本项目使用 v1.14.2 自编裁剪版。

---

## 📄 许可证

见 [LICENSE](LICENSE)。
