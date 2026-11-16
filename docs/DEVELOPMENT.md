# Разработка и релизы

## Сборка локально
Нужно: JDK 17, Android SDK 35, NDK `27.2.12479018`, CMake `3.22.1`.

1. Один раз соберите статический OpenSSL 3.5 LTS для всех ABI. Скрипт сам скачивает исходники
   и проверяет SHA-256:
   ```bash
   ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/27.2.12479018 scripts/build-openssl-android.sh
   ```
2. Отладочная сборка и тесты:
   ```bash
   ./gradlew testDebugUnitTest :app:assembleDebug
   ```
3. Release (подписывается, если существует `keystore/keystore.properties`):
   ```bash
   ./gradlew :app:assembleRelease
   ```

APK появляются в `app/build/outputs/apk/<debug|release>/`: отдельный файл под каждый ABI и `universal`.

Формат `keystore/keystore.properties` (папка `keystore/` в git не попадает):
```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=airplaytv
keyPassword=...
```

## Сборка на GitHub

В репозитории два workflow:

| Workflow | Когда запускается | Что делает |
|---|---|---|
| `CI` (`.github/workflows/ci.yml`) | push в `main`, pull request, вручную | unit-тесты и debug APK (артефакт хранится 14 дней) |
| `Release` (`.github/workflows/release.yml`) | push тега `v1.2.3` | тесты, подписанные release APK, проверка подписи, SHA256SUMS, публикация на странице Releases |

Сборка OpenSSL занимает около 10–15 минут и выполняется только при первом запуске или после изменения
`scripts/build-openssl-android.sh`. Дальше результат берётся из кеша Actions.

### Однократная настройка подписи
Релизы обязательно подписываются, иначе Android не установит обновление поверх старой версии.
Добавьте в **Settings → Secrets and variables → Actions** четыре секрета:

| Секрет | Значение |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | файл `keystore/release.jks` в base64 |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword` из `keystore/keystore.properties` |
| `ANDROID_KEY_ALIAS` | `keyAlias` (`airplaytv`) |
| `ANDROID_KEY_PASSWORD` | `keyPassword` |

Удобнее всего через [GitHub CLI](https://cli.github.com) из корня проекта:
```bash
base64 -i keystore/release.jks | gh secret set ANDROID_KEYSTORE_BASE64
```
```bash
grep storePassword keystore/keystore.properties | cut -d= -f2- | gh secret set ANDROID_KEYSTORE_PASSWORD
```
```bash
grep keyAlias keystore/keystore.properties | cut -d= -f2- | gh secret set ANDROID_KEY_ALIAS
```
```bash
grep keyPassword keystore/keystore.properties | cut -d= -f2- | gh secret set ANDROID_KEY_PASSWORD
```

> ⚠️ Храните `keystore/release.jks` и пароли в надёжном месте, например в менеджере паролей.
> Если ключ потерян, пользователям придётся удалить приложение и поставить его заново.

### Выпуск новой версии
```bash
git tag v1.1.0
```
```bash
git push origin v1.1.0
```
Через 10–20 минут на странице **Releases** появится версия `1.1.0` с файлами
`AirPlayReceiver-universal.apk`, `-armeabi-v7a`, `-arm64-v8a`, `-x86`, `-x86_64` и `SHA256SUMS.txt`.
Номер версии берётся из тега: `v1.2.3` → `versionName 1.2.3`, `versionCode 10203`. Тег обязательно
должен иметь вид `vX.Y.Z`, а каждая следующая версия — быть больше предыдущей.

## Обновления в приложении
Приложение проверяет `releases/latest` репозитория из `BuildConfig.UPDATE_REPO`. В CI это
`GITHUB_REPOSITORY`, локально `-PupdateRepo=owner/name`, по умолчанию `AKondrackii/airplay-android-tv`.
Ожидаемый формат релиза создаёт workflow `Release`: тег `vX.Y.Z`, файлы `AirPlayReceiver-<abi>.apk`
и `SHA256SUMS.txt`. Черновики и pre-release игнорируются. Обновление предлагается, только если versionCode
из тега больше установленного, а APK подписан тем же ключом.

## Тесты на эмуляторе
См. [TESTING.md](TESTING.md): там описан AVD Android TV, `scripts/security_probe.py` и `scripts/fuzz_rtsp.py`.
