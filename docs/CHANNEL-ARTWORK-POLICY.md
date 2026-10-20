# Channel artwork

Channel identity uses bundled logos first, then the subscription logo, then a channel-name label. Existing logo requests obey network consent and share a bounded app-private 128 MiB Coil disk cache.

Programme backgrounds use only a validated HTTP(S) `icon src` supplied inside the current XMLTV programme. The optional `Programme.imageUrl` survives guide serialization and defaults to null for older caches. Channel-level icons never become programme pictures; missing images never inherit the previous programme's image. Unsupported URLs fall back without downloading.

The live idle/preparation view now reads the existing guide state and can show a static programme picture. It does not trigger guide refresh or create a media player. Missing, failed or unavailable cached images use a locally rendered tinted gradient with the channel logo/name. Artwork is decorative, not presented as a current live frame. Existing official guide adapters that do not supply image metadata continue using the fallback.

Only the visible background requests a programme image, bounded to a 960×540 decode target. Network-disabled requests can still use the persistent cache. No video previews, screenshots, full-library programme-image prefetching or additional playback sessions are started.

This is the artwork foundation and its live idle-view integration, not an implementation of any unselected homepage concept.
