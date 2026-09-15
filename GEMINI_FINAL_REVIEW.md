# Gemini Final Review Actions

Repository: `guns96x/widgen`

Purpose: final targeted cleanup after commits `a06fd8d7816a752fbcc48b46615e47001ff068ad` and `ab09e0d9547c2ab99bf7c3bf84a17ddc04a7b33b`.

Do **not** redesign the app. Do **not** rewrite working modules. Apply the fixes below in order and keep behavior unchanged except where explicitly requested.

## 1. Add explicit stale/offline state for cached quota data

### Problem
`QuotaRepository.refreshQuota()` leaves the previous successful `QuotaSnapshot` in `_snapshotFlow` when refresh fails. The UI can therefore display old quota percentages as if they are current.

### Required behavior
Keep cached quota data for usability, but mark connection freshness separately.

### Files
- `android/app/src/main/java/com/widgen/limits/data/repository/QuotaRepository.kt`
- `android/app/src/main/java/com/widgen/limits/ui/MainActivity.kt`
- widget code if it displays cached values without freshness indication
- add/update tests under `android/app/src/test/...`

### Implementation
Add a connection/freshness state owned by the repository, e.g.:

```kotlin
sealed interface BridgeConnectionState {
    data object Unknown : BridgeConnectionState
    data object Online : BridgeConnectionState
    data class Stale(val reason: String) : BridgeConnectionState
}
```

Expose it as `StateFlow<BridgeConnectionState>`.

Rules:
- initial cached snapshot + no successful network call yet => `Unknown`
- successful `refreshQuota()` => `Online`
- failed `refreshQuota()` with cached data present => `Stale(reason)`
- failed `refreshQuota()` with no cached data => `Stale(reason)` and UI should show unavailable/offline rather than percentages

Do not destroy the cached snapshot on transient failure.

UI must visibly show `STALE` / `OFFLINE` when data are not fresh.

## 2. Stop calling plaintext SharedPreferences a secure token store

### Problem
`PreferencesApiTokenStore` still stores the bearer token in plaintext SharedPreferences.

### Required behavior
Use Android Keystore-backed encrypted storage if practical with current dependencies. If introducing a stable AndroidX Security dependency is undesirable, keep the current implementation but rename/document it accurately and do not claim encryption.

Preferred implementation:
- Android Keystore-backed token storage
- no token in general app prefs
- `android:allowBackup="false"` remains

If using a dependency, pin its version explicitly in Gradle.

### Tests
Keep the `ApiTokenStore` interface so repository tests remain injectable.

## 3. Enforce the Bridge URL invariant inside the repository

### Problem
Current code:

```kotlin
BridgeUrlValidator.normalize(url).getOrDefault(url.trim())
```

allows an invalid URL to be persisted if `setBridgeUrl()` is called outside the Settings UI.

### Required fix
Change repository API to reject invalid URLs.

Recommended signature:

```kotlin
fun setBridgeUrl(url: String): Result<Unit>
```

Implementation:

```kotlin
val normalized = BridgeUrlValidator.normalize(url)
return normalized.map { clean ->
    prefs.edit().putString(KEY_BRIDGE_URL, clean).apply()
}
```

No invalid URL may ever be persisted.

Update Settings UI accordingly.

## 4. Surface refresh and account-switch errors in Android UI

### Problem
The network layer now returns failures correctly, but `MainActivity` ignores the result of `repository.switchAccount(acc.id)` and refresh failures.

### Required behavior
Use a `SnackbarHost` or equivalent visible error state.

For refresh:
- show progress while running
- on failure show a concise error
- preserve cached data but mark them stale

For account switch:
- disable only the target account switch action while request is running
- on success refresh state as currently implemented
- on failure show error text such as `Switch failed: HTTP 401 ...`
- do not falsely mark account as switched

Do not add duplicate refresh calls.

## 5. Return 400 for malformed JSON and invalid account_id type

### File
`bridge/widgen_bridge.py`

### Problem
Malformed JSON currently reaches the generic exception handler and becomes HTTP 500.

### Required behavior
For `/api/accounts/switch`:

```python
try:
    data = json.loads(body)
except json.JSONDecodeError:
    return_json_error(400, "Invalid JSON")
```

Validate:

```python
account_id = data.get("account_id") or data.get("id")
if not isinstance(account_id, str) or not account_id.strip():
    return_json_error(400, "Invalid account_id")
account_id = account_id.strip()
```

Do not allow list/dict/numeric IDs to fall through to a 500.

Prefer a small helper for JSON error responses so Content-Type and CORS behavior are consistent.

### Add integration tests
In `bridge/tests/test_http_api.py` add tests for:
- malformed JSON => 400
- `account_id: []` => 400
- `account_id: {}` => 400
- `account_id: 123` => 400
- whitespace-only string => 400

## 6. Remove the personal Windows fallback path

### Problem
Current bridge fallback includes:

```python
"C:\\Users\\pavlo"
```

### Required fix
Use a portable user-home resolution, for example:

```python
from pathlib import Path
USER_PROFILE = os.environ.get("USERPROFILE") or str(Path.home())
```

No personal username or private machine-specific path should remain in source or README examples.

## 7. Preserve the improvements already completed

Do not regress these working fixes:
- Bearer auth on protected API endpoints
- restricted CORS
- dashboard escaping
- `ThreadingHTTPServer`
- stable in-process token cache
- payload size limit
- account existence check before switch
- Settings URL validation
- `allowBackup=false`
- HTTP 4xx/5xx returned as `Result.failure` in Android client
- no fake 100% fallback for unavailable quota values

## 8. Verification commands

Run all relevant tests before committing.

### Python

```bash
python -m unittest discover -s bridge/tests -v
```

Expected: all tests PASS.

### Android unit tests

From `android/`:

```bash
./gradlew test
```

On Windows:

```powershell
.\gradlew.bat test
```

Expected: all tests PASS.

### Android build

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

## Definition of Done

This review is complete only when all of the following are true:

- stale cached quota cannot visually masquerade as fresh data
- token storage is either actually encrypted/Keystore-backed or accurately documented as plaintext private preferences
- repository cannot persist an invalid bridge URL
- refresh and switch failures are visible to the user
- malformed JSON and invalid `account_id` return HTTP 400, never 500
- no `C:\\Users\\pavlo` or other personal path remains in code
- Python tests pass
- Android unit tests pass
- Android debug APK builds successfully

Keep commits focused. Suggested order:

1. `fix(android): enforce freshness and bridge URL state`
2. `fix(android): surface switch and refresh failures`
3. `fix(android): harden token storage`
4. `fix(bridge): validate malformed requests and remove personal paths`
5. `test: cover final bridge and repository edge cases`

After implementation, summarize exactly which files changed, test commands run, and their results. Do not claim completion without actual passing test/build output.