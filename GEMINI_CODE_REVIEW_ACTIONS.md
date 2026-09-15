# Gemini Code Review Follow-Up — Execute, Don’t Re-Design

Repository: `guns96x/widgen`

Current reviewed commit: `19bd930947ce3542d5f442e2b8a7e36b0395fe0e`

This document is a **targeted implementation checklist** based on code review. Do not re-architect the project from scratch. Do not replace working components unless explicitly required below. Work task-by-task, keep behavior stable, add tests for each fix, and commit after each logical group.

## Priority Order

1. Fix URL validation integration
2. Fix account switch result semantics
3. Secure API token storage / backup behavior
4. Stabilize bridge token generation on persistence failure
5. Replace single-threaded HTTP server
6. Add HTTP integration tests
7. Cleanup dead refresh callback/code
8. Only then consider bridge modularization

---

## Task 1 — Actually use `BridgeUrlValidator` in production flow

### Problem

`android/app/src/main/java/com/widgen/limits/data/util/BridgeUrlValidator.kt` exists and has tests, but the Settings UI saves the raw text directly. This means malformed URLs can still be persisted.

### Files

Modify:
- `android/app/src/main/java/com/widgen/limits/ui/MainActivity.kt`
- optionally `android/app/src/main/java/com/widgen/limits/data/repository/QuotaRepository.kt`

Existing validator:
- `android/app/src/main/java/com/widgen/limits/data/util/BridgeUrlValidator.kt`

### Required behavior

When user taps **Save & Connect**:

1. Call `BridgeUrlValidator.normalize(urlText)`.
2. If it fails:
   - do not save URL;
   - do not close dialog;
   - display validation error in the dialog.
3. If it succeeds:
   - save normalized URL;
   - save token;
   - close dialog;
   - call `repository.refreshQuota()` once.

### Exact UI state suggestion

Inside `SettingsDialog` add:

```kotlin
var validationError by remember { mutableStateOf<String?>(null) }
```

On confirm:

```kotlin
val normalized = BridgeUrlValidator.normalize(urlText)
normalized.fold(
    onSuccess = { cleanUrl ->
        validationError = null
        onSave(cleanUrl, tokenText.trim())
    },
    onFailure = { error ->
        validationError = error.message ?: "Invalid Bridge URL"
    }
)
```

Display `validationError` below URL field using `MaterialTheme.colorScheme.error` or existing error color.

### Acceptance criteria

- `192.168.1.20:59123` saves as `http://192.168.1.20:59123`.
- `http://192.168.1.20:59123/` saves without trailing slash.
- `file:///tmp/x`, `javascript:...`, blank input and missing-host URLs are rejected.
- Dialog remains open on validation error.
- Existing `BridgeUrlValidatorTest` passes.

---

## Task 2 — Fix `switchAccount()` success/failure semantics

### Problem

Current API client returns:

```kotlin
Result.success(response.isSuccessful)
```

This means HTTP 401/403/500 becomes `Result.success(false)`.

Then repository checks only `result.isSuccess`, so it may refresh after a failed switch.

### Files

Modify:
- `android/app/src/main/java/com/widgen/limits/data/api/QuotaApiClient.kt`
- `android/app/src/main/java/com/widgen/limits/data/repository/QuotaRepository.kt`

Add tests under:
- `android/app/src/test/java/...`

### Required API client behavior

`switchAccount(...)` must return:

- HTTP 2xx + success response -> `Result.success(true)`
- HTTP 401/403/404/500 -> `Result.failure(IOException(...))`
- transport exception -> `Result.failure(exception)`

Do not return `Result.success(false)` for HTTP errors.

Suggested implementation:

```kotlin
client.newCall(request).execute().use { response ->
    if (!response.isSuccessful) {
        return@withContext Result.failure(
            IOException("HTTP ${response.code}: ${response.message}")
        )
    }
    Result.success(true)
}
```

### Required repository behavior

Only refresh if the switch really succeeded:

```kotlin
val result = apiClient.switchAccount(url, token, accountId)
if (result.getOrNull() == true) {
    refreshQuota()
}
return result
```

### Acceptance criteria

- HTTP 200 -> switch success + one quota refresh.
- HTTP 401 -> failure, no refresh.
- HTTP 500 -> failure, no refresh.
- network failure -> failure, no refresh.

---

## Task 3 — Store API token more safely

### Problem

`KEY_API_TOKEN` currently lives in ordinary `SharedPreferences`. The token controls bridge API access. App backup may also expose app preferences depending on manifest/device behavior.

### Files

Inspect/modify:
- `android/app/src/main/AndroidManifest.xml`
- `android/app/build.gradle.kts`
- `android/app/src/main/java/com/widgen/limits/data/repository/QuotaRepository.kt`

### Minimum required hardening

At minimum set:

```xml
android:allowBackup="false"
```

unless there is a specific documented reason to keep backup enabled.

### Preferred implementation

Move token storage behind a dedicated abstraction, e.g.:

`android/app/src/main/java/com/widgen/limits/data/security/SecureTokenStore.kt`

Use Android Keystore-backed storage if practical with the current dependency set. Keep bridge URL and cached quota in normal preferences, but keep the bearer token in secure storage.

Required interface:

```kotlin
interface ApiTokenStore {
    fun get(): String
    fun set(token: String)
    fun clear()
}
```

`QuotaRepository` should use this abstraction instead of reading token directly from normal preferences.

### Constraints

- Never log the full bearer token.
- Never write token into README, source, tests, or APK resources.
- UI may show only a password field, not the current token in plain text.

### Acceptance criteria

- app still authenticates normally after restart;
- token is not stored in the existing plain `antigravity_limits_prefs` entry;
- `allowBackup=false` unless explicitly documented otherwise.

---

## Task 4 — Make bridge token generation stable when config persistence fails

### Problem

Current `get_api_token()` can generate a token, fail to write it to disk, return it, then generate a different token on the next call. This can make authorization randomly fail during the same process lifetime.

### File

Modify:
- `bridge/widgen_bridge.py`

Add tests:
- `bridge/tests/test_auth.py`

### Required behavior

Introduce process-level cache:

```python
_api_token_cache = None
```

`get_api_token()` order:

1. return non-empty `WIDGEN_API_TOKEN` env value if present;
2. if `_api_token_cache` exists, return it;
3. try config file;
4. if config contains valid token, cache and return it;
5. otherwise generate exactly one token, assign it to `_api_token_cache`;
6. attempt persistence;
7. if persistence fails, keep using the same cached token for process lifetime.

Do not generate a new token on every call.

### Tests required

Add tests for:

- environment token wins;
- config token is reused;
- generated token remains identical across repeated calls;
- simulated write failure still returns the same token on subsequent calls.

Reset module cache between tests.

### Acceptance criteria

`get_api_token()` is deterministic within a running bridge process.

---

## Task 5 — Replace `HTTPServer` with `ThreadingHTTPServer`

### Problem

The bridge is single-threaded. `switch_antigravity_account()` can block for up to 25 seconds, causing quota and health requests to wait behind it.

### File

Modify:
- `bridge/widgen_bridge.py`

### Required change

Replace:

```python
from http.server import HTTPServer, BaseHTTPRequestHandler
```

with:

```python
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
```

and replace:

```python
server = HTTPServer((BRIDGE_HOST, BRIDGE_PORT), WidgenHandler)
```

with:

```python
server = ThreadingHTTPServer((BRIDGE_HOST, BRIDGE_PORT), WidgenHandler)
server.daemon_threads = True
```

### Acceptance criteria

A long account-switch request must not block `/api/health` or authenticated `/api/quota` requests.

---

## Task 6 — Add bridge HTTP integration tests

### Goal

Current auth unit tests only call helper functions. Add endpoint-level tests around the HTTP handler.

### Files

Add:
- `bridge/tests/test_http_api.py`

### Test scenarios

At minimum cover:

#### `/api/health`

- no token required;
- returns HTTP 200;
- returns valid JSON with `status` and `timestamp`.

#### `/api/quota`

- missing Authorization -> 401;
- bad bearer token -> 401;
- valid bearer token -> 200.

Mock expensive provider functions so tests do not require Codex/Antigravity installed.

#### `/api/accounts`

- missing token -> 401;
- valid token -> 200.

#### `/api/accounts/switch`

- missing token -> 401;
- invalid token -> 401;
- valid token + missing account_id -> 400;
- valid token + mocked successful switch -> 200;
- mocked failed switch -> 500.

#### CORS

- localhost allowed origin gets CORS headers;
- unrelated origin does not get wildcard CORS.

### Implementation guidance

Start `ThreadingHTTPServer` on port `0` inside the test fixture so the OS selects an available test port.

Run server in a background thread and shut it down after each test class/module.

### Acceptance criteria

All endpoint behavior above is covered without depending on real local services.

---

## Task 7 — Remove dead refresh plumbing

### Problem

`MainActivity` still passes `onRefreshRequested` into `MainScreen`, but manual refresh now directly calls `repository.refreshQuota()` and the callback is no longer needed in the main refresh path.

### File

Modify:
- `android/app/src/main/java/com/widgen/limits/ui/MainActivity.kt`

### Required cleanup

Remove:

```kotlin
onRefreshRequested: () -> Unit
```

from `MainScreen` if no live call sites remain.

Remove unnecessary `OneTimeWorkRequestBuilder`, `WorkManager`, `QuotaSyncWorker` imports from `MainActivity.kt` if they become unused.

Do not remove WorkManager from the application/widget background sync architecture.

### Acceptance criteria

- no unused callback;
- no unused imports;
- manual refresh still works;
- widget periodic/background sync still works.

---

## Task 8 — Improve stale/offline state without inventing 100%

### Current state

Antigravity pool cards were improved to display `—` when pool is absent. Preserve this behavior.

### Required review

Check all remaining UI paths for fallback values that can falsely represent missing data as full quota.

Inspect at least:

- `CodexHeroCard`
- `AntigravityWidget.kt`
- model quota rendering
- cached snapshot handling

### Rule

Never display `100%` merely because data is absent.

Use one of:

- `—`
- `Unavailable`
- `Offline`
- cached data with an explicit stale indicator

### Important

If `CodexInfo` exists but one specific window is null, `CodexHeroCard` currently has fallback expressions like:

```kotlin
session?.remainingPercent ?: 100
```

Change this so a missing window is shown as unknown, not 100%.

### Acceptance criteria

No missing/null quota object produces a fake `100% remaining` display.

---

## Task 9 — Validate account IDs server-side

### Problem

`/api/accounts/switch` accepts arbitrary JSON account IDs and forwards them to Antigravity Tools.

### File

Modify:
- `bridge/widgen_bridge.py`

### Required behavior

Before switching:

1. fetch known account list from `fetch_antigravity_tools_accounts()`;
2. confirm requested `account_id` exists in the returned list;
3. if account is unknown, return HTTP 404 or 400 and do not call switch API.

Keep implementation simple; do not invent a new account database.

### Tests

Add endpoint tests for:

- known account -> switch attempted;
- unknown account -> rejected, switch function not called.

---

## Task 10 — Input/body limits for POST endpoint

### Problem

`Content-Length` is accepted without an explicit maximum.

### File

Modify:
- `bridge/widgen_bridge.py`

### Required behavior

For `/api/accounts/switch`:

- reject missing/invalid/negative content length appropriately;
- enforce a small maximum body size, e.g. 8 KiB;
- return HTTP 413 if exceeded.

Suggested constant:

```python
MAX_JSON_BODY_BYTES = 8 * 1024
```

### Acceptance criteria

Oversized bodies are rejected before reading arbitrary amounts into memory.

---

## Task 11 — Keep health endpoint intentionally minimal

Current `/api/health` exposes only generic status and timestamp. Keep it that way.

Do not expose:

- filesystem paths;
- usernames;
- Codex token state details;
- API token;
- Antigravity account emails;
- process IDs;
- internal language-server ports.

No change needed if current behavior remains minimal.

---

## Task 12 — Tests and commands Gemini must run before declaring completion

### Python

From repository root:

```bash
python -m unittest discover -s bridge/tests -v
```

All tests must pass.

Also run syntax check:

```bash
python -m py_compile bridge/widgen_bridge.py
```

### Android unit tests

From `android/`:

```bash
./gradlew testDebugUnitTest
```

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest
```

### Android build

```bash
./gradlew :app:assembleDebug
```

or Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

### Static cleanup

Before final commit:

- remove unused imports;
- search for hardcoded `100.82.252.86` and remove remaining personal/default occurrences;
- search for hardcoded bearer/API tokens;
- search for `Result.success(response.isSuccessful)` and ensure it is gone for account switch;
- search for `?: 100` in quota UI paths and review every occurrence for false-100 behavior.

---

# Required commit structure

Prefer separate commits so review is easy:

1. `fix(android): validate bridge URL before saving`
2. `fix(android): correct account switch failure semantics`
3. `security(android): harden bearer token storage`
4. `fix(bridge): stabilize generated API token`
5. `fix(bridge): use threaded HTTP server`
6. `test(bridge): add authenticated endpoint integration tests`
7. `fix(ui): remove fake 100 percent quota fallbacks`
8. `security(bridge): validate switch account and request body`
9. `chore(android): remove dead refresh plumbing`

If implementation constraints require combining closely related commits, keep each commit reviewable and explain why.

---

# Do Not Do Yet

Until all tasks above pass tests, do **not** spend time on:

- Direct Google OAuth;
- Direct OpenAI OAuth;
- major UI redesign;
- replacing Compose architecture;
- replacing WorkManager;
- rewriting bridge in another framework;
- broad provider refactoring;
- adding unrelated features.

Those are separate phases.

---

# Definition of Done

This review round is complete only when all of the following are true:

- Settings cannot persist an invalid bridge URL.
- Failed account switch is a `Result.failure`, not successful false.
- Failed switch does not trigger quota refresh.
- Bearer token is not casually exposed via backup/plain preference path.
- Bridge token remains stable if config write fails.
- Bridge uses `ThreadingHTTPServer`.
- Endpoint integration tests cover auth failures and successes.
- Unknown account IDs cannot be switched.
- POST body has a size limit.
- Missing quota data never appears as fake 100% remaining.
- Python tests pass.
- Android unit tests pass.
- Android debug APK builds successfully.
- No hardcoded personal Tailscale IP remains as a production default.
- No real API token is committed.

After implementation, provide a short completion report containing:

1. commits created;
2. files changed;
3. tests executed and exact result;
4. APK build result;
5. anything intentionally deferred.
