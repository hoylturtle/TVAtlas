# TVAtlas Player

面向 Android 手机、平板和电视的直播播放器，最低 Android 6.0（API 23）。项目缘起、订阅定制、地区限制与使用边界见 [项目说明](../README.md) 和 [免责声明](../DISCLAIMER.md)。

目标是在合法观看条件下提高稳定性。APP 不提供账号或内容权限，不保证所有频道在所有地区可播。

## 安装与基本使用

1. 从 [GitHub Releases](https://github.com/hoylturtle/TVAtlas/releases) 下载 APK。其他源码、许可证及校验附件无需安装。
2. 在「频道源」管理统一展示的默认频道订阅，或添加自己的 HTTP(S) M3U 地址。
3. 需要时，在「代理」导入 Clash/Mihomo 节点订阅，或配置 HTTP/SOCKS5 代理。频道订阅和代理套餐相互独立。
4. 按地区、频道类型选择频道；播放信息可查看当前线路、具体节点和网络状态。失败时可重试或切换线路。
5. 按实际需求配置网络使用偏好，注意移动数据费用。

电视使用方向键、确认和返回操作；手机／平板支持横屏全屏。节目单依赖有效 EPG 来源及正确的频道匹配。

## 模块结构

| 路径 | 职责 |
| --- | --- |
| `core/` | M3U 解析、规则格式与匹配、回退规划、节目单及共享模型 |
| `app/.../ui/` | Compose 界面、手机／电视适配与遥控焦点 |
| `app/.../playback/` | Media3 播放协调、错误分类、DRM 请求处理 |
| `app/.../source/` | 官方服务入口与动态播放配置适配 |
| `app/.../proxy/` | Mihomo 运行时、HTTP/SOCKS5、客户端与节点测速 |
| `app/.../storage/` | Room、订阅与偏好管理、凭证加密及备份 |
| `app/.../traffic/`、`epg/`、`update/` | 流量、节目单和签名更新 |
| `app/.../cloud/` | 独立预览分支中的网盘分享接入，尚未完成原生播放 |

表中的 `app/.../` 指 `app/src/main/java/com/tvatlas/player/`。

## 路由、代理与播放边界

默认策略为大陆直连，港澳匹配香港节点，台湾、日本、韩国匹配对应地区节点。用户可选择一个自定义规则文件，配合频道或线路手动设置；同地区候选可用于故障回退。默认策略不是地区限制的绕过保证，具体行为以当前生效规则和实际播放日志为准。

支持包含 `proxies` 列表的 Clash/Mihomo YAML，节点参数由固定版本内核校验。订阅中的代理组、规则、外部控制器、TUN 和监听配置不会整体执行；APP 使用自己的路由管理。规则格式示例见 [examples/routes-v1.json](examples/routes-v1.json)。

内核仅为本应用提供本地转发，不创建系统 VPN，外部浏览器也不会自动使用 APP 内节点。内核随签名 APK 更新，不以下载任意可执行文件的方式独立更新。

官方源按服务协议获取会话、清单和必要的 Widevine 授权，不提取密钥。是否可播仍取决于内容权限、地区、设备和官方服务状态。网络或节点回退无法修复所有源格式、授权或设备问题。

## 隐私与诊断

代理订阅和凭证使用 Android Keystore 加密保存。规则导出不包含代理密码或完整节点配置，迁移后需重新导入相关订阅。

调试日志用于定位入口、清单、分片、授权和网络阶段，不自动上传。避免记录完整 URL、Token、Cookie 和正文；分享日志前仍需检查设备、频道和节点名称等信息。不要把真实订阅或账号写入 Issue、测试代码和截图。

流量统计为应用请求数据的统计值，不等于底层全部网络开销；套餐余量以服务商返回及结算为准。

## 构建与验证

需要 **JDK 21、Gradle 8.11.1、Android SDK platform 35 / build-tools 35.0.0**。在本目录执行：

```sh
python3 scripts/fetch_mihomo.py
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Release 输出：`app/build/outputs/apk/release/app-release.apk`。可分发安装包需要固定发布签名；签名准备与配置见 [docs/signing.md](docs/signing.md)。密钥和密码不得提交到仓库。

以下命令仅适用于 `feature/tvatlas-next-vod` 预览分支，稳定分支不包含该构建类型：

```sh
gradle -PtvatlasTestBuildType=preview :core:test :app:testPreviewUnitTest :app:lintPreview :app:assemblePreview
```

预览包名为 `com.tvatlas.player.preview`，与正式版数据隔离，可并行安装。预览包不使用正式版的应用内更新通道。网盘模块支持分享识别、加密保存、部分目录读取、当前已加载文件搜索、视频筛选与目录返回位置保留。分页失败可重试，目录缓存仅在内存中保留并限制大小。不能作为三家网盘均可原生播放的证明。

授权优先采用官方扫码／OAuth。当前未配置百度和 115 经登记的应用身份及回调，夸克适用的授权／分享取流接口仍待核实，因此不展示可用的登录二维码或声称已登录。官方能力参考：[百度网盘开放平台](https://yun.baidu.com/union/openAbility)、[115 开发者平台](https://open.115.com/)。账号授权不等于具有读取任意分享的权限；不自动转存分享，也不要求用户粘贴 Cookie。

JVM 测试与模拟器检查用于验证解析、路由、错误处理和界面行为，不能代替真实网络、遥控器和 Widevine 设备验收，参见 [DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md)。

## 发布与第三方许可

正式更新使用 GitHub Releases 元数据，并校验 APK 大小、SHA-256、包名、版本和签名。下载后仍由用户确认安装；固定签名的更高版本可覆盖更新。开发分支与正式发布分开管理，1.0.0 为稳定版，未完成的网盘模块留在独立预览分支。

Mihomo 使用 GPL-3.0。许可证和对应源码随发布提供，生成对应源码包可执行：

```sh
python3 scripts/fetch_mihomo.py --source
```

不要为了精简 Release 附件删除当前更新依赖的 `release-info.json`，或省略第三方许可证与对应源码分发义务。
