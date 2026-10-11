# Official Jade guest session integration

Observed device failure in v0.1.7: DIRECT rejected the region; selected Hong Kong
proxy returned HTTP 200 for getSession but no token. Checkout and DRM were never
reached. A browser screenshot showed supported_country=true and user.token with
guest_mode=true; an existing browser session does not establish a fresh app session.

Public official frontend inspected on 2026-10-09:
https://www.mytvsuper.com/_next/static/chunks/2687-*.js (module 45848)
The device pairing component has defaults /api/auth/pairDevice/ and
/api/auth/generateProfileAccessToken/. Its anonymous guest branch POSTs device_id,
lang and incognito; drm_id is optional, and the frontend also explicitly falls
back to pairing without it. Profile/account fields are omitted for guest mode.
The tracking ID helper concatenates 16 random decimal digits and epoch seconds.
The nav component rereads getSession after pairing completes.
Public inspection CI: https://github.com/hoylturtle/TVAtlas/actions/runs/37937216299
The inspection utility is manual-only and never calls authentication APIs.

v0.1.8 uses the selected route for all requests:
1. GET /api/auth/getSession/self/?sub=live; reject unsupported regions.
2. When token is absent only, POST /api/auth/pairDevice/ once with a fresh anonymous ID.
3. GET getSession again using the same in-memory CookieJar; accept user.token or root token.
4. Authenticated checkout for free Jade; Media3 performs normal Widevine authorization.

No captured browser tokens, cookies, license payloads or keys are embedded.
Session cookies are cleared after source resolution; token lives in memory for playback.
No HTML-only bootstrap remains: a page GET does not execute guest JavaScript.
Unit tests check pairing method, fields, order, bounded retries and failure handling.
These tests do not prove official-service acceptance or Xiaomi Widevine playback;
a real Hong Kong route/device run is still required to validate those stages.
