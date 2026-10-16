#!/usr/bin/env bash
set -euo pipefail
# emulator-runner executes separate script lines in separate shells. Keep the trap and
# verification in this single process so screenshots are copied after tests, even on failure.
export_previews() {
  mkdir -p app/build/next-ui-previews
  adb pull /sdcard/Pictures/TVAtlas-next/. app/build/next-ui-previews || true
}
trap export_previews EXIT
adb shell wm size 1280x960
adb shell wm density 160
gradle -PtvatlasTestBuildType=preview :core:test :app:testPreviewUnitTest :app:lintPreview :app:assemblePreview :app:connectedPreviewAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tvatlas.player.ui.CloudLibraryPageTest,com.tvatlas.player.ui.PreviewVodPlayerTest,com.tvatlas.player.ui.RemoteContinuityTest,com.tvatlas.player.ui.TvSplitLayoutsTest,com.tvatlas.player.ui.RoutingPageTest,com.tvatlas.player.ui.PreviewIdentityTest,com.tvatlas.player.ui.ArtworkCacheTest --stacktrace
if [[ -n "${GITHUB_RUN_NUMBER:-}" ]]; then
  mv app/build/outputs/apk/preview/app-preview.apk "app/build/outputs/apk/preview/TVAtlas-preview-${GITHUB_RUN_NUMBER}.${GITHUB_RUN_ATTEMPT:-1}-${GITHUB_SHA:0:7}.apk"
fi
