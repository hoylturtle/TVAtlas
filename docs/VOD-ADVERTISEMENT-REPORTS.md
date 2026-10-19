# VOD advertisement reports and route feedback

The playback toolbar provides “有广告，换线路”. Confirmation remembers an opaque local fingerprint and switches to remaining candidates, preserving the current position when the replacement is seekable. With no alternative, playback pauses and explains why. All controller open paths filter reports. If all candidates are reported, playback stays closed with an explanation. Clearing all reports requires confirmation.

For aggregated library playback, the fingerprint combines the episode/content identity, provider ID and provider line. Renewed playback addresses therefore retain the report for that episode/provider line, while other episodes and providers remain eligible. Direct URL playback still uses an exact URL fingerprint. Legacy URL reports remain recognized. No whole-provider or shared-CDN blocking is performed.

The playback client inspects at most 64 KiB of an already requested HLS playlist. Explicit CUE-OUT, SCTE35-OUT and Apple interstitial markers are insertion evidence, not proof of advertising. Absence of markers in the inspected prefix does not prove that a video is ad-free or that an advertisement was baked in. Segment discontinuities and advertisement words in URLs do not count as evidence. No extra inspection requests, image recognition, or segment deletion occur. Quality probes do not perform this inspection.

The report confirmation explains observed insertion evidence and the available alternative count. The toolbar also offers manual 30-second seeking for seekable VOD, and temporary, dismissible feedback for fallback. Different versions can have different timelines or share advertisements. This feature cannot universally erase embedded advertisements.

Live connection feedback distinguishes reuse of a saved successful route from connecting another route and attempting fallback. It preserves that feedback during player preparation. Proxy subscriptions are manual-only; periodic live latency probing is disabled. Saved successful automatic routes no longer expire after 30 minutes, while removed/disabled nodes and manual routing remain authoritative.

Validation includes playlist body preservation and one-request-only inspection, non-ad discontinuities, stable report identity across URL renewal, episode/provider isolation, saved-route reuse and failure fallback. Android instrumentation compilation is checked locally; real TV behavior still requires device verification.
