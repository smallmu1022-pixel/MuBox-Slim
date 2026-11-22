<div align="center">

# MuBox Slim for Android

**MuBox 的精简版：只留你真正用的协议，体积小三分之一。**

[![最新版本](https://img.shields.io/github/v/release/smallmu1022-pixel/MuBox-Slim?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/smallmu1022-pixel/MuBox-Slim/releases)
[![总下载](https://img.shields.io/github/downloads/smallmu1022-pixel/MuBox-Slim/total?label=%E6%80%BB%E4%B8%8B%E8%BD%BD)](https://github.com/smallmu1022-pixel/MuBox-Slim/releases)
[![Android](https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white)](https://github.com/smallmu1022-pixel/MuBox-Slim/releases)
[![内核](https://img.shields.io/badge/%E5%86%85%E6%A0%B8-sing--box%201.14.4-blue)](https://github.com/SagerNet/sing-box)
[![最近更新](https://img.shields.io/github/last-commit/smallmu1022-pixel/MuBox-Slim?label=%E6%9C%80%E8%BF%91%E6%9B%B4%E6%96%B0)](https://github.com/smallmu1022-pixel/MuBox-Slim/commits/main)
[![许可证](https://img.shields.io/github/license/smallmu1022-pixel/MuBox-Slim?label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)

</div>

自编裁剪的 sing-box 内核 + 应用层同步精简，只保留六个协议：**VLESS、Hysteria2、Trojan、AnyTLS、SOCKS、HTTP**。其余协议（VMess、Shadowsocks、TUIC、WireGuard 等）已彻底移除；订阅中遇到被移除的节点会自动跳过并计数，不影响整表导入。

## 下载

到 [Releases](https://github.com/smallmu1022-pixel/MuBox-Slim/releases) 下载最新 APK；已装用户可在 App 内检查更新直接升级。

| 项目 | 说明 |
|:---|:---|
| 系统 | Android 16（API 36）及以上 |
| 包名 | `com.mubox.slim`（可与 MuBox 共存） |
| 安装包 | 仅 arm64-v8a，约 18.5 MB |

## 瘦身对比

| | MuBox | MuBox Slim |
|:---|:---|:---|
| arm64 安装包 | 约 27.4 MB | 约 18.5 MB（−32%） |
| 内核 libbox.so | 67.6 MB | 44.8 MB（−34%） |
| 内核依赖包 | 1112 | 743（−33%） |

## 保留功能

规则 / 全局模式、规则集在线更新、订阅导入与自动更新、批量延迟测试、连接查看、局域网共享、仅代理模式、Root 透明代理；MuBox 的备份文件可直接导入。

## 来源与致谢

MuBox Slim 是 MuBox 的精简版；MuBox 基于 [KunBox](https://github.com/roseforljh/KunBox) v2.24.3（原作者 **roseforljh**）独立维护，在此致谢。代理内核为 [sing-box](https://github.com/SagerNet/sing-box)（SagerNet），本项目使用自编裁剪版 1.14.4。

许可证见 [LICENSE](LICENSE)。
