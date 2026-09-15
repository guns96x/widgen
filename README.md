# Antigravity Limits (Widgen)

Мобільний Android-додаток та віджет на робочий стіл для моніторингу квот, лімітів та таймерів скидання акаунтів **Google Antigravity** у реальному часі (аналог «Codex Limits»).

---

## 📱 Можливості

- **Home Screen Widget (Jetpack Glance)**:
  - Компактний або розширений віджет 4x2 / 2x2.
  - Лінійні прогрес-бари для **Gemini Pool** (Flash / Pro) та **Claude / GPT Pool** (Sonnet, Opus, OSS).
  - Таймер зворотного відліку до оновлення сесії/тижневого ліміту («Скидання о 16:58» / «через 4 год 58 хв»).
  - Кнопка миттєвого оновлення прямо з віджета.
  - Індикатор підключення та акаунта.
- **Android App UI (Jetpack Compose & Material 3 Neobank)**:
  - Темний висококонтрастний дизайн у стилі Monobank/Revolut.
  - Edge-to-edge відступи під системні панелі (status & navigation bars).
  - Детальний список по кожній окремій моделі (14+ моделей Antigravity).
  - Підтримка швидкого налаштування IP-адреси ПК/моста або Tailscale.
- **PC Quota Bridge (Python)**:
  - Автоматично виявляє локальний процес `language_server.exe` Antigravity.
  - Зчитує CSRF-токен та порт, робить запити за протоколом Connect/gRPC-Web.
  - Нормалізує квоти без витоку приватних OAuth-токенів.
  - Вбудований мобільний веб-дашборд на `http://localhost:59123/`.

---

## 🚀 Швидкий старт

### 1. Запуск моста на комп'ютері (PC Bridge)

У папці `bridge/`:
```bat
run_bridge.bat
```
Або у фоні через PowerShell:
```powershell
.\run_bridge.ps1 -Background
```
Міст буде доступний за адресою:
`http://localhost:59123/` (веб-інтерфейс) або `http://<IP-вашого-ПК>:59123/api/quota` (REST API для віджета).

### 2. Встановлення додатка на Android

- Готовий APK знаходиться у корені проєкту: `AntigravityLimits.apk` (також скопійовано на Робочий стіл: `C:\Users\pavlo\Desktop\AntigravityLimits.apk`).
- Для встановлення через USB:
  ```sh
  adb install -r AntigravityLimits.apk
  ```
  Або скопіюйте файл на смартфон через Telegram/Провідник та встановіть.

### 3. Налаштування віджета на смартфоні

1. Відкрийте встановлений додаток **Antigravity Limits**.
2. Натисніть значок налаштувань ⚙️ у правому верхньому кутку.
3. Введіть локальну IP-адресу вашого комп'ютера у домашній мережі або IP Tailscale (наприклад, `http://192.168.1.105:59123`).
4. Натисніть **Save & Connect** та кнопку оновлення.
5. Затисніть вільне місце на робочому столі смартфона -> **Віджети** -> знайдіть **Antigravity Limits** і перетягніть на екран.

---

## 🛠 Збірка проєкту з вихідного коду

```sh
cd android
./gradlew :app:assembleDebug
```
Зібраний APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
