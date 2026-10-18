# VK Music Export 0.3.3: diagnostic stage

## Confirmed versus unknown

Owner observation: 0.3.2 (1029), Samsung Galaxy S25 FE / Android 17, white WebView after phone submit. Chrome on the same device succeeds; VPN is used. UA/CH changes did not resolve it. No device log or reproducible account session was available during implementation. **Root cause remains unknown.**

Code review (base d3e9e962): top-frame HTTPS allowlist, no cancel-and-reload in shouldOverrideUrlLoading, non-main frames allowed by that callback. POST requests are not reissued. One gesture-created child window; non-gesture/nested windows denied. Unsupported schemes denied. TLS errors cancelled. Third-party cookies accepted in both windows. Missing HTTP/console/renderer observations have been added. Desktop mode and the removed browser footer explanation remain absent. Parser DOM and login behavior are otherwise retained; renderer death now removes/destroys the affected view and keeps native diagnostic controls available.

## Observation model

Application.attachBaseContext starts the journal before Application.onCreate, Activity and WebView. A launch is an OS process lifetime (not each Activity recreation). Every stored record has run_id (durable increasing launch ordinal plus UUID, independent of wall clock), seq (writer order), UTC timestamp, wall_ms and elapsed_ns (Android elapsedRealtimeNanos). capture_seq records producer order; gaps can signal queue loss. Clock adjustments can change wall time; elapsed clock is authoritative within a boot. Rotation markers have the same envelope. Previous run = most recently retained other process, which may be an instrumentation process during automated tests.

Callbacks: start, progress, commit visible, finish, history, navigation decisions with main-frame/gesture/redirect/method, form resubmission (default do-not-resend), windows, request metadata, HTTP errors, network codes, TLS cancellation, platform renderer responsive/unresponsive/gone. Main and child window IDs persist until destroyed. Application/Activity lifecycle, memory pressure, prior platform exit reasons, app buttons, load/connect/parser state and parser sample counts; coarse default-network capabilities (VPN/Wi-Fi/cellular/validated/blocked, no addresses or SSID) are recorded. Exit reason descriptions/traces are deliberately not read.

The request hook returns null and never replays requests. **This is not a full network trace.** shouldOverrideUrlLoading does not cover POST or app loadUrl. shouldInterceptRequest does not report later redirect hops, javascript/blob requests, all service-worker traffic or successful response status/headers. Request headers exposed to Java are not a guaranteed wire-level snapshot: recorded hints are only the allowlisted observed headers. Empty/absent CH fields do not prove that hints were not sent. No body, HTML, form value, cookie, authorization header or storage content is read for diagnosis. No TLS bypass.

JS error hooks use DOCUMENT_START_SCRIPT when supported, otherwise commit/finish; errors before fallback installation are invisible. Only error type, line/column and sanitized source survive; messages/stacks are omitted. Generic console entries retain severity/category only. No monkeypatching of fetch, XHR, form submission or storage. The probe samples document.readyState, navigator UA and low-entropy UA data, not DOM content. JS UA must equal the configured UA before it is retained.

Separate signals:

* Long load: pageStarted without pageFinished after 30 s, per window. SPA activity after finish is outside this detector.
* JS callback absent after 10 s: evaluateJavascript probe, only issued for a visible window, every 5 s with one outstanding callback. Navigation invalidates pending probes; backgrounding/navigation can also delay responses. Parser callback timeout: 15 s.
* Main thread stall: background watchdog times one outstanding main Handler ping against elapsed time; reports after 5 s while Activity started. This is a heuristic, not a platform ANR verdict. Android renderer callbacks are recorded separately.

## Durability and privacy

One bounded 1024-item queue, single background disk writer, fsync approximately once per second and before export, best-effort 750 ms flush on uncaught Java exception. Android force-stop, SIGKILL, native crash or power loss may discard queued/unsynced records; no claim of lossless crash tracing. Historical exit reason helps distinguish these cases. IO failures and queue loss are explicit counters/records; export health describes the exporting process, while previous logs contain persisted health only.

Five process directories, two <=1.5 MB segments each, <=15.16 MB retained journals including a <=32 KB startup metadata snapshot per run. Old runs and segments are deleted with explicit rotation records; segment records include cumulative evicted bytes. One <=~3.1 MB private shared export file keeps journal + export storage below 20 MB. Export includes all *retained* records of the selected launch, not events already removed by documented rotation. A separate bounded startup_snapshot preserves initial device, version, WebView and UA/brand metadata when event segments rotate; run identity also remains in each event. Invalid interrupted NDJSON lines are skipped and counted when constructing valid JSON, never concatenated as raw JSON.

URL storage is deny-by-default: only fixed approved hostnames and path words; every other segment is `~`. All query values, fragments, userinfo, private playlist keys and unknown hosts/scheme payloads omitted. Arbitrary console/error text and stacks are never stored. Parser codes use a fixed allowlist. Agent data comes from system configuration or an exact-match JS probe, not arbitrary console input. CH brands/versions are allowlisted, model header omitted. Logs are private app data, excluded from backup; sharing grants read permission only to the explicit JSON file.

Clipboard <=240,000 UTF-16 bytes (conservative Binder budget). Over-limit and clipboard failures offer full JSON file; no truncation. Current log copy is one press in native header, including popups, and in Settings. Extra menu exposes previous copy and both file exports. Snapshot export is queued behind pending writes and runs off main. The copied snapshot necessarily predates its own clipboard-success event. The system clipboard may clear later according to OS rules.

## Chrome compatibility: findings and falsifiable next steps

Changing UA and AndroidX UserAgentMetadata changes exposed identity data, not the embedding engine or its capabilities. Chromium documents that WebView is a content embedder without the Chrome layer; it has its own per-app cookie jar/cache. **Guaranteed indistinguishability from Chrome is not established and cannot be promised by UA/CH changes.** This build retains the 0.3.2 compatibility profile and records configured identity, JS-observed low-entropy data and available request hints separately. No further spoofing or patched navigator APIs were added.

After the owner's log, reconstruct event order by seq/elapsed clock and window ID, identify last confirmed event before the blank page, then test one evidenced hypothesis at a time:

| Evidence to look for | Hypothesis and next controlled test | Success criterion |
| --- | --- | --- |
| Blocked top-frame origin/scheme | Required auth navigation rejected; validate observed origin against VK flow, narrowly update policy or implement safe in-app handoff | Same navigation completes and returns to music without broad allowlist expansion |
| Rejected window request / stranded child | Required non-gesture or nested auth window; reproduce on local synthetic page then explicitly support lifecycle | Auth window returns/closes and main music page verifies session |
| Start/request followed by HTTP/network/TLS code | Transport/VPN/provider problem; repeat same build with one changed network condition | Corresponding error disappears, login completes |
| Commit/finish then JS error or no callback | Script/CSP/provider compatibility; compare installed WebView versions and actual hints, test stock identity in a separate controlled build | Form progresses, no prior error signal, parser remains usable |
| Renderer unresponsive/gone vs main-thread stall | Renderer or host freeze; isolate probes/parser/UI hooks, compare provider | Native controls stay responsive, renderer survives same flow |
| Callbacks/probes complete but blank screen | Missing page data/layout or auth state; callbacks alone cannot establish which | A targeted follow-up diagnostic test demonstrates next auth step and parser session verification |

Chrome Custom Tabs use the real browser engine/session and can be shown inside the app UI, but arbitrary DOM extraction and Chrome cookies cannot be transferred into this WebView through supported APIs. Thus Custom Tabs alone do not solve this parser's session access. If WebView auth proves unsupported, viable alternatives require a documented VK callback/token flow with supported music access, or a separate user-mediated import flow; neither is implemented or claimed supported here. Credential/cookie extraction from Chrome is not proposed.

Official references checked 2026-10-18:

* https://developer.android.com/reference/android/webkit/WebViewClient — POST/navigation coverage, redirects/interception, renderer death handling.
* https://developer.android.com/reference/android/webkit/WebView — provider package and JavaScript callbacks.
* https://developer.android.com/reference/android/webkit/WebViewRenderProcessClient — platform responsiveness callbacks.
* https://developer.android.com/reference/androidx/webkit/WebSettingsCompat — UA metadata interaction with overridden UA.
* https://developer.android.com/reference/androidx/webkit/WebViewCompat — document-start scripts.
* https://chromium.googlesource.com/chromium/src/+/main/android_webview/docs/architecture.md — embedding architecture and per-app cookie jar.
* https://developer.android.com/develop/ui/views/layout/webapps/webview — navigation and isolated browser data.

## Validation and limits

Unit: secret-bearing URL cases, free-text omission, arbitrary parser-code omission, truncated-record recovery, prior launch, five-run cap, segment bounds/rotation and ordered envelope. Instrumentation: native clipboard -> parsed JSON, size boundary, FileProvider readback, queue pressure and reported loss. Existing extractor and interface regression checks remain enabled. Release pipeline verifies permanent certificate and installs the actual published 0.3.2 (1029), then upgrades in place preserving a collection fixture. This tests upgrade compatibility on Android 16 AVD, not Samsung/Android 17, VK cookies or successful real login.

Owner reproduction: update without uninstalling → launch → repeat phone login until blank → wait 35 s → copy log or share JSON. If terminated, restart and select previous launch. Real VK login remains **unverified** until that log arrives.
