# TVAtlas Player v0.1.4

面向 Android 手机、平板与 Android TV 的 IPTV 播放器。Android 6.0（API 23）及以上。

## 使用

1. 在「播放列表」添加名称和 HTTP(S) M3U 地址。
2. 在「路由」选择「添加 Clash/Mihomo 订阅」，填写名称，粘贴订阅 URL（支持长按粘贴和「粘贴订阅地址」按钮）并下载导入。也可手动添加 HTTP / SOCKS5 代理；没有代理时仍可直连播放。
3. 可选从系统文件选择器导入 JSON 规则。参考 `examples/routes-v1.json`。
4. 在「直播」选择频道。长按频道查看线路、修改频道或单线路路由。
5. 「恢复规则」删除手动覆盖；AUTO 使用命中规则的策略和成功历史。
6. 在「设置」启用诊断可查看脱敏地址、路由、命中规则和失败类别。

同名频道合并，URL 去重。更新列表保留频道手动路由、仍存在的线路设置和成功历史；无效或空列表不会覆盖原有数据。

手机竖屏显示频道列表和播放区，横屏进入全屏。平板/电视采用播放器为主的深色布局，左侧频道面板可收起，选台后自动收起；返回、播放器上的 OK / 左键可打开频道面板。电视方向键选择频道，OK 播放，长按 OK 打开线路菜单；播放器区域上下键换台。所有表单与菜单提供可聚焦按钮。

频道分组默认折叠，点击组名展开；搜索直接展示匹配频道，清空搜索恢复原展开状态。线路选择器使用播放列表名称，同一列表中的多条线路按序号区分；代理选择器只显示节点名称，不显示内部 ID 或地址。线路菜单改为紧凑下拉选择，保留自动选线、频道代理与单线路代理。视觉方向参考 [OpenIPTV 的界面说明](https://github.com/shayanline/OpenIPTV/blob/master/docs/design.md)，没有复制其业务实现。

## 构建与验证

需要 JDK 17、Gradle 8.11.1、Android SDK platform 35 / build-tools 35.0.0。打开此目录作为 Android Studio 工程，或执行：

```sh
python3 scripts/fetch_mihomo.py
gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 的 `Android Player` 工作流上传 `TVAtlas-Player-v0.1.4-debug` 构建产物及测试报告。此包由 Android 调试密钥签名，可安装用于验收；正式发行签名需另行配置私有密钥。不同 CI 构建的临时调试签名可能不同，不能保证覆盖安装。升级前记下播放列表地址并导出路由规则；若出现签名不一致，卸载旧包后安装并重新导入，卸载会删除应用私有数据。

Android 模拟器测试验证内置 Mihomo 的配置检查、订阅更新保留节点 ID、失败更新保留旧数据，以及本地 SOCKS 监听到节点的实际转发。

核心 JVM 模块无需 Android 设备，测试 M3U/中文/BOM、JSON 校验、规则优先级、手动覆盖、历史顺序、故障分类、AUTO 与成功门槛。应用 JVM 测试模拟 HTTP 跳转路由、HLS 子请求继承和 SOCKS5 认证/远程 DNS。

## 订阅代理

支持包含 `proxies` 列表的 Clash/Mihomo YAML，包括 SS、SSR、VMess、VLESS、Trojan、Hysteria/Hysteria2、TUIC、AnyTLS、HTTP、SOCKS5、Snell；具体节点参数由内置 Mihomo v1.19.32 校验。订阅中的规则、代理组、外部控制器、TUN 和监听配置不会导入。provider-only 配置、URI/base64 订阅和本地证书文件暂不支持。

导入后，在频道或线路路由中选择节点，也可选择 AUTO。订阅卡片提供手动更新和删除，节点可单独启停。相同订阅 URL 和节点名称保持稳定 ID，因此更新后保留路由引用；删除或改名的节点需要重新选择。最多启用 200 个订阅节点，单次下载上限 2 MB。更新下载或校验失败时保留原配置。

订阅 URL 和节点完整配置使用 Android Keystore 加密保存；导出规则不包含订阅凭证或节点配置。在另一台设备先导入同一订阅，再导入引用节点的规则。内核只为本应用提供经过认证的本地 SOCKS 转发，不创建系统 VPN。

Mihomo 使用 GPL-3.0；许可和声明随应用提供。构建产物同时包含固定版本的对应源码及依赖，使用 `python3 scripts/fetch_mihomo.py --source` 可重新生成源码包。

## 检查更新

「设置 → 检查更新」显示当前版本、新版说明和下载入口。版本按整数 versionCode 比较，只采纳本仓库 Android Player 工作流的成功 push 构建，并要求存在未过期、版本匹配的 APK 产物。版本信息读取该成功构建提交的 `release-info.json`，不会读取尚未验证的分支 HEAD。更新失败可重试，不影响播放；不在后台自动下载或安装。

「打开下载页面」使用系统浏览器打开 GitHub 安装包页面，需 GitHub 登录下载 ZIP 后解压 APK。没有浏览器的电视可以「复制下载链接」，在其他设备下载后传入安装。每次发新版需要同步 `release-info.json`、Gradle versionCode/versionName 和工作流产物名称；CI 校验这些信息一致。临时调试签名不保证覆盖安装，正式无损升级需稳定发行签名。

## 规则语义

`schemaVersion: 1`。支持 channel / channelRegex / group / url / urlContains / domain / domainSuffix / playlistId。一个 match 中多个字段按 AND 匹配。优先级依次为线路手动、频道手动、精确 URL、精确域名、域名后缀/URL 关键词、精确频道、频道正则、分组、播放列表、默认路由。同一级显式 priority 越小越优先；相同 priority 按 JSON 顺序，未指定 priority 排在指定值后。

AUTO 的 `try` 可指定 `["DIRECT", "US", "HK"]`。未指定时为 DIRECT 加启用的代理。仅当历史成功线路和路由仍在当前允许的尝试集合中时才优先使用。强制 DIRECT/PROXY 不会被历史改写。没有命中规则时默认 DIRECT；手动选择 AUTO 可尝试配置的代理。

HLS master、variant、分片和 key 共用播放会话路由；URL/域名规则可覆盖子请求，并对每一次 HTTP 跳转重新判断。单线路或频道的强制路由同样覆盖子请求。

播放器持续 READY/播放推进至少 3 秒，且 READY 后获得新的媒体数据，才写入成功记录。网络/403/451/分片错误尝试下个路由；404/410、清单解析和解码错误跳到下条线路。播放等待超过 20 秒触发切换；用户暂停、停止或离开应用不记录失败。

## 安全与边界

代理用户名和密码通过 Android Keystore AES-GCM 加密，只保存在应用私有目录；关闭备份和设备转移。规则 JSON 不接受 password/token 等未知敏感字段，导出不包含凭证。诊断不显示 URL 路径或查询参数，错误记录仅保存受控类别和 HTTP 状态码。HTTP 与 SOCKS5 使用独立客户端，SOCKS5 认证不使用全局 Authenticator。

v0.1.4 提供 HTTP(S) 直播/HLS、手动列表刷新和规则文件导入/导出。自动后台刷新、二维码、EPG、收藏、远程规则及正式签名发布不在此版本内。UDP/RTP、DRM、付费/授权绕过和系统 VPN 不支持。

CI 编译与模拟网络测试不能替代家庭网络和电视实机验收，参见 `DEVICE_ACCEPTANCE.md`。

### v0.1.5：官方翡翠台实验接入

在「播放列表」点击「添加官方翡翠台（实验）」，再从频道设置为该线路选择服务允许地区的节点。
内置列表只保存官方网页入口；每次播放通过所选线路建立访客会话，解析 checkout 的自动画质 DASH 地址，
由 Android Widevine 请求官方许可证。HTTP 401/403 每个播放路由最多重新建立一次会话，随后按原计划切换。
会话、视频与 DRM 请求使用同一具体线路；官方源不应用分片域名覆盖规则。token/Cookie/签名地址不写入数据库或日志。
仅支持免费 Jade（ott_J_h264/cenc_m）；不提取密钥、不绕过 DRM、不接入付费频道。

这是基于用户提供的网页网络请求制作的原型，不是官方 SDK 集成。checkout 使用网页接口，许可证声明 Android 客户端；
平台可能拒绝跨平台会话，需要支持 Widevine 的真实设备实测。CI 的模拟响应及构建检查不代表官方直播验证成功。
官方 API、认证策略或签名过期可能导致失败；不承诺永久可用。失败时可用官方 App/网页观看。

### v0.1.6：失败诊断修复

所有线路耗尽后仍显示最后错误，诊断开关可查看最后路由；官方 API 错误区分访客会话与播放配置。
截图中的两字节 mlicense POST 可能仅用于服务证书，实际密钥许可证接口仍需确认；不宣称实播已修复。
