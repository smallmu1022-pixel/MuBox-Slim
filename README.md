<div align="center">

# MuBox Slim for Android

> **The slim edition of MuBox: only the protocols you actually use, one third smaller.**
>
> A custom-trimmed sing-box kernel with matching app-layer cuts,
> keeping just six protocols: VLESS, Hysteria2, Trojan, AnyTLS, SOCKS and HTTP.

[Download](#-download-and-installation) • [Protocols](#-protocol-support) • [What was trimmed](#-what-was-trimmed) • [Build](#-build-guide)

**[中文文档](README_CN.md)**

</div>

> Based on MuBox (derived from KunBox v2.24.3, roseforljh/KunBox)

---

## 📥 Download and Installation

Get the latest APK from the [Releases page](https://github.com/smallmu1022-pixel/MuBox-Slim/releases).

> **Note**: Release assets include `arm64-v8a` and `armeabi-v7a` builds; most phones want `arm64-v8a`.

| Item | Requirement |
|:---|:---|
| Android | Android 7.0 (API 24) or later; targets Android 17 (API 37) |
| Package | `com.mubox.slim` (can coexist with MuBox) |
| Size | ~18 MB for the arm64 APK (MuBox is ~27 MB) |

Install by opening the downloaded APK (you may need to allow installs from unknown sources).

---

## 🌐 Protocol Support

**Kept (6):**

| Protocol | Link format | Notes |
|:---|:---|:---|
| **VLESS** | `vless://` | REALITY / Vision / uTLS |
| **Hysteria2** | `hysteria2://` / `hy2://` | QUIC, port hopping |
| **Trojan** | `trojan://` | |
| **AnyTLS** | `anytls://` | |
| **SOCKS** | `socks://` | SOCKS5 |
| **HTTP** | `http://` | HTTP/HTTPS proxy |

**Removed:** VMess, Shadowsocks, Hysteria (v1), TUIC, WireGuard, SSH, Naive, Snell, Tor, ShadowTLS, and more.

Nodes of removed protocols inside a subscription or backup are silently skipped (and counted) instead of failing the whole import; pasting a removed protocol link by hand shows a clear "removed in the slim edition" notice.

---

## ✂️ What Was Trimmed

| | MuBox | MuBox Slim |
|:---|:---|:---|
| arm64 APK | ~27.3 MB | ~18.4 MB (−32%) |
| Kernel libbox.so | 67.5 MB | 45.2 MB (−33%) |
| Kernel dependency packages | 1112 | 739 (−33.5%) |

- The kernel is a self-built trim of sing-box v1.14.2: removed protocols are unregistered and fully out of the dependency graph (Naive's Cronet library, OpenVPN, OpenConnect, WireGuard, the Tailscale module, …), with a CI dependency-graph guard that fails the build if a cut package creeps back
- The app layer drops parsing, editing and config generation for the removed protocols
- Same signing key as MuBox and a fully compatible backup format: a MuBox `mubox_backup` file imports straight into Slim

---

## ✨ Retained Features

- Rule mode / global mode, rule-set hub (online download, auto-update)
- Subscription import and auto-update; clipboard / QR code / file node import
- Batch latency testing, connection inspector
- LAN sharing (SOCKS/HTTP inbound)
- VPN mode / proxy-only mode / root transparent proxy mode

---

## 🔧 Build Guide

Releases are built by GitHub Actions (triggered by a `v*` tag): check out sing-box v1.14.2 → apply the kernel trim patch → verify the dependency graph → build libbox.aar with gomobile → package both ABI APKs onto the release.

Local builds need JDK 17, the Android SDK/NDK and Go 1.26.5; see `.github/workflows/` for details.

---

## 📄 License

See [LICENSE](LICENSE).
