# Сборка

## Что нужно

- JDK 17;
- Android SDK (platform 34, build-tools 34.0.0);
- Gradle 8.7.

## Debug

```bat
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Release и подпись

Ключ и `keystore.properties` в репозиторий **не входят**. Для подписанной сборки создайте
`keystore.properties` в корне проекта:

```properties
storeFile=keystore/byedpi-autostart.jks
storePassword=...
keyAlias=...
keyPassword=...
```

и положите сам `.jks` в `keystore/`. Оба пути уже в `.gitignore`.

Без `keystore.properties` release-сборка собирается без подписи.
