# AI Limits — Antigravity & OpenAI Codex (Widgen)

Мобільний Android-додаток та віджет на робочий стіл для об'єднаного моніторингу квот, лімітів та таймерів скидання для **OpenAI Codex** та акаунтів **Google Antigravity** у реальному часі з можливістю віддаленого перемикання активного акаунта на ПК.

---

## 📱 Можливості

### 1. Єдиний Home Screen Widget (Jetpack Glance)
- **OpenAI Codex (ліва колонка)**:
  - 5-годинне плаваюче вікно запитів (Session Window) з прогрес-баром і таймером скидання.
  - Тижневий ліміт (Weekly Window) з підсвічуванням низького залишку.
- **Google Antigravity (права колонка)**:
  - Gemini Pool (Flash / Pro).
  - Claude & GPT Pool (Sonnet 4.6, Opus 4.6, GPT-OSS).
  - Індикатор підключеного акаунта та кнопка миттєвого оновлення `↻`.

### 2. Android App (Jetpack Compose, Material 3 Neobank)
- Темний висококонтрастний дизайн у стилі Monobank/Revolut з підтримкою edge-to-edge.
- **OpenAI Codex Hero Card**: статус плану (Plus/Pro), поточний залишок та графік скидання.
- **Antigravity Multi-Account Switcher**: відображення списку всіх зареєстрованих акаунтів на ПК (наприклад, `paul96x@gmail.com` та `guns96x@gmail.com`), їхні індивідуальні квоти та кнопка **Switch** для перемикання активного акаунта в IDE на комп'ютері прямо з телефона.
- **Детальний розклад моделей**: розгортання квот для кожної з 30+ моделей (Gemini 3.8/3.7/3.1, Claude Sonnet/Opus 4.6, GPT-OSS).

### 3. Автономність: ПК vs Direct OAuth
- **Режим PC Bridge (із комп'ютером)**:
  - Підключається через Tailscale/локальну мережу до локального моста (порт 59123).
  - Дозволяє не лише переглядати квоти, але й **перемикати активний акаунт для IDE прямо зі смартфона**.
- **Автономний режим без ПК (Direct Google/OpenAI OAuth)**:
  - Додаток може працювати автономно без увімкненого ПК:
    - Для **Antigravity**: прямий запит до внутрішнього ендпоінту `https://daily-cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels` з Google OAuth refresh_token повертає повну сітку квот 33 моделей безпосередньо від серверів Google.
    - Для **Codex**: прямий запит до `https://chatgpt.com/backend-api/codex/usage` через токен авторизації OpenAI.

---

## 🚀 Швидкий старт

### 1. Запуск моста на комп'ютері (PC Bridge)

Міст автоматично підхоплює акаунти з `Antigravity Tools` (порт 8045) та токени `OpenAI Codex` з `~/.codex/auth.json`.

У корені проєкту або папці `bridge/`:
```powershell
.\bridge\run_bridge.ps1 -Background
```
Міст доступний за адресою:
- Веб-дашборд: `http://localhost:59123/`
- REST API для смартфона: `http://100.82.252.86:59123/api/quota` (IP Tailscale).

### 2. Встановлення додатка на Android

- Готовий APK: `AntigravityLimits.apk` у корені репозиторію.
- Встановлення через ADB:
  ```powershell
  adb install -r AntigravityLimits.apk
  ```
- Або скопіюйте файл на телефон через Telegram/Quick Share та встановіть.

### 3. Додавання віджета на робочий стіл

1. Відкрийте додаток **AI Limits**.
2. За замовчуванням налаштовано Tailscale IP ПК (`http://100.82.252.86:59123`). За потреби змініть у ⚙️.
3. Додайте віджет **AI Limits** на головний екран смартфона.

---

## 🛠 Збірка з вихідного коду

```bash
cd android
./gradlew :app:assembleDebug
```
Зібраний APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
