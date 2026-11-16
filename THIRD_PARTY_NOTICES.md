# Сторонние компоненты

| Компонент | Версия | Лицензия | Где | Как используется |
|---|---|---|---|---|
| UxPlay (`lib/`) | FDH2/UxPlay, коммит в `airplay/src/main/cpp/third_party/uxplay/UPSTREAM` | GPL-3.0 (отдельные файлы LGPL-2.1) | `third_party/uxplay` | Протокол AirPlay: RTSP/HTTP, сопряжение, FairPlay SAP (`playfair`), RTP, зеркалирование, HLS |
| llhttp | в составе UxPlay | MIT | `third_party/uxplay/llhttp` | Разбор HTTP/RTSP |
| playfair | в составе UxPlay | GPL-3.0 | `third_party/uxplay/playfair` | FairPlay SAP-рукопожатие (реверс-инжиниринг) |
| libplist | 2.6.0 | LGPL-2.1 | `third_party/libplist` | Binary plist |
| Apple ALAC | macosforge/alac | Apache-2.0 | `third_party/alac` | Декодирование ALAC (только декодер) |
| OpenSSL | 3.5.9 LTS | Apache-2.0 | собирается `scripts/build-openssl-android.sh` | Криптография UxPlay (статический libcrypto) |
| JmDNS | 3.5.12 | Apache-2.0 | Maven | mDNS/Bonjour-анонс |
| AndroidX (Compose, Media3, DataStore, Lifecycle) | см. `gradle/libs.versions.toml` | Apache-2.0 | Maven | UI, воспроизведение HLS, настройки |
| Kotlin Coroutines | 1.9.0 | Apache-2.0 | Maven | Асинхронность |

Все лицензии совместимы с GPL-3.0, под которой распространяется приложение.
Исходный код приложения и изменённых компонентов прилагается к каждой распространяемой сборке.

## Изменения в UxPlay
Все изменения помечены комментарием `AIRPLAYTV:`.
- `httpd.c` / `httpd.h`: хук `airplaytv_accept_filter`, вызываемый после `accept()` (фильтр локальной сети до разбора данных).
- `raop.c`: отклонение запросов с неизвестной или пустой версией протокола (раньше abort); корректно
  завершённый ответ 431 (раньше abort); отказ 400 при отсутствующем или чужом `X-Apple-Session-ID`
  (раньше SIGSEGV или abort).
- `raop_handlers.h`: `/pair-pin-start` и `/pair-setup-pin` отвечают 403, если PIN-режим выключен.
- `http_response.c`: `http_response_add_data` игнорирует пустые данные вместо `assert`.
- Бэкенд Bonjour `lib/dns_sd`/`lib/mdnsd` заменён на `dnssd_android.c`: TXT-записи формируются
  с теми же ключами, а публикует их Kotlin.

## Изменения в Apple ALAC
Помечены `AIRPLAYTV:`. Найдены по падениям на реальном ТВ и проверены фаззингом с AddressSanitizer
(300 000 случайных пакетов без ошибок памяти):
- `ALACDecoder.cpp`: число сэмплов из потока ограничено `frameLength` (раньше — переполнение кучи при записи на испорченных данных).
- `ag_dec.c`: предел чтения `maxPos` считается от текущей позиции, а не от начала пакета (раньше — чтение далеко за концом буфера).
- Сборка: `TARGET_RT_LITTLE_ENDIAN=1` (код Apple считал ARM big-endian) и `-fwrapv`.
- `alac_bridge.cpp` декодирует из копии пакета с нулевым запасом после данных.

Исправления падений найдены фаззингом (`scripts/security_probe.py`, `scripts/fuzz_rtsp.py`) и подходят для отправки в upstream.
