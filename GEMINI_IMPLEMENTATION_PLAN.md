# Widgen — детальний план виправлень для Gemini

> Цей файл є робочим ТЗ для AI-агента. Не витрачай час на повторний загальний аналіз репозиторію. Виконуй задачі нижче по порядку, перевіряй результат після кожного етапу і коміть логічними блоками.

## 1. Мета

Довести `guns96x/widgen` з поточного MVP до безпечного та передбачуваного LAN/Tailscale-рішення для:

- перегляду OpenAI Codex quota;
- перегляду Antigravity quota;
- перегляду декількох Antigravity-акаунтів;
- перемикання активного Antigravity-акаунта з Android;
- роботи Android App + Glance widget через PC Bridge;
- коректного відображення offline/unknown станів;
- подальшого безпечного додавання Direct OAuth, але **не реалізовувати Direct OAuth у цьому етапі**, якщо його немає у вимогах окремої задачі.

Головний принцип: PC Bridge має бути безпечним навіть у локальній мережі. Жоден клієнт без токена не повинен мати можливість читати приватні quota/акаунти або виконувати `switch`.

---

# 2. Поточна архітектура

## PC

Основний файл:

- `bridge/widgen_bridge.py`

Він зараз одночасно:

- читає Antigravity Tools config;
- звертається до `127.0.0.1:8045`;
- шукає `language_server.exe`;
- дістає CSRF token із command line;
- читає `~/.codex/auth.json`;
- звертається до Codex usage API;
- нормалізує quota;
- кешує дані;
- реалізує HTTP API;
- рендерить HTML dashboard.

## Android

Основні файли:

- `android/app/src/main/java/com/widgen/limits/data/api/QuotaApiClient.kt`
- `android/app/src/main/java/com/widgen/limits/data/repository/QuotaRepository.kt`
- `android/app/src/main/java/com/widgen/limits/data/model/QuotaModels.kt`
- `android/app/src/main/java/com/widgen/limits/ui/MainActivity.kt`
- `android/app/src/main/java/com/widgen/limits/widget/AntigravityWidget.kt`
- `android/app/src/main/java/com/widgen/limits/worker/QuotaSyncWorker.kt`
- `android/app/src/main/java/com/widgen/limits/WidgenApplication.kt`
- `android/app/src/main/AndroidManifest.xml`

---

# 3. Пріоритети

Виконувати саме у цьому порядку:

1. API authentication.
2. Прибрати hardcoded приватні IP/конфігурацію.
3. Зробити правильні offline/unknown semantics.
4. Прибрати дубльовані refresh-запити.
5. Виправити widget refresh lifecycle.
6. Закрити CORS/XSS проблеми dashboard.
7. Додати тести.
8. Розділити великий `widgen_bridge.py` на модулі.
9. Оновити README відповідно до фактичної реалізації.

Direct OAuth — окрема майбутня фаза.

---

# 4. TASK 1 — API authentication

## Проблема

Bridge за замовчуванням слухає `0.0.0.0:59123`, але `/api/*` не має нормальної автентифікації.

Поточна перевірка `Origin` для `/api/accounts/switch` не є security boundary.

Будь-який HTTP-клієнт, який бачить bridge, може відправити POST без `Origin`.

## Ціль

Додати shared bearer token між PC Bridge та Android App.

## Поведінка

Bridge повинен приймати:

```http
Authorization: Bearer <token>
```

Токен:

- довжина мінімум 32 випадкових bytes у source entropy;
- не хардкодити в repo;
- брати з environment variable `WIDGEN_API_TOKEN`;
- якщо токена немає — при першому локальному запуску можна згенерувати його і зберегти у локальному config-файлі;
- ніколи не друкувати повний токен у логи;
- дозволено показати лише короткий masked form типу `abcd...91ef`.

## Protected endpoints

Захистити:

- `GET /api/quota`
- `GET /api/accounts`
- `POST /api/accounts/switch`

`GET /api/health` може залишитися без auth, але відповідь не повинна містити email, токени, account ids чи інші приватні дані.

Dashboard `/` та `/dashboard`:

- або також вимагати bearer auth;
- або залишити лише для localhost.

Рекомендований варіант: localhost-only dashboard + token-protected API.

## Bridge implementation

Створити helper:

```python
def get_api_token() -> str:
    ...


def is_authorized(headers) -> bool:
    ...
```

Використовувати constant-time compare:

```python
import hmac
hmac.compare_digest(received_token, expected_token)
```

Не порівнювати секрети простим `==`.

При відсутньому або неправильному токені:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{"error":"unauthorized"}
```

## Android implementation

У `QuotaRepository` додати:

```kotlin
fun getApiToken(): String
fun setApiToken(token: String)
```

Зберігати token локально.

Бажано використовувати AndroidX Security / EncryptedSharedPreferences, якщо це не ускладнює збірку надмірно. Якщо dependency не додається — хоча б ізолювати token окремим ключем і не логувати його.

У `QuotaApiClient` всі API-запити повинні містити:

```kotlin
.header("Authorization", "Bearer $apiToken")
```

Очікувані сигнатури:

```kotlin
suspend fun fetchQuota(baseUrl: String, apiToken: String): Result<QuotaSnapshot>

suspend fun switchAccount(
    baseUrl: String,
    apiToken: String,
    accountId: String
): Result<Boolean>
```

## UI

У Settings Dialog повинні бути:

- Bridge URL;
- API Token;
- кнопка `Save & Connect`.

Token поле:

- password visual transformation;
- не показувати токен у відкритому вигляді після збереження;
- можна додати show/hide toggle пізніше, але це не обов'язково.

## Acceptance criteria

Без токена:

```bash
curl http://PC:59123/api/quota
```

має повернути `401`.

З правильним токеном:

```bash
curl -H "Authorization: Bearer TOKEN" http://PC:59123/api/quota
```

має повернути `200`.

POST switch без токена — `401`.

POST switch з токеном — працює.

Android після введення URL + token успішно підключається.

---

# 5. TASK 2 — прибрати hardcoded Tailscale IP

## Проблема

У repo присутній конкретний IP:

```text
100.82.252.86
```

Він є у README та Android default config.

## Треба

У `QuotaRepository.kt` змінити:

```kotlin
const val DEFAULT_BRIDGE_URL = "http://100.82.252.86:59123"
```

на нейтральний варіант.

Рекомендовано:

```kotlin
const val DEFAULT_BRIDGE_URL = "http://192.168.1.100:59123"
```

або порожнє значення.

Кращий UX:

- якщо bridge URL не налаштований — показати setup state;
- не робити запити на вигаданий default IP.

Рекомендована модель:

```kotlin
const val DEFAULT_BRIDGE_URL = ""
```

а UI показує:

```text
Bridge not configured
```

README також не повинен містити конкретний приватний IP власника repo.

Приклади в README:

```text
http://192.168.1.50:59123
```

або:

```text
http://<PC-LAN-IP>:59123
```

---

# 6. TASK 3 — правильні Unknown / Offline states

## Проблема

Поточний UI у багатьох місцях трактує відсутні дані як:

```text
100%
Ready
```

Це неправильно.

`null` не означає 100% quota.

## Нова модель станів

Ввести явне поняття availability.

Мінімальний варіант — не змінювати JSON schema глобально, а в UI:

- якщо `snapshot == null` → `Not connected`;
- якщо `codex == null` → `Codex unavailable`;
- якщо `pool == null` → `Quota unavailable`;
- не малювати 100% progress для unknown.

Кращий варіант — додати:

```kotlin
enum class DataStatus {
    ONLINE,
    STALE,
    OFFLINE,
    UNKNOWN
}
```

Але не перебудовувати весь застосунок без потреби.

## Widget

Замість:

```kotlin
val codexSessionPct = codex?.sessionWindow?.remainingPercent ?: 100
```

логіка має розділяти:

```text
known percentage
unknown percentage
```

Для unknown показувати:

```text
—
Offline
Unavailable
```

але не `100%`.

## MainActivity

Так само:

- Codex section приховати або показати `Unavailable`;
- PoolHeroCard має підтримувати `pool == null`;
- progress bar для unknown не показувати.

## Acceptance criteria

1. Вимкнути bridge.
2. Відкрити app/widget.
3. UI не повинен показувати 100% як актуальні quota.
4. Має бути зрозумілий cached/offline/unknown status.

---

# 7. TASK 4 — прибрати подвійні refresh-запити

## Поточна проблема

У `MainActivity` refresh робить:

1. `repository.refreshQuota()`;
2. потім запускає `QuotaSyncWorker`;
3. worker ще раз робить `repository.refreshQuota()`.

Після account switch є схожа дубльована логіка.

## Цільова модель

Повинен бути один власник network refresh.

Рекомендовано:

### Interactive UI refresh

UI напряму викликає:

```kotlin
repository.refreshQuota()
```

Після успішного refresh:

```kotlin
AntigravityWidget().updateAll(context)
```

Не запускати WorkManager для ручного refresh.

### Background refresh

WorkManager залишається тільки для:

- periodic sync;
- background scheduled sync.

### Switch

`repository.switchAccount()`:

1. POST switch;
2. якщо success — один `refreshQuota()`;
3. оновити widget;
4. повернути success/failure.

Не запускати додатковий worker із UI.

## Acceptance criteria

При одному натисканні Refresh у bridge logs має бути один quota refresh cycle, а не два.

---

# 8. TASK 5 — widget refresh lifecycle

## Поточна проблема

`RefreshWidgetCallback`:

1. enqueue worker;
2. одразу `update()`;
3. widget перерендерюється зі старого cache;
4. потім worker завершується і знову оновлює widget.

## Треба

Callback повинен лише запустити refresh job.

UI update робити після завершення network refresh у worker.

Приклад:

```kotlin
class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(...) {
        val request = OneTimeWorkRequestBuilder<QuotaSyncWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
```

У `QuotaSyncWorker` після `repository.refreshQuota()`:

```kotlin
AntigravityWidget().updateAll(applicationContext)
```

це вже є — залишити саме його як джерело update після network operation.

---

# 9. TASK 6 — CORS hardening

## Поточна проблема

Bridge повертає:

```http
Access-Control-Allow-Origin: *
```

Це зайве для native Android app.

## Треба

Якщо dashboard не потребує cross-origin requests — прибрати wildcard CORS повністю.

Якщо CORS потрібен:

- allowlist лише localhost origins;
- ніколи не `*` для приватних quota endpoints.

Наприклад:

```python
ALLOWED_ORIGINS = {
    "http://127.0.0.1:59123",
    "http://localhost:59123",
}
```

Native Android OkHttp CORS не потребує.

---

# 10. TASK 7 — XSS hardening dashboard

## Поточна проблема

Bridge вставляє у HTML без escaping:

- email;
- name;
- account id;
- інші runtime strings.

## Треба

Для HTML:

```python
import html
safe_email = html.escape(str(email), quote=True)
```

Для значень, які йдуть у JavaScript:

не робити ручне quoting через f-string.

Використовувати:

```python
json.dumps(account_id)
```

Наприклад:

```python
js_account_id = json.dumps(a.get("id"))
```

і лише тоді вставляти у JS.

Не змішувати raw data та HTML rendering.

---

# 11. TASK 8 — error model

Зараз багато місць ковтають exception:

```python
except Exception:
    pass
```

або:

```kotlin
catch (_: Exception) {}
```

Це ускладнює діагностику.

## Bridge

Додати мінімальний structured logging:

```python
import logging
logger = logging.getLogger("widgen")
```

Рівні:

- INFO — startup, detected providers;
- WARNING — provider unavailable;
- ERROR — malformed data / unexpected failures;
- DEBUG — деталі без секретів.

Ніколи не логувати:

- access token;
- refresh token;
- CSRF token;
- API token;
- contents `auth.json`.

## Android

Не треба додавати складний logging framework.

Можна використовувати:

```kotlin
Log.w(...)
Log.e(...)
```

але без API token та інших секретів.

---

# 12. TASK 9 — bridge modularization

Виконувати після security fixes, щоб не змішати functional change та refactor.

## Цільова структура

```text
bridge/
  widgen_bridge.py
  config.py
  models.py
  cache.py
  auth.py
  providers/
    __init__.py
    codex.py
    antigravity_tools.py
    antigravity_language_server.py
  http_api.py
  dashboard.py
```

## Відповідальності

### `config.py`

- `WIDGEN_BRIDGE_PORT`
- `WIDGEN_BRIDGE_HOST`
- paths
- API token config
- cache TTL

### `auth.py`

- bearer extraction;
- constant-time validation;
- token loading/generation.

### `providers/codex.py`

- читання Codex auth;
- Codex usage request;
- normalization Codex quota.

### `providers/antigravity_tools.py`

- `get_antigravity_tools_api_key()`;
- account list;
- account switching.

### `providers/antigravity_language_server.py`

- Windows process discovery;
- CSRF extraction;
- port discovery;
- live Language Server quota.

### `models.py`

TypedDict/dataclasses для normalized snapshot.

Не обов'язково використовувати Pydantic.

### `cache.py`

- snapshot cache;
- TTL;
- invalidation після account switch.

### `dashboard.py`

- HTML rendering;
- escaping.

### `http_api.py`

- HTTP handler;
- routing;
- auth;
- JSON responses.

### `widgen_bridge.py`

Має залишитися lightweight entrypoint:

```python
from http_api import run_bridge

if __name__ == "__main__":
    run_bridge()
```

---

# 13. TASK 10 — URL validation в Android

Settings зараз дозволяє зберегти будь-який рядок.

## Треба

Перед save:

- trim whitespace;
- якщо немає `http://` або `https://` — додати `http://`;
- перевірити, що URL парситься;
- не приймати порожній host;
- не дозволяти `file://`, `content://`, `javascript:` тощо.

Допустимі schemes:

```text
http
https
```

Створити helper, наприклад:

```kotlin
object BridgeUrlValidator {
    fun normalize(input: String): Result<String>
}
```

Додати unit tests.

---

# 14. TASK 11 — tests

Немає покладатися лише на manual testing.

## Python tests

Створити:

```text
bridge/tests/
  test_auth.py
  test_time_format.py
  test_normalization.py
  test_http_api.py
```

Використати стандартний `unittest`, якщо не хочеш додавати pytest dependency.

### Мінімальні auth tests

Перевірити:

- no Authorization header → false;
- wrong scheme → false;
- empty token → false;
- incorrect bearer → false;
- correct bearer → true.

### HTTP tests

Запустити temporary server на localhost/random port.

Перевірити:

- `/api/health` → 200;
- `/api/quota` без token → 401;
- `/api/quota` з token → auth проходить;
- switch без token → 401.

Не використовувати реальні Codex/Google tokens у tests.

Всі provider calls mock.

## Android unit tests

Створити:

```text
android/app/src/test/java/com/widgen/limits/
```

Тести мінімум для:

- BridgeUrlValidator;
- JSON parsing `QuotaSnapshot`;
- unknown/null state helpers;
- URL `/api/quota` normalization.

Якщо auth header builder винесений в helper — перевірити його окремо.

---

# 15. TASK 12 — README synchronization

README зараз не повинен заявляти функціональність, якої реально немає.

## Змінити секцію Direct OAuth

Якщо direct mode ще не реалізований, написати:

```text
Planned / experimental
```

Не писати, що Android app уже працює standalone без PC, якщо фактичний код використовує тільки PC Bridge.

## Додати Security setup

README повинен містити:

```powershell
$env:WIDGEN_API_TOKEN="<random-secret>"
.\bridge\run_bridge.ps1
```

або актуальний механізм config/token generation після реалізації.

Також додати Android setup:

1. Відкрити Settings.
2. Ввести LAN/Tailscale IP bridge.
3. Ввести API token.
4. Save & Connect.

Не комітити реальний token.

---

# 16. TASK 13 — Android cleartext policy

Поточний manifest:

```xml
android:usesCleartextTraffic="true"
```

Для локального HTTP bridge це може бути необхідно.

Не вимикати бездумно, інакше LAN HTTP перестане працювати.

Замість цього:

- залишити cleartext для поточної LAN architecture;
- задокументувати, що security забезпечується bearer token + trusted LAN/Tailscale;
- у майбутньому можна перейти на HTTPS/mTLS.

Не додавати self-signed TLS зараз без окремої причини — це значно ускладнить Android trust config.

---

# 17. TASK 14 — response schema consistency

Bridge має повертати стабільну schema.

Бажана верхня структура:

```json
{
  "status": "online",
  "updatedAt": "2026-09-15T20:00:00+00:00",
  "codex": {},
  "antigravity": {},
  "message": null,
  "staleReason": null
}
```

## Правила

- `status=online` — свіже завантаження успішне;
- `status=stale` — network/provider failure, але є cached snapshot;
- `status=offline` — provider недоступний і cache відсутній;
- `updatedAt` — timestamp snapshot, а не UI render time.

Не використовувати synthetic 100% як fallback.

---

# 18. TASK 15 — cache semantics

Поточний cache TTL = 10 sec.

Це нормально для interactive UI, але треба розділити:

- in-memory bridge cache;
- Android persistent last-known snapshot.

## Bridge

При provider failure:

- якщо є старий cache — повернути його як `stale`;
- додати `staleReason`;
- не робити вигляд, що snapshot fresh.

Після account switch:

```python
invalidate_cache()
```

Потім перший наступний refresh має примусово звернутися до provider.

## Android

Збережений snapshot дозволено показувати offline, але UI має явно відображати, що дані cached/stale.

---

# 19. TASK 16 — account switch safety

Після switch:

1. POST у Antigravity Tools;
2. чекати response;
3. invalidate bridge cache;
4. повторно fetch accounts;
5. переконатися, що requested account став `isCurrent=true`;
6. лише після цього повернути success Android app.

Не вважати HTTP 200 від локального tools proxy достатньою гарантією, якщо стан не змінився.

При timeout:

```json
{
  "success": false,
  "error": "switch_verification_timeout"
}
```

Android має показати Toast/Snackbar, а не мовчки ігнорувати failure.

---

# 20. TASK 17 — UI feedback

## Refresh

Після ручного refresh:

- spinner;
- success не потребує Toast;
- failure → Snackbar `Could not refresh quotas`.

## Account switch

Поки switch виконується:

- disable повторні switch натискання;
- показати progress біля target account;
- після failure показати error;
- після success оновити список.

Не допускати кількох паралельних switch POST.

---

# 21. TASK 18 — build verification

Після змін обов'язково виконати:

## Android

Windows:

```powershell
cd android
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug
```

Linux/macOS:

```bash
cd android
./gradlew test
./gradlew :app:assembleDebug
```

## Python

```bash
python -m unittest discover -s bridge/tests -v
```

## Syntax

```bash
python -m py_compile bridge/*.py bridge/providers/*.py
```

Не комітити build output.

---

# 22. TASK 19 — manual end-to-end checklist

Перед фінальним commit перевірити вручну.

## Case A — bridge offline

- app запускається;
- не crash;
- показує offline/cached state;
- widget не показує fake `100%`.

## Case B — wrong API token

- app отримує 401;
- UI повідомляє authentication error;
- switch недоступний.

## Case C — correct API token

- quota завантажуються;
- account list завантажується;
- widget оновлюється.

## Case D — switch account

- натиснути Switch;
- ПК реально перемикає account;
- app після refresh показує новий `isCurrent`;
- quota відповідає новому account.

## Case E — network loss

- вимкнути Wi-Fi/Tailscale;
- app не crash;
- показує cached data як stale;
- після відновлення мережі refresh повертається online.

---

# 23. Commit strategy

Не робити один гігантський commit.

Рекомендовані commits:

```text
security: add bearer authentication to bridge API
security: add API token support to Android client
fix: remove hardcoded bridge address
fix: represent unavailable quota as unknown
fix: remove duplicate refresh requests
fix: update widget only after sync completes
security: harden dashboard CORS and escaping
refactor: split bridge into provider modules
test: add bridge and Android unit tests
docs: synchronize README with actual behavior
```

Після кожного логічного commit запускати релевантні tests.

---

# 24. Заборонено

Не робити наступне без окремої задачі:

- не комітити Google/OpenAI access tokens;
- не комітити `auth.json`;
- не комітити Antigravity proxy API key;
- не комітити реальний `WIDGEN_API_TOKEN`;
- не відкривати bridge у публічний Internet;
- не додавати cloud backend;
- не додавати Firebase;
- не переписувати app на інший framework;
- не міняти Kotlin/Compose stack;
- не додавати Direct OAuth як побічний refactor;
- не замінювати LAN architecture на HTTPS/self-signed certificates без окремого рішення;
- не видаляти WorkManager — він потрібен для background sync;
- не робити UI redesign, поки не завершені correctness/security задачі.

---

# 25. Definition of Done

Робота завершена, коли одночасно виконані всі умови:

- Bridge API захищений bearer token.
- Switch без token неможливий.
- Quota без token недоступні.
- У repo немає персонального Tailscale IP.
- Unknown data не показуються як `100%`.
- Manual refresh не виконує подвійний network fetch.
- Widget оновлюється після завершення sync.
- CORS не wildcard для приватного API.
- Dashboard strings escaped.
- Account switch verify-иться після виконання.
- Android показує помилки refresh/switch.
- Python tests проходять.
- Android unit tests проходять.
- Android debug APK збирається.
- README відповідає фактичним можливостям коду.
- Direct OAuth позначений як planned, якщо він реально ще не реалізований.

---

# 26. Інструкція Gemini

Працюй автономно по цьому документу.

Перед кожним task:

1. відкрий конкретні файли, яких він стосується;
2. не роби unrelated refactor;
3. спочатку додай/онови test там, де це практично;
4. внеси мінімальні зміни;
5. запусти релевантну перевірку;
6. тільки після PASS переходь далі.

Якщо існуючий код суперечить цьому документу — пріоритет має безпека, коректність quota та backward compatibility Android ↔ Bridge.

Якщо потрібно змінити JSON schema — спочатку одночасно підготуй Bridge і Android model, щоб не залишати repository у стані, де одна сторона вже оновлена, а друга ні.

Не вважай задачу завершеною лише тому, що код компілюється. Перевіряй runtime semantics: 401, offline, stale cache, account switching та widget refresh.
