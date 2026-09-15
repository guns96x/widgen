# AI Limits — Antigravity & OpenAI Codex (Widgen)

Мобільний Android-додаток та віджет на робочий стіл для об'єднаного моніторингу квот, лімітів та таймерів скидання для **OpenAI Codex** та акаунтів **Google Antigravity** у реальному часі з можливістю віддаленого перемикання активного акаунта на ПК.

---

## 📱 Можливості

### 1. Єдиний Home Screen Widget (Jetpack Glance)
- **OpenAI Codex (ліва колонка)**:
  - 5-годинне плаваюче вікно запитів (Session Window) з прогрес-баром і таймером скидання.
  - Тижневий ліміт (Weekly Window) з кольоровою градацією залишку.
- **Google Antigravity (права колонка)**:
  - Gemini Pool (Flash / Pro).
  - Claude & GPT Pool (Sonnet 4.6, Opus 4.6, GPT-OSS).
  - Індикатор підключеного акаунта та кнопка миттєвого оновлення `↻`.
- **Чесні Offline / Unknown стани**: відсутність даних відображається як `—` / `Offline`, без синтетичних 100%.

### 2. Android App (Jetpack Compose, Material 3 Neobank)
- Темний висококонтрастний дизайн у стилі Monobank/Revolut з підтримкою edge-to-edge.
- **OpenAI Codex Hero Card**: статус плану (Plus/Pro), поточний залишок та графік скидання.
- **Antigravity Multi-Account Switcher**: відображення списку всіх зареєстрованих акаунтів на ПК, їхні індивідуальні квоти та кнопка **Switch** для перемикання активного акаунта в IDE на комп'ютері прямо з телефона.
- **Детальний розклад моделей**: розгортання квот для кожної з 30+ моделей (Gemini 3.8/3.7/3.1, Claude Sonnet/Opus 4.6, GPT-OSS).
- **Безпечні налаштування**: вбудований валідатор URL (`BridgeUrlValidator`) та маскування API Bearer токена.

### 3. Архітектура: PC Bridge vs Direct OAuth
- **Режим PC Bridge (поточний стабільний режим)**:
  - Підключається через Tailscale або домашній Wi-Fi до локального моста (порт 59123).
  - Захищений спільним Bearer-токеном (`WIDGEN_API_TOKEN`).
  - Дозволяє не лише переглядати квоти, але й **перемикати активний акаунт для IDE прямо зі смартфона**.
- **Direct OAuth (планується / експериментальний)**:
  - Автономний direct-запит без ПК знаходиться в планах розвитку (`Planned / experimental`).

---

## 🔒 Безпека (API Bearer Auth)

PC Bridge захищений Bearer-токеном. Усі ендпоінти (`/api/quota`, `/api/accounts`, `/api/accounts/switch`) вимагають валідного заголовка `Authorization: Bearer <token>`.

- Токен генерується автоматично при першому старті та зберігається у `~/.widgen/config.json`.
- Або можна встановити власний токен через змінну середовища:
  ```powershell
  $env:WIDGEN_API_TOKEN="ваш_довгий_випадковий_секрет"
  ```

---

## 🚀 Швидкий старт

### 1. Запуск моста на комп'ютері (PC Bridge)

Міст автоматично підхоплює акаунти з `Antigravity Tools` (порт 8045) та токени `OpenAI Codex` з `~/.codex/auth.json`.

У корені проєкту або папці `bridge/`:
```powershell
.\bridge\run_bridge.ps1 -Background
```
Міст доступний за адресою:
- Локальний веб-дашборд: `http://localhost:59123/`
- REST API для смартфона: `http://<PC-LAN-АБО-TAILSCALE-IP>:59123/api/quota`

При запуску міст виведе маскований токен. Повний токен знаходиться у файлі `%USERPROFILE%\.widgen\config.json`.

### 2. Встановлення додатка на Android

- Готовий APK: `AntigravityLimits.apk` у корені репозиторію.
- Встановлення через ADB:
  ```powershell
  adb install -r AntigravityLimits.apk
  ```
- Або встановіть APK безпосередньо на телефон через файловий менеджер.

### 3. Підключення смартфона до моста

1. Відкрийте додаток **AI Limits** на телефоні.
2. Якщо міст ще не налаштовано, натисніть **⚙️ Configure PC Bridge** або іконку налаштувань у правому верхньому куті.
3. Введіть адресу моста: `http://<PC-LAN-АБО-TAILSCALE-IP>:59123`.
4. Введіть **API Token** із файлу `~/.widgen/config.json`.
5. Натисніть **Save & Connect**.

---

## 🧪 Тестування та збірка

### Тести моста (Python)
```bash
python -m unittest discover -s bridge/tests -v
```

### Тести Android
```bash
cd android
./gradlew test
```

### Збірка APK
```bash
cd android
./gradlew :app:assembleDebug
```
Зібраний APK автоматично розташовується за адресою `android/app/build/outputs/apk/debug/app-debug.apk`.
