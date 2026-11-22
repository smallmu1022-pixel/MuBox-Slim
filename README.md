<div align="center">

# MuBox Slim for Android

**KunBox 的深度精简版：只留你真正用的协议，体积小三分之一。**

[![最新版本](https://img.shields.io/github/v/release/smallmu1022-pixel/MuBox-Slim?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/smallmu1022-pixel/MuBox-Slim/releases)
[![总下载](https://img.shields.io/github/downloads/smallmu1022-pixel/MuBox-Slim/total?label=%E6%80%BB%E4%B8%8B%E8%BD%BD)](https://github.com/smallmu1022-pixel/MuBox-Slim/releases)
[![Android](https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white)](https://github.com/smallmu1022-pixel/MuBox-Slim/releases)
[![内核](https://img.shields.io/badge/%E5%86%85%E6%A0%B8-sing--box%201.14.4-blue)](https://github.com/SagerNet/sing-box)
[![最近更新](https://img.shields.io/github/last-commit/smallmu1022-pixel/MuBox-Slim?label=%E6%9C%80%E8%BF%91%E6%9B%B4%E6%96%B0)](https://github.com/smallmu1022-pixel/MuBox-Slim/commits/main)

</div>

自编裁剪的 sing-box 内核 + 应用层同步精简，只保留六个协议：**VLESS、Hysteria2、Trojan、AnyTLS、SOCKS、HTTP**。其余协议已彻底移除；订阅中遇到被移除的节点会自动跳过并计数，不影响整表导入。

## 下载

到 [Releases](https://github.com/smallmu1022-pixel/MuBox-Slim/releases) 下载最新 APK；已装用户可在 App 内检查更新直接升级。

| 项目 | 说明 |
|:---|:---|
| 系统 | Android 16（API 36）及以上 |
| 包名 | `com.mubox.slim` |
| 安装包 | 仅 arm64-v8a，约 18.6 MB |

## 协议支持

完整支持以下六种协议（sing-box JSON、Clash YAML、Base64 订阅均可导入）：

| 协议 | 链接格式 | 支持特性 |
|:---|:---|:---|
| **VLESS** | `vless://` | REALITY、Vision（XTLS Flow）、uTLS 指纹、**enc 后量子加密（mlkem768x25519plus）** |
| **Hysteria2** | `hysteria2://` | QUIC、端口跳跃、拥塞控制 |
| **Trojan** | `trojan://` | TLS、Mux、Trojan-Go 兼容 |
| **AnyTLS** | `anytls://` | 通用 TLS 封装、流量混淆 |
| **SOCKS** | `socks5://` / `socks://` | SOCKS5 代理 |
| **HTTP** | `http://` / `https://` | HTTP / HTTPS 代理 |

已移除的协议（原版有、精简版无）：VMess、Shadowsocks、Hysteria（一代）、TUIC、WireGuard、SSH、Naive、Snell、Tor、ShadowTLS、ssmapi。导入含这些节点的订阅时会自动跳过并给出计数，其余节点正常导入。

## 与原版 KunBox 对比

| | KunBox（原作者） | MuBox Slim |
|:---|:---|:---|
| 协议 | 12 种全量 | 只留 6 种：VLESS、Hysteria2、Trojan、AnyTLS、SOCKS、HTTP |
| arm64 安装包 | 约 27.4 MB | 约 18.6 MB（−32%） |
| 架构 | arm64＋32 位双包 | 仅 arm64 单包 |
| 最低系统 | Android 7.0 | Android 16 |
| 内核 | sing-box 官方全量 | 自编裁剪 1.14.4＋自维护补丁（含 VLESS enc 加密支持） |
| 签名 | 原作者签名 | Slim 独立签名（与原版、MuBox 互不覆盖） |

## 保留功能

规则 / 全局模式、规则集在线更新、订阅导入与自动更新、批量延迟测试、连接查看、局域网共享、仅代理模式、Root 透明代理；原 MuBox 的备份文件可直接导入。

## 来源与致谢

MuBox Slim 是 MuBox 的精简版；MuBox 基于 [KunBox](https://github.com/roseforljh/KunBox) v2.24.3（原作者 **roseforljh**）独立维护，在此致谢。代理内核为 [sing-box](https://github.com/SagerNet/sing-box)（SagerNet），本项目使用自编裁剪版 1.14.4。

许可证见 [LICENSE](LICENSE)。
