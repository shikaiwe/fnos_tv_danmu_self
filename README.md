# 飞牛TV 弹幕版 🎯

> 基于飞牛影视 API 的第三方 Android TV 客户端，支持弹幕、自动连播、TV 遥控器优化操作。

> [!IMPORTANT]
> 本项目基于 [**rgcaafe/fnos_tv_danmu**](https://github.com/rgcaafe/fnos_tv_danmu) 二次开发，遵循原项目的 GPLv3 协议。

## 📥 下载

从 [Releases](https://github.com/shikaiwe/fnos_tv_danmu_self/releases) 下载 APK 安装到 Android 电视/手机，或本地构建：

```bash
git clone https://github.com/shikaiwe/fnos_tv_danmu_self.git
cd fnos_tv_danmu_self
./gradlew assembleRelease   # Windows 用 gradlew.bat
# 产物：app/build/outputs/apk/release/FNTV_release_*.apk
```

## ✨ 主要改动（相对原项目）

- **播放内核迁移至 mpv** — 由 ExoPlayer 替换为 libmpv，格式兼容性更好
- **Anime4K 画质增强** — 播放器内可开关的实时超分
- **手势操作** — 亮度、音量、快进快退、长按倍速
- **播放页重构** — 新增侧滑抽屉与独立设置面板
- **字幕优化** — 双语 ASS 处理、外挂/内嵌字体适配

## 📦 功能特点

- 弹幕支持 — 自动匹配剧集弹幕，支持搜索手动选择
- 继续观看 / 剧集自动连播
- TV 遥控器 DPAD 焦点导航优化
- 倍速播放（0.5x ~ 4.0x）、播放比例切换、硬解/软解切换、锁定模式
- 应用内检查更新

## 🛠 技术栈

Java · mpv-android (libmpv 0.5.1) · Anime4K · Retrofit2 + OkHttp3 · 自定义 Canvas 弹幕引擎 · Android 8.0+ (API 26) / Target API 34

## 🔐 登录说明

使用**飞牛影视的账号密码**登录。服务器地址格式 `http://<NAS地址>:<端口>`，默认端口 `5666`。

## ❓ 常见问题

**弹幕无法加载？** 在设置中配置弹幕服务器地址（默认 `http://<NAS>:9321`），确认服务正常运行；匹配不准时可用弹幕面板的"弹幕搜索"手动选集。

**播放卡顿？** 设置中切换解码模式（硬解/软解），检查网络。

**如何更新？** 设置 → 关于 → 检查更新。更新检查与 APK 下载均内置国内镜像加速（ghproxy 系列代理，GitHub 直连兜底），国内网络可直接使用。

## 🚀 发布新版本

CI 会推送 `v*` 标签时自动构建、签名并发布 Release，同时把 `update.json` 提交回 `master` 供应用内检查更新使用（见 `.github/workflows/release.yml`）。

首次需在仓库 **Settings → Secrets → Actions** 配置 4 个 Secret：

| Secret | 说明 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `fntv-release.jks` 的 base64 内容 |
| `SIGNING_STORE_PASSWORD` | 密钥库密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名（`fntv`） |
| `SIGNING_KEY_PASSWORD` | 密钥密码 |

发版步骤：

```bash
# 1. app/build.gradle 中 versionCode +1，按需改 versionName，提交推送
git tag v1.0.1
git push origin v1.0.1   # 自动触发构建发布
```

## 🙏 致谢

- [**rgcaafe/fnos_tv_danmu**](https://github.com/rgcaafe/fnos_tv_danmu) — 原始项目，本项目基于其二次开发
- [**fntv-electron**](https://github.com/QiaoKes/fntv-electron) — 飞牛影视 API 接口逻辑参考
- [**Danmu API**](https://github.com/huangxd-/danmu_api) — 弹幕数据服务
- [**mpv / mpv-android**](https://github.com/mpv-android/mpv-android) · [**Anime4K**](https://github.com/bloc97/Anime4K)

## ⚠️ 声明

本项目为第三方客户端，与飞牛影视官方无关，仅供学习交流，不存储、不提供任何影视资源。

## 📄 License

[GNU General Public License v3.0](LICENSE)。本项目为 [rgcaafe/fnos_tv_danmu](https://github.com/rgcaafe/fnos_tv_danmu) 的衍生作品，依 GPLv3 以相同协议开源。
