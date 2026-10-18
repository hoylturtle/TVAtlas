# VOD advertisement reports

The playback toolbar provides “有广告，换线路”. Confirmation records a SHA-256 fingerprint of the exact video URL in local preferences and switches to the remaining candidates, preserving the current position when the replacement is seekable. With no alternative, playback pauses and explains why. All controller open paths filter reported URLs; if all candidates are reported, playback stays closed with an explanation. “清除广告标记” resets the local list.

This is user-directed avoidance, not automatic advertisement detection or video editing. It does not filter a whole provider or shared CDN. Rotating video URLs may require another report. Alternative versions can have different timelines or share the same embedded advertisements. The app does not remove HLS segments based on duration/discontinuity heuristics.

Validation: debug Kotlin compilation and app JVM tests passed. Added an Android instrumentation regression for switching, persistence across controller recreation, all-reported rejection, and clearing reports; execution requires an Android test device.
