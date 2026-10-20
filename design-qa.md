# TVAtlas native home design QA

## Visual truth and evidence

- Selected concept: first cinematic option, revised for separate latest-film and latest-series shelves. Original: `/workspace/generated_images/exec-61eecc36-6a2c-4074-9eb0-41adab9bc96d.png` (1672 × 941). Retained normalized reference: `docs/home-review/selected-reference.jpg` (960 × 540).
- Native implementation: `docs/home-review/home-tv.jpg` (960 × 540), `home-phone.jpg` (360 × 780), `home-short.jpg` (840 × 320).
- Full simultaneous comparison: `docs/home-review/comparison-tv.jpg`. Focused shelf/title comparison: `docs/home-review/comparison-shelves.jpg`. Initial failing comparison: `docs/home-review/comparison-before.jpg`.
- Density: API 35 Android emulator, 160 dpi / 1 dp per pixel. Reference downsampled to the same TV viewport with integer pixel rounding. No browser, device bezel or OS chrome is included.
- State: first movie card focused on TV and short landscape. Deterministic synthetic catalogue titles and local picture fixtures; no real user history or account. Production reads actual configured catalogue metadata and posters. The repeated landscape in test cards is fixture data, not a fabricated movie feed.

## Comparison history

1. CI 39386593464 passed tests but its captured 960 × 540 layout had a P2 density problem: hero, separate metadata lines and tall cards pushed series pictures/titles out of the initial TV viewport. Captures were opened and compared with the selected source; no visual pass was claimed.
2. Fixed wide-screen hero sizing by reserving the height of two shelves and two-line names. Shelf metadata moved inline; TV-only view-all controls became compact while phone touch targets stayed 48 dp. Poster bounds adapt to available height; phone maintains its earlier landscape-card proportions. Screenshot focus was aligned with the reference.
3. Final CI 39388096535 passed verify/native-smoke and skipped publication. The new regression confirms the first movie and first series names both fit in the initial TV viewport. Final full-screen and focused comparison boards were opened and inspected together with phone and short-landscape captures.

## Required fidelity surfaces

- Typography: native Android sans-serif; clear hero/section/caption hierarchy. Two-line movie names have reserved height. Actual film names replace the concept's fictional calligraphic wordmark; no film logo is invented. All first TV row names and the first series row names fit; phone and short-landscape names remain readable.
- Spacing/layout: cinematic static hero plus horizontal shelves, four visible movie/series cards on the TV fixture, aligned margins and consistent rounded corners. The wider native navigation intentionally retains Sources, Proxy and Settings. The TV hero is more compact than the illustration so both latest shelves fit with usable native controls. Narrow screens scroll vertically instead of clipping a fixed-height poster grid.
- Colour/tokens: dark graphite canvas, slightly separate navigation, restrained purple immediate focus paint, white inactive primary action. The focus block is visible on the first film in matched-state captures. Heavy blur, parallax and delayed focus animation are intentionally absent for television performance.
- Images: generated original neutral landscape fallback, encoded as a 122 KiB raster WebP, and real provider posters in production. No replacement of artwork with drawn geometric approximations. Local fixture images are sharp and correctly cropped. Production source-image subject/quality and availability vary and were not claimed identical to the fictional reference.
- Copy/content: separate latest film/series headings; short provider-update hint. Latest means provider update time, not theatrical release. Search, view all, details and conditional continue-watching/live shelves have real handlers. Progress percentages are not invented when home metadata has no progress snapshot.

## Interaction and runtime evidence

- 256 JVM tests pass, including independent cache-only kind shelves without API calls, preservation of the library filter, history provider-ID continuity and omission of media addresses from home persistence.
- 130 Android instrumentation tests pass, including TV/short-landscape title bounds, remote Enter confirmation, series-shelf scrolling and responsive screenshot capture.
- Release build, signing/static checks and upgrade installation verification pass. Native test logs report no failing tests. Browser console checks do not apply to the existing native Android application.
- Home/category/search requests are consumed once. Restored home scroll does not request an off-screen hero focus. Leaving Home or opening playback pauses catalogue work. Existing real playback/details components are reused rather than replaced by static mock controls.

## Residual device acceptance / polish

- Actual Xiaomi TV GPU timing, unusually large system fonts, service outages, and live third-party poster availability require physical-device acceptance; no such test is claimed here.
- The concept's decorative glow and calligraphy are not replicated as fake film branding. User-controlled startup preferences and richer progress artwork can be considered separately.
- All actionable P0/P1/P2 layout findings from this review are resolved. Remaining differences above are intentional native/product constraints or P3 polish.

final result: passed
