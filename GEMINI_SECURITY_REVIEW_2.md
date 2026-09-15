# Gemini Second-Pass Security & Correctness Review

Current reviewed HEAD: `71f113b88a1c001a8eb96721c51238471650f2a1`

This is a blocking follow-up review. Do not add features until the items below are fixed and verified.

## P0 — REMOVE AND ROTATE THE COMMITTED BEARER TOKEN

File:
`android/app/src/main/java/com/widgen/limits/data/security/ApiTokenStore.kt`

Problem:
A real-looking API bearer token is committed as `DEFAULT_API_TOKEN` in a public repository. Treat it as compromised immediately.

Required changes:
1. Delete `DEFAULT_API_TOKEN` completely from source code.
2. `PreferencesApiTokenStore.get()` must return only the stored token or an empty string. No credential fallback.
3. Generate a new bridge token on the PC after this fix. Do not reuse the committed value.
4. Update the Android app manually through Settings with the newly generated token.
5. Search the entire repository for the old token and remove every occurrence.
6. Never place a generated bearer token in source, APK defaults, README, tests, examples, logs, screenshots, or commit messages.

Target implementation:
```kotlin
override fun get(): String = prefs.getString(KEY_API_TOKEN, "") ?: ""
```

Acceptance criteria:
- `DEFAULT_API_TOKEN` does not exist.
- Repository search for the old secret returns zero results in current tree.
- New installs require explicit token configuration.
- Bridge token has been rotated on the PC.

Note: deleting the token from the current tree does not remove it from Git history. Rotation is mandatory.

---

## P0 — REMOVE PERSONAL TAILSCALE IP DEFAULT

File:
`android/app/src/main/java/com/widgen/limits/data/repository/QuotaRepository.kt`

Problem:
`DEFAULT_BRIDGE_URL` was changed back to a personal Tailscale address. This leaks deployment-specific infrastructure and silently makes fresh installs target one specific machine.

Required change:
```kotlin
const val DEFAULT_BRIDGE_URL = ""
```

Fresh install behavior must be:
- no bridge URL configured;
- setup banner shown;
- user explicitly enters LAN/Tailscale URL.

Do not replace it with another private IP.

---

## P1 — FIX INITIAL CONNECTION STATE FOR CACHED DATA

Files:
- `QuotaRepository.kt`
- `MainActivity.kt`

Current behavior:
A cached snapshot may be loaded immediately, while `connectionState` starts as `Unknown`, rendered as `OFFLINE`. This is acceptable only if the UI explicitly communicates that cached data is not verified yet.

Required behavior:
- `Unknown` = never attempted current-session connection / cached data not verified.
- `Online` = latest refresh succeeded.
- `Stale(reason)` = refresh attempted and failed while previous snapshot may still be shown.

If cached data exists under `Unknown`, show `CACHED` or `NOT VERIFIED` instead of plain `OFFLINE` so the state matches what is on screen.

Do not erase useful cached quota values on failure; mark them stale.

---

## P1 — DO NOT DISCARD REFRESH FAILURE AFTER A SUCCESSFUL SWITCH

File:
`QuotaRepository.kt`

Current pattern:
```kotlin
if (result.getOrNull() == true) {
    refreshQuota()
}
return result
```

Problem:
The account switch may succeed but the subsequent refresh may fail. The caller receives `Result.success(true)` and cannot know that UI data was not refreshed.

Required semantics:
- Switch HTTP failure -> `Result.failure`.
- Switch success + refresh success -> `Result.success(true)`.
- Switch success + refresh failure -> return a failure describing that switch succeeded but refresh failed OR introduce a typed result that distinguishes `SwitchedAndRefreshed` from `SwitchedButRefreshFailed`.

Minimum acceptable implementation without new architecture:
```kotlin
val switched = apiClient.switchAccount(url, token, accountId)
if (switched.isFailure) return switched
if (switched.getOrNull() != true) return Result.failure(...)

val refresh = refreshQuota()
if (refresh.isFailure) {
    return Result.failure(IllegalStateException(
        "Account switched, but quota refresh failed: ${refresh.exceptionOrNull()?.message ?: "unknown error"}",
        refresh.exceptionOrNull()
    ))
}
return Result.success(true)
```

Add a test for this case.

---

## P1 — SEND ONLY THE FIELD EXPECTED BY ANTIGRAVITY TOOLS

File:
`bridge/widgen_bridge.py`

Current payload sends both:
```python
{"accountId": account_id, "account_id": account_id}
```

If Antigravity Tools requires camelCase, send only the documented/observed field:
```python
{"accountId": account_id}
```

Reason: sending duplicate aliases creates ambiguity and may break if the upstream API begins rejecting unknown fields.

Add/update a unit test that inspects the outgoing JSON body and asserts exactly one account identifier field.

---

## P1 — STRENGTHEN ACCOUNT LIST NORMALIZATION

File:
`bridge/widgen_bridge.py`

When validating the requested switch target, normalize account IDs to strings before membership comparison. Upstream JSON may expose IDs with unexpected primitive types.

Use behavior equivalent to:
```python
known_ids = {
    str(a.get("id")).strip()
    for a in known_accounts
    if a.get("id") is not None and str(a.get("id")).strip()
}
```

The inbound `account_id` is already required to be a non-empty string. Keep that validation.

---

## P2 — TOKEN STORE NAMING / SECURITY CLAIM

File:
`ApiTokenStore.kt`

The implementation still uses ordinary private `SharedPreferences`; this is not encrypted storage. `allowBackup=false` reduces exposure but does not make the preference encrypted.

Choose one of these two approaches:

### Preferred
Use Android Keystore-backed encrypted storage appropriate for the project's minSdk.

### Minimal acceptable
Keep `SharedPreferences`, but:
- do not call it encrypted/secure in comments;
- keep `allowBackup=false`;
- document that the token is app-private plaintext on-device storage.

Do not add a third-party dependency solely for this unless necessary.

---

## P2 — ADD CI

Current repository has tests but no GitHub status checks for HEAD.

Add `.github/workflows/ci.yml` with at least:

### Python bridge
```bash
python -m unittest discover -s bridge/tests -v
```

### Android unit tests
From `android/`:
```bash
./gradlew testDebugUnitTest
```

### Android build
```bash
./gradlew assembleDebug
```

Use Java 17.

CI must run on push and pull_request.

Do not publish secrets or upload the local bearer token as a CI secret; tests must use temporary test tokens only.

---

# Regression checks that must stay passing

Do not regress the fixes already present:
- `ThreadingHTTPServer` stays enabled.
- malformed JSON returns HTTP 400, not 500.
- non-object JSON returns 400.
- invalid/non-string/empty `account_id` returns 400.
- oversized request returns 413.
- `/api/quota` and `/api/accounts` require Bearer auth.
- `/api/accounts/switch` requires Bearer auth.
- CORS remains restricted.
- personal Windows path fallback stays removed; use `USERPROFILE` or `Path.home()`.
- stale/refresh errors remain visible to Android UI.
- switch has a loading state and user-visible error.
- URL validation remains enforced before persistence.

# Required tests

Add or update tests for:
1. token store returns empty when no token saved;
2. no default credential exists;
3. invalid URL cannot be persisted;
4. successful switch + failed refresh is surfaced to caller;
5. bridge sends exactly `accountId` upstream;
6. invalid switch JSON -> 400;
7. list/non-object JSON -> 400;
8. invalid account ID type -> 400;
9. unknown account -> 404;
10. authorized switch success -> 200.

# Required execution order

1. Remove committed credential.
2. Remove personal bridge URL default.
3. Rotate bridge token locally.
4. Fix switch/refresh result semantics.
5. Simplify upstream switch payload to camelCase only.
6. Add/update tests.
7. Add CI.
8. Build APK only after tests pass.
9. Commit code changes.
10. Report exact test/build results in commit/PR notes.

# Definition of Done

Do not mark complete until all are true:
- [ ] No bearer token is hardcoded anywhere in current source tree.
- [ ] Old committed token has been rotated and is unusable.
- [ ] No personal Tailscale/LAN IP is used as a default.
- [ ] Fresh install requires explicit bridge URL + token.
- [ ] Cached/stale state is unambiguous in UI.
- [ ] Switch success followed by refresh failure is surfaced.
- [ ] Antigravity Tools switch payload uses only `accountId`.
- [ ] Python tests pass.
- [ ] Android unit tests pass.
- [ ] Android debug APK builds.
- [ ] GitHub Actions CI exists and passes.

Do not spend time on new UI features, Direct OAuth, or major bridge modularization until this list is green.