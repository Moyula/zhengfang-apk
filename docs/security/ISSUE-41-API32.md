# Issue #41 and API 32 regression follow-up

Baseline: main c91c4dc (after 1.0.91). API remains 3; host contract/SDK is 3.2.4.

Shared-login consent is separate from an ephemeral session grant. Remembered consent binds the account, school, provider, verified publisher and declared scope. Relogin gets a new handle. Revocation cancels active work and invalidates pending confirmations. Developer packages do not inherit publisher trust. Scope reductions currently also request fresh consent as a conservative migration default.

Provider-declared shared operations bind exact origin/path/method/purpose and bounded query/form schemas. Remembered read-state operations cannot use arbitrary bodies or other endpoints. Unknown requests, including a caller-labelled query, require explicit per-request confirmation. Service pages use the same boundary. The Issue does not supply a buildable plugin or authoritative read-state endpoint, so this change does not invent a school whitelist or claim that plugin's real workflow was retested.

Academic data readers receive a persistent plugin-wide data label, propagated through service calls. Network disclosure requires a separately declared/approved recipient; redirects, uploads, browser handoff and remote-page bridge responses use the same guard. Shared cookies/tokens remain host-managed, authentication extraction endpoints are unavailable to consumers, and known secret reflections are blocked. Revoking disclosure preserves the data label. Server-side behavior after authorized disclosure remains outside App control.

The JS service now uses an isolated Android UID; the Application skips private initialization for that UID. Binder calls remain operation-bound. Actual isolated-process/WebView behavior remains a device acceptance item.

Navigation animation snapshots are observable, rapid reversals retain at most two pages, and module entry uses normal alpha composition instead of ModulateAlpha. This addresses stale transparent text layers without using scroll or timer workarounds. Grade view models are cached by report/session; aggregate statistics run on Dispatchers.Default. Glass capture records timing and rejects stale completion callbacks. Full real-time optical effects remain enabled; no reduced-rate/static fallback was introduced.

The 1.0.89-to-1.0.90 tree diff does not modify page-rendering code. Earlier 7ab3ee5 and later 192b69a affect the shared paths. Without the missing screenshot and API 32 GPU acceptance, a precise device-specific root cause and frame-rate improvement are not claimed.

Local verification: 754 JVM tests, 750 passed, 4 optional real-account checks skipped, zero failures/errors. Included tests cover consent rebinding/revocation, retained data disclosure, forged scopes and animation completion/reversal. No device/emulator tests, APK packaging, deployment, CI execution or real school writes were performed. Final independent SDK, portal, runner, Git and artifact evidence is delivered separately in the workspace acceptance report.
