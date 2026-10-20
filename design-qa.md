# TVAtlas home design QA

Source visual truth: selected first concept, revised to separate latest films and series:
`/workspace/generated_images/exec-61eecc36-6a2c-4074-9eb0-41adab9bc96d.png` (1672 × 941).

Implementation: native Android Compose HomeScreen, not a website. Target captures: 960 × 540 TV, 840 × 320 short landscape, and 360 × 780 phone. Android emulator instrumentation is the rendering/interaction verification method; browser checks do not apply to this native app.

State: latest movie/series fixture data with local images, no network and no user credentials. Production uses actual catalogue metadata and posters. The bundled neutral landscape is a fallback, not fabricated film artwork.

Pending: emulator screenshots, aligned visual comparison and remote interaction results. Typography, spacing, colours, image quality and copy will be assessed from actual captures. No visual pass is claimed from compilation or JVM tests.

final result: blocked

## First captured comparison

CI 39386593464 passed all checks and produced `home-tv.jpg`, `home-phone.jpg`, and `home-short.jpg`.
- [P2] At 960 × 540 the original hero plus vertically stacked shelf captions pushed series artwork and titles below the first viewport, unlike the revised target's simultaneous film/series discovery.
- Fix in progress: a condensed wide-screen hero, inline shelf metadata, bounded poster height and compact TV-only “view all” targets. Phone touch targets stay 48 dp.
- Regression added: both first film and first series titles must fit in the initial TV viewport. Screenshots explicitly focus the first film card to match the source focus state.
- Re-capture and comparison are required before changing the result to passed.
