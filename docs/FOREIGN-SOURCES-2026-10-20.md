# Foreign sources and release-year ordering

Prepared for public release 1.5.6 after user testing; these changes were not included in 1.5.5.

## Live channels

Five new channels are included in the shared regional bundle, both generated playlists and the daily builder configuration:

| Channel | Public stream operator / host | Discovery |
| --- | --- | --- |
| Al Jazeera English | `live-hls-apps-aje-fa.getaj.net` | iptv-org international index |
| DW English | Amagi DW channel | iptv-org international index |
| France 24 Français | `live.france24.com` | iptv-org France/international index |
| France 24 English | `live.france24.com` | iptv-org international index |
| NHK World Japan | `masterpl.hls.nhkworld.jp` | iptv-org international index |

Discovery: https://iptv-org.github.io/iptv/countries/int.m3u . Broadcasters: https://www.france24.com/en/live , https://www.dw.com/ , https://www3.nhk.or.jp/nhkworld/ , https://www.aljazeera.com/live . Only the above selected feeds were added, not the entire unreviewed index.

On 2026-10-20, all five returned HLS playlists and recognizable TS/fMP4 initialization or media bytes. These bounded cloud checks establish transport availability at that time, not uninterrupted television playback or availability from every user's region. No subscriber tokens or paid-channel credentials are bundled.

France, Germany and Qatar are browse groups. This change does not invent new proxy country rules for these countries; existing user routing continues to apply.

## Films and television

A separate native adapter uses https://archive.org/advancedsearch.php and `/metadata/{identifier}`. It searches `feature_films` and `classic_tv`, limited to 1888–1969 and explicit Public Domain Mark / legacy public-domain metadata. The label originates with Archive item metadata, not an independent rights determination.

The source is part of the existing removable unified-subscription flow. Its subscription description is bundled locally; catalogue search and playback metadata use Archive's public APIs through the selected VOD client. It merges into the existing movie/series lists and existing offer deduplication, caching, native MP4 playback and line selection. No website JavaScript or Spider execution is needed.

Listing requests return twenty records per page. Details load MP4 choices lazily. Responses are bounded to 4 MiB; current-release requests return empty immediately instead of contacting this classic catalogue. Missing release years remain unknown; upload dates are never substituted for release years.

Two sample items were checked via metadata and ranged MP4 requests (HTTP 206 and MP4 headers):
- https://archive.org/details/HisGirlFriday1940_201505
- https://archive.org/details/Robin_Hood_68_The_Black_Five

Archive search returned thousands of matching *records*, including duplicate uploads. This is not a count of unique playable titles. Not every record was playback-tested. This catalogue supplies classic films and episodes, not contemporary foreign releases. Pluto TV, Tubi and similar contemporary services were not represented as working native movie APIs without a verified compatible integration.

## Latest means release year

The complete library now defaults to `YEAR`, descending by release year; provider update time only breaks ties within the same year. Explicit “最近更新” remains an optional separate sort.

Home movie and series requests use the current calendar year and `YEAR` sort. The repository checks returned metadata locally as well, so a provider ignoring the year query cannot inject a 2019 title into the current-year shelf. Cached shelf keys include the year. No matching current-year content yields an explicit empty state and “查看全部”, rather than substituting old uploads under a latest label.

## Verification

- Five HLS feeds: manifests and bounded native-media bytes checked.
- Archive catalogue search, two detail records and ranged MP4 transport checked.
- JVM tests cover release-year versus update-date ordering, public-domain label filtering, television identity, encoded MP4 paths and avoiding requests for current-year Archive releases.
- Playlist builder regression tests pass.
- Native television playback and end-to-end playback of every Archive result remain unverified.

Final local validation: 261 JVM tests with zero failures; `:app:lintDebug` and `:app:compileReleaseKotlin` passed. Seven playlist builder tests passed. No public release or main-branch update was performed for these changes.
