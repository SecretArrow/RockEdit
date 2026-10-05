# v0.15.0 — Bracket Pair Colors, Cloud OAuth, USB OTG

Completeness documentation per the project's defensive-programming template:
scenario mapping → code locations → branch tests → explicit assumptions.

## 1. Bracket pair colorization (backlog item 4, second half)

| Scenario | Handling (code) | Branch test |
|---|---|---|
| Empty document | `colorize("")` → empty list | `empty text returns empty list` |
| Oversized document (> 150k chars) | early return, same cap as highlighter | `oversized text ...`, `non-positive maxChars guard` |
| Brackets inside string / char / line comment / block comment | scanner state machine skips them | 4 dedicated tests |
| Escaped quote inside string (`\"`) | escape skips next char | `escaped quote inside string does not close it` |
| Unterminated string | state resets at newline (documented fail-safe) | `unterminated string resets at newline fail-safe` |
| Unbalanced closers / openers | depth clamps to ≥ 1 for coloring; never negative | 2 dedicated tests |
| CRLF input | `\r` is an ordinary char; offsets stay aligned | `crlf text keeps offsets aligned` |
| Any unexpected internal error | `catch` returns the partial result — editing never breaks | scanner wrapped in try/catch |
| Setting off (Settings → editor) | spans not applied; toggling re-highlights | `setBracketColors` guard |
| Depth beyond palette | `colorIndexFor` cycles; invalid depth/palette clamped | 2 dedicated tests |

Assumptions: angle brackets are not colored (ambiguous); the scanner follows
C quote/comment conventions, so `#` comments (Python/YAML) are treated as code —
visual only, harmless.

## 2. Cloud OAuth (Google Drive / Dropbox / OneDrive)

Design decision (privacy-first, FOSS): **no client IDs ship with the app**.
The user registers their own OAuth client once; tokens are exchanged and
stored **encrypted on-device**. Rock Edit talks to the provider API directly
(never through a third-party proxy).

| Scenario | Handling (code) | Branch test |
|---|---|---|
| Client id / code / refresh token empty at exchange | rejected before any network call with an actionable reason | 3 tests in `OAuthTokenExchangerTest` |
| Token endpoint HTTP error | `Failure` with HTTP code + body snippet | `http error surfaces code and body snippet` |
| Network unreachable / timeout | `Failure("network request ... failed")` | `network exception maps ...` |
| Invalid JSON / missing `access_token` | explicit `Failure` branches | 2 tests |
| Provider omits `refresh_token` on refresh (Google) | old refresh token kept on merge | `refresh without a new refresh token keeps the old one` |
| Token store corrupted | fails safe to null → user re-authorizes | `corrupted store fails safe` |
| Token usable | returned without network | `usable token is returned without network` |
| Token expired | silent refresh + re-store | `expired token is refreshed and stored` |
| Connection not a cloud type | `IllegalStateException` with message | `non cloud connection type is rejected` |
| Drive: missing parent folder | `FileNotFoundException` ("create the folder first") | `missing subfolder ...`, `write into a missing parent ...` |
| Drive: existing file on write | PATCH media upload (update), not duplicate | `write over an existing file patches content only` |
| Drive: single quotes in names | escaped for the `q` grammar | `single-quoted names are escaped` |
| Drive: pagination | `nextPageToken` loop, capped | `list follows page tokens` |
| Dropbox: 409 conflict on mkdir | no-op-safe success | `mkdir conflict is no-op safe` |
| Dropbox: deleted entries / paging | skipped / `has_more` loop capped at 100 pages | `list continues while has_more` |
| OneDrive: 302 pre-authenticated download | followed explicitly; **Authorization is dropped** on the redirect host | `read follows the pre-authenticated redirect` |
| OneDrive: root-level operations | root children endpoint; deleting root rejected | `list root ...`, `delete of the root ...` |
| OAuth secret storage | `clientSecret` + tokens encrypted (Keystore AES-GCM); plain bytes never hit disk | `cloudFieldsRoundTripWithEncryptedSecret`, `access and refresh tokens are encrypted at rest` |
| Deleting a cloud connection | tokens removed together with the connection | `confirmDelete` → `removeTokens` |
| Paste field ambiguity (code vs refresh token) | code exchange first, refresh fallback, informative failure last | `authorizeAndSave` |
| Old JSON without cloud fields | parses with empty defaults (backward compatible) | `legacyJsonWithoutCloudFieldsStillParses` |

Assumptions: full `drive` scope (users browse their existing files —
documented trade-off against `drive.file`); paste-based authorization instead
of an embedded browser (no loopback server in v0.15.0, documented in the
dialog help text); Dropbox requires the user's client secret by design.

## 3. USB OTG

| Scenario | Handling (code) | Branch test |
|---|---|---|
| No USB device attached | section hidden | `refreshUsbSection` guard |
| Composite device (per-interface MSC) | interface-class detection | `mass storage detection covers ...` |
| Device label null/blank | sanitized fallback "USB storage" | `display name joins non-blank parts` |
| Permission not granted yet | `requestPermission` broadcast flow | receiver + `onUsbSectionClicked` |
| Permission denied | toast, no crash | `usbPermissionReceiver` |
| `init()` fails / no FAT partition | informative `IllegalStateException`; previous session closed deterministically | `UsbOtgSupport.open` guard branches |
| Device unplugged mid-session | next operation throws IOException → normal error path (toast); re-open re-initializes | documented + `describeError` |
| Reading/writing/deleting the volume root | rejected with clear messages; root mkdir is a no-op | 4 tests |
| Path normalization (`//`, trailing slash) | `UsbOtgLogic.volumePath` | `volume paths drop the leading slash` |
| Per-operation `use {}` close | client `close()` is a no-op; device session is process-lifetime | `client close is a no-op` |
| libaums lookup exceptions | `attachedDevices` returns empty list (fail-safe) | wrapped in try/catch |

Assumptions: only the **first** readable FAT volume is exposed; raw
mass-storage behavior cannot be verified without physical hardware — the CI
e2e suite covers the non-USB paths, and hardware verification remains a manual
release step (documented in CHANGELOG).

## Test inventory (new, v0.15.0)

- `BracketPairColorizerTest` — 15 tests
- `OAuthTokenStoreTest` — 9 tests
- `OAuthTokenExchangerTest` — 12 tests
- `CloudAuthUrlsTest` — 7 tests
- `StoreBackedCloudAuthTest` — 7 tests
- `GoogleDriveRemoteClientTest` — 15 tests
- `DropboxRemoteClientTest` — 11 tests
- `OneDriveRemoteClientTest` — 12 tests
- `UsbOtgLogicTest` + `UsbOtgRemoteClientTest` — 13 tests
- `RemoteConnectionStoreTest` — +3 tests (cloud fields, legacy JSON, ports)

## Loopback in-app OAuth (v0.16.0)

Alternative to the paste-code flow: the Storage Manager dialog offers an
**"Open in app"** button that launches `InAppAuthActivity` (in-app WebView)
with a `LoopbackRedirectServer` bound to `127.0.0.1:8642`
(`LoopbackRedirectServer.PREFERRED_PORT`).

Provider-specific redirect registration (user's own OAuth client console):

| Provider | Register this redirect URI | Why |
|---|---|---|
| Google Drive | `http://127.0.0.1` (any port, installed-app client) or `http://127.0.0.1:8642/` | RFC 8252 §7.1 allows variable loopback ports |
| Dropbox | `http://127.0.0.1:8642/` (exact) | Dropbox requires the exact registered URI |
| OneDrive (MS Graph) | `http://localhost:8642/` (exact) | Graph requires exact match; `localhost` is the permitted form |

Scenario → handling table (loopback):

| Scenario | Handling | Test |
|---|---|---|
| First callback `?code=..&state=..` | parsed once, success page served | `real socket` tests |
| Callback `?error=..` | recorded as error result (no code) | `error param` tests |
| URL-encoded values / `+` | decoded (first occurrence wins) | decode tests |
| Empty `code=` value | normalized to null (treated as absent) | `empty code` test |
| POST / malformed request line | 405/400, callback NOT recorded | method/malformed tests |
| Second (duplicate/reload) request | 200 "already used" page, callback unchanged | consumed test |
| State mismatch | inline error in-app, callback REJECTED (never accepted) | `state mismatch` decision |
| Bind failure (port taken) | activity finishes with `bind_failed` error extra | documented |
| 5-minute timeout | finishes with `timeout` error extra | documented |
| WebView crash | finishes with `webview_gone` error extra | `onRenderProcessGone` |
| Process death while authorizing | stale result dropped with toast (no mis-attributed save) | `pendingInAppAuth` guard |

Security notes: the server binds loopback ONLY (never `0.0.0.0`); the code is
never reflected into the served HTML; state is 128-bit SecureRandom and
compared constant-time (`MessageDigest.isEqual`); plain HTTP is acceptable for
installed-app loopback redirects per RFC 8252 §7.1. The code exchange reuses
the same redirect URI used at authorization time (OAuth2 requirement).

## Test inventory (new, v0.16.0)

- `CodeStatisticsTest` — 58 tests
- `CharsetLabTest` — 52 tests
- `ImageExportPlannerTest` — 55 tests
- `LoopbackRedirectServerTest` — 29 tests (real 127.0.0.1 sockets)
