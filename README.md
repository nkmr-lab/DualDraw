# DualDraw — EMRペン＋タッチ同時描画アプリ (Wacom MovinkPad / Android 15)

EMRペン（Wacom）と指/タッチペンを**同時に**別チャンネルで描画できる Android アプリです。  
ペンは青系、タッチは赤系で描かれ、筆圧によってストローク幅が変化します。

---

## なぜこのアプローチが必要か

まずそもそもペンやタッチなどの情報は /dev/input/event* で取得することができます。
例えばevent3だとタッチの入力が、event6だとペンの入力がやってきます。
```PowerShell
adb shell getevent -t -l /dev/input/event3
adb shell getevent -t -l /dev/input/event6
```
この event3 および event6 は独立しているので、リアルタイムにそれぞれの情報がやってきます。
なお、複数のペンの場合は、排他的処理になっている模様で、どちらかの情報しか来ません。
同時に複数入力を取るには、event3とevent6を使う必要があるっぽいです。

で、ここまでは良いのだけれど、Android の InputDispatcher には **"stylus suppresses touch"** という仕様があり、MouseEventなどを使ってそれぞれの入力を取ろうとするものの、EMRペンが hover 状態になると、タッチ入力に `ACTION_CANCEL` が送られ、タッチの入力がやってこなくなります。つまり、同時描画ができません。これが厄介。

仕方ないので、/dev/input/event3 や event6 を直接監視したいのだけれど、今度はそちらへのアクセス権限がありません。
色々試してみたのだけれど、まず読み込み権限がなく、chmodで権限を変更することもできません。rootにもなれないので仕方がない。
ここらへんは、MovinkPadがしっかり守られているという意味なんだろうなと。

ということで、PC側でサーバ（relay.py）を立てて、Androidの /dev/input/event3 および event6 をPC側のシェルで読み込み、MovinkPadからそのサーバに対して接続してデータを取得していくという方式を取ることにしました。
Android の入力システムを**完全にバイパス**し、Linux カーネルのデバイスファイル `/dev/input/event*` を直接読み取っているわけです。

### アーキテクチャ

```
/dev/input/event3  (タッチ)  ──┐
/dev/input/event6  (EMRペン) ──┤  adb exec-out  ──→  PC relay.py  ──→  adb reverse  ──→  RawInputBridge (アプリ)
                                └──────────────────────────────────────────────────────────────────────────────
```

- `adb exec-out` でデバイスのカーネルイベントを PC に転送
- PC 側の `relay.py` が TCP サーバーとして待機
- `adb reverse` でデバイス上の localhost ポートを PC にトンネル
- アプリの `RawInputBridge` が接続し、イベントを受信して描画

> **なぜ PC 経由？**  
> SELinux (Enforcing) がアプリ (`untrusted_app` ドメイン) から  
> shell の TCP ソケットへの直接接続をブロックするため、  
> `adbd` 経由のトンネルで回避しています。

---

## 動作環境

| 項目 | 内容 |
|------|------|
| デバイス | Wacom MovinkPad (Android 15) |
| PC | Windows 10/11 |
| Android Studio | Hedgehog 以降 |
| AGP | 8.3.0 |
| Kotlin | 1.9.23 |
| Gradle | 9.0.0 |
| Python | 3.x（`py` コマンドで起動できること） |

---

## ファイル構成

```
DualDraw_project/
├── relay.py                        ← PC 側中継スクリプト（毎回起動が必要）
└── DualDraw/
    ├── app/src/main/java/com/example/dualdraw/
    │   ├── MainActivity.kt         ← Activity・イベント横取り・Bridge 管理
    │   ├── DualDrawView.kt         ← 描画ビュー（ペン/タッチ独立レイヤー）
    │   └── RawInputBridge.kt       ← TCP でイベントを受信・座標変換
    ├── app/src/main/res/
    │   └── layout/activity_main.xml
    └── app/src/main/AndroidManifest.xml
```

---

## セットアップ手順

### 1. リポジトリをクローン（または ZIP 展開）

```powershell
git clone https://github.com/nkmr-lab/DualDraw.git
cd DualDraw
```

### 2. Android Studio でビルド

```powershell
cd DualDraw_project\DualDraw
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew installDebug
```

> `JAVA_HOME` は Android Studio の JBR を指定してください。

### 3. Python インストール（未インストールの場合）

[https://www.python.org/downloads/](https://www.python.org/downloads/) から Python 3.x をインストール。  
`python --version` で確認。

---

## 使い方（毎回の起動手順）

### 手順 1：デバイスを USB 接続し、adb を確認

```powershell
$adb = "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb devices
```

### 手順 2：relay.py を起動

```powershell
python C:\Users\nakamura\DualDraw_project\relay.py
```

以下のように表示されれば OK：

```
adb reverse tcp:9003 -> tcp:9003  OK
adb reverse tcp:9006 -> tcp:9006  OK
[9003] waiting (/dev/input/event3)
[9006] waiting (/dev/input/event6)
relay started. Ctrl+C to stop.
```

### 手順 3：アプリを起動

デバイス上のアイコンをタップ、または：

```powershell
& "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell am start -n com.example.dualdraw/.MainActivity
```

アプリ画面に `RAW touch=✓ stylus=✓` と表示されれば接続成功です。

### 手順 4：描画

- **EMRペン（Wacom）**：青系で描画
- **指 / タッチペン**：赤系で描画
- 両方同時に使用可能

> relay.py を止める場合は `Ctrl+C`

---

## PC なしで使う（Termux モード）

Termux を使えば MovinkPad 単体で動作できます。ワイヤレスデバッグで自分自身に adb 接続し、デバイス上で relay を実行します。localhost 通信なのでレイテンシもほぼゼロです。

### 初回セットアップ

1. **Termux を [F-Droid](https://f-droid.org/packages/com.termux/) からインストール**（Play Store 版は古いので非推奨）

2. Termux でパッケージをインストール：
   ```bash
   pkg update && pkg install android-tools python
   ```

3. 設定 → 開発者向けオプション → **ワイヤレスデバッグ** を ON

4. 開発者向けオプション → **子プロセスの制限を無効化** を ON（Android 14+）

5. **画面分割**で Termux とワイヤレスデバッグ設定画面を並べて表示

6. ワイヤレスデバッグ設定から「ペアリングコードによるデバイスのペアリング」をタップし、Termux で：
   ```bash
   adb pair 127.0.0.1:<ペアリングポート>
   # 表示された6桁コードを入力
   ```

7. `relay_termux.py` をデバイスに転送：
   ```powershell
   # PC から転送
   & $adb push C:\Users\nakamura\DualDraw_project\relay_termux.py /sdcard/relay_termux.py
   ```
   Termux 側でコピー：
   ```bash
   cp /sdcard/relay_termux.py ~/relay_termux.py
   ```

### 毎回の起動手順（Termux）

1. ワイヤレスデバッグ設定画面で **接続ポート** を確認（再起動のたびに変わる）

2. Termux で接続：
   ```bash
   adb connect 127.0.0.1:<接続ポート>
   ```

3. relay を起動：
   ```bash
   python ~/relay_termux.py
   ```

4. DualDraw アプリを起動 → `RAW touch=✓ stylus=✓` と表示されれば成功

---

## コード解説

### RawInputBridge.kt — カーネルイベント受信

TCP ソケット経由で Linux の `input_event` 構造体 (24 バイト) を受信します。

```
struct input_event {          // 64bit Linux
    __s64  tv_sec;            //  8 bytes
    __s64  tv_usec;           //  8 bytes
    __u16  type;              //  2 bytes
    __u16  code;              //  2 bytes
    __s32  value;             //  4 bytes
};                            // 計 24 bytes
```

**タッチ（event3）**: Multi-Touch Protocol B  
スロット番号 (`ABS_MT_SLOT`) と tracking ID (`ABS_MT_TRACKING_ID`) で指を識別します。

**EMRペン（event6）**: シングルタッチプロトコル  
`BTN_TOUCH` で pen down/up を検出、`ABS_X/Y/PRESSURE` で座標と筆圧を取得します。

**座標変換**（portrait → landscape）：

```kotlin
// USE_ROTATION_90 = false の場合
vx = rawY / Y_MAX * viewWidth
vy = (1f - rawX / X_MAX) * viewHeight
```

### DualDrawView.kt — 独立レイヤー描画

`DevicePointerKey(deviceId, pointerId)` をキーにストロークを管理。  
ペンレイヤーとタッチレイヤーを別々の `Canvas` / `Bitmap` に描画し、`onDraw` で重ねます。

筆圧に応じてストローク幅を線形補間：

```kotlin
strokeWidth = lerp(minWidth, maxWidth, pressure)
```

### relay.py — PC 側中継

```python
# adb reverse でデバイスの localhost:PORT を PC の PORT にトンネル
adb reverse tcp:9003 tcp:9003
adb reverse tcp:9006 tcp:9006

# adb exec-out でカーネルデバイスファイルをストリーム
proc = Popen([adb, "exec-out", "cat /dev/input/event3"], stdout=PIPE)

# PC の TCP サーバーに接続してきたアプリにデータを転送
conn.sendall(proc.stdout.read(24))
```

---

## トラブルシューティング

| 症状 | 対処 |
|------|------|
| `RAW touch=… stylus=…` のまま | relay.py が起動しているか確認 |
| relay.py で `adb reverse FAILED` | USB 接続・adb デバイス認識を確認 |
| 描画位置がずれる | `RawInputBridge.kt` の `USE_ROTATION_90` を変更してリビルド |
| 二重に描かれる | relay.py 起動前にアプリを再起動 |
| 遅延が大きい | USB 3.0 ポートに変更、または PC の性能を確認 |

---

## ライセンス

MIT
