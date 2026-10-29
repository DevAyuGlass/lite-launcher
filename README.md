# Lite Launcher

Минималистичный лаунчер для Android 10+ (MIUI / HyperOS и др.): без сторонних библиотек,
без иконок и анимаций, чёрный фон, избранное и поиск по приложениям.

## Сборка
- **GitHub Actions:** любой push в `main` собирает APK. Скачать: вкладка *Actions* -> нужный запуск -> *Artifacts*.
- **Release:** создай тег вида `v0.1` - APK появится во вкладке *Releases*.
- **Локально:** JDK 17 + Android SDK, затем `gradle assembleRelease`.

## Подпись
Без настройки APK подписывается debug-ключом (он меняется при каждой сборке, обновлять поверх нельзя - только удалить и поставить заново).
Чтобы обновления ставились поверх, добавь свой ключ в *Settings -> Secrets and variables -> Actions*:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
