# Home: cinematic discovery with latest films and series

The selected cinematic concept is implemented as a native Compose destination (page 5); existing live/source/proxy/settings destination IDs remain stable. Fresh launches open Home. Returning from non-live pages opens Home; live menus keep their existing playback behavior. Only Home asks to exit the app.

Home presents a static hero, separate latest-film and latest-series shelves, locally recorded continued viewing, and live favourites/recent channels. Titles and posters come from actual configured catalogue APIs. “Latest” means provider update time, not theatrical release date. No video previews are started. A generated original mountain landscape is bundled solely as a neutral hero fallback; it is not attributed to a film.

Two bounded metadata browsers share the existing catalogue cache and its write mutex, while keeping their filter and browse state separate from the film-library page. Each home browser allows at most two concurrent source jobs. Cache-only mode never invokes an API client and can restore film and series shelves after process restart. Network access obeys existing consent rules. Leaving Home, opening details or starting VOD pauses the shelf requests. Explicit refresh remains available.

Movie cards load fresh details and reuse existing episode choice, quality ordering, advertising reports and playback failover. Continued-viewing entries are added only when playback reaches READY, and store at most 12 metadata records and provider item references. Playable addresses and episode lists are not persisted in this home file. Actual playback progress remains in the existing encrypted resume store; the home UI does not invent percentages.

Source lists, proxy nodes and settings remain accessible from main navigation. Home live cards explicitly activate the live-page lifecycle before issuing a play command. Film/series “view all” and search reuse the film-library destination with the requested category/search state.

Validation: JVM regression covers independent cache-only shelves without network, provider-ID continuity and omission of media credentials from continued-viewing metadata. Native tests cover TV/short-landscape title visibility, remote confirmation, shelf scrolling, and TV/phone/short screenshot fixtures. Captures contain synthetic catalogue entries, not real user history.
