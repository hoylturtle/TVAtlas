# Regional live catalogue

`regional-live.m3u` is a small curated repair table shared with the Android
asset of the same name. It affects the built-in subscription only. Hong Kong,
mainland China and user-added subscriptions are excluded from the overlay.

Validation on 2026-10-17: 58 logical channels, 64 URLs across Macau, Taiwan,
Japan, Singapore, United States, United Kingdom, South Korea and Thailand.
Each shipped URL returned an HLS media playlist and recognizable TS/fMP4 media
bytes from the cloud test location. This is a point-in-time transport check,
not proof of continuous playback, programme rights or regional availability.

Sources: https://iptv-org.github.io/iptv/ and broadcaster websites. Thai PBS
was checked against https://www.thaipbs.or.th/live. Macau TDM's current
`/api/v1/common/get-domain` and `/api/v1.0/live/categories` on www.tdm.com.mo
publish the new official domains replacing old live3 URLs. Both globallive
and live5 variants were checked through media segments, not just HTTP status.

Some entries are third-party relays, not broadcaster-operated endpoints.
Do not describe the whole table as official. Do not add YouTube watch pages
as native HLS streams, protected paid streams or expiring account tokens.
Several Taiwanese legacy news relay URLs still return 403 and are not marked
fixed by this table. Japanese domestic coverage remains limited. Do not
substitute an international feed while retaining a domestic channel's name.

Overseas routing: existing HK/MO, TW, JP and KR strategies remain in effect.
SG/US/UK/TH now select matching enabled country nodes; when none exist, public
services can try direct. Explicit user routes and custom rules take precedence.
Manual proxy fallback stays within the chosen node's recognized country.

The bundle is installed without network access on upgrade, preserving other
channels and channel-level settings. Built-in refresh overlays matching repairs
again so a stale remote list cannot restore known replaced URLs. The daily
playlist builder also reads this table. Update both copies together after
revalidation; a static CDN address is not a permanent resolver.
