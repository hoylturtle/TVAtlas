# TVAtlas Player (Android / Tablet / TV)

Early native Android MVP. This lives under TVAtlas temporarily until a dedicated repository is created.

## Scope
- Add arbitrary HTTP(S) M3U playlist URLs.
- Parse UTF-8 M3U channel names and group-title; merge repeated exact names into multiple lines.
- Media3 ExoPlayer playback with remote-control-friendly native controls.
- Configure one HTTP or SOCKS proxy locally and choose DIRECT / PROXY / AUTO per channel.
- AUTO tests direct first, then proxy on player failure, remembering a successful route locally.
- Proxy applies to media playlist, variant playlists, segments and keys requested through the Media3 OkHttp DataSource. No system VPN permission is required.
- Secrets must remain in Android local app storage, never in GitHub.

## Limitations
- First MVP supports HTTP(S) playlists and HLS/HTTP streams; UDP/RTSP/DRM not supported.
- No proxy subscription parser (Clash/Mihomo YAML or VLESS/Trojan) yet. Enter a working HTTP or SOCKS proxy endpoint.
- Per-channel proxy is application-layer routing, not Android-wide VPN.
- Playback success is not evidence of redistribution rights. Use streams you are authorized to access.
- Not yet CI-built or device-tested; APK is not released.

Open `android-player` as the Android Studio project. 
