# TVAtlas

面向 Android TV / OpenIPTV 的精选 IPTV 订阅。目标不是堆频道数量，而是提供中文分组、自动更新、候选源回退和可诊断的长期维护播放列表。

## 正式订阅

推荐直接使用 GitHub RAW：

`https://raw.githubusercontent.com/hoylturtle/TVAtlas/main/tvatlas.m3u`

> OpenIPTV 请优先使用 RAW 地址。GitHub Pages 曾出现中文编码显示异常，因此不作为正式 M3U 入口。

## 当前覆盖

- 中国大陆：央视、卫视、体育、影视、纪录、新闻
- 香港：综合、新闻、公共电视
- 澳门：综合、新闻、体育
- 台湾：综合、新闻、财经、公共电视
- 日本：精选频道
- 韩国：精选综合与新闻
- 新加坡：精选免费电视

## 自动维护

TVAtlas 每日自动构建，并在代码或配置更新时重新生成播放列表。构建器会：

1. 拉取多个公开上游源。
2. 按频道名称和地区收集候选线路。
3. 并发探测候选线路。
4. 优先选择验证成功且优先级最高的线路。
5. 若 GitHub Runner 因地域限制无法验证，但存在候选源，则保留为 `fallback-unverified`，避免误删在实际地区可播放的频道。
6. 输出 `tvatlas.m3u`。
7. 保存诊断报告为 GitHub Actions artifact，便于定位超时、空响应、HTML、无效 HLS 等问题。

## 健康状态

- **healthy**：GitHub 构建节点已验证候选流。
- **fallback-unverified**：存在候选流，但 GitHub 节点未验证成功。常见原因包括地域限制。
- **missing**：当前没有任何候选流。

因此 `fallback-unverified` 不等于电视端不可播放。

## 版本 1.0

v1.0 的基线目标是：所有配置频道至少拥有一个候选源、自动构建稳定、诊断可追踪，并保持一个可直接导入播放器的正式 RAW 订阅地址。

TVAtlas 只聚合公开可访问的播放地址，不绕过 DRM、付费墙或访问控制。上游频道及流地址可能随时变化，实际可播放性也可能受地区、运营商和播放器影响。
