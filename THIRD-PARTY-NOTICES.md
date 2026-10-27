# 第三方元件授權

本專案（OmniADB-Client）以 MIT 授權釋出，見 `LICENSE`。
下列元件各自採用不同授權，使用或再散布本專案時請一併遵守。

## libadb-android

- 專案：<https://github.com/MuntashirAkon/libadb-android>
- 授權：**GPL-3.0-or-later 或 Apache-2.0（雙授權，可擇一）**
- 用途：ADB 協定 client 端實作（TLS 握手、SPAKE2 配對、stream 多工）
- 本專案選擇 **Apache-2.0** 分支，因此不觸發 GPL 的 copyleft 條件。
- 上游原始授權檔保留於 `LICENSES/libadb-COPYING`。

## scrcpy

- 專案：<https://github.com/Genymobile/scrcpy>
- 授權：**Apache-2.0**
- 用途：`app/src/main/assets/scrcpy-server-4.1.jar`（v4.1 官方 release 檔，未修改）
- 客戶端（影像解碼、控制協定）為本專案自行實作。

## Conscrypt

- 專案：<https://github.com/google/conscrypt>
- 授權：**Apache-2.0**
- 用途：Android 上提供 TLS 1.3 / 現代憑證套件。

## Bouncy Castle

- 專案：<https://www.bouncycastle.org/>
- 授權：**MIT 風格（Bouncy Castle License）**
- 用途：SPAKE2 配對所需的橢圓曲線與金鑰編碼。

## AndroidX

- 專案：<https://developer.android.com/jetpack/androidx>
- 授權：**Apache-2.0**
- 用途：`androidx.core`（FileProvider）、`androidx.annotation`。

## OmniADB-Host

- 專案：<https://github.com/tokyoxpa3/OmniADB-Host>
- 授權：**MIT**
- 用途：`app/src/main/assets/OmniADB-Host.apk` 由該專案建置，控制端負責推送安裝。
