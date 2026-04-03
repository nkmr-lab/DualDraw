# RawInput 汎用ライブラリ — 引き継ぎ資料

## 1. 背景と目的

Wacom MovinkPad (Android 15) では EMRペンとタッチが物理的に同時入力可能だが、
Android の InputDispatcher が **"stylus suppresses touch"** ポリシーで一方を ACTION_CANCEL してしまう。

DualDraw プロジェクトでは、Linux カーネルの `/dev/input/event*` を直接読み取ることで
Android の入力システムを完全にバイパスし、同時入力を実現した。

**新プロジェクトの目的：** この仕組みをドローアプリ固有のコードから分離し、
任意の Android アプリが複数入力デバイスを同時に扱える汎用ライブラリとして再実装する。

---

## 2. 実証済みアーキテクチャ

```
┌─ Android Device ───────────────────────────────────────────┐
│                                                            │
│  /dev/input/event3 (touch)   /dev/input/event6 (EMR pen)  │
│         │                           │                      │
│    [adb exec-out cat ...]     [adb exec-out cat ...]       │
│         │                           │                      │
│    ─── USB/adb トンネル ─────────────────────────────────  │
│         │                           │                      │
│    [adb reverse tcp:9003]     [adb reverse tcp:9006]       │
│         │                           │                      │
│         ▼                           ▼                      │
│   RawInputBridge ──────────────────────────────────────    │
│   (TCP client, localhost:PORT に接続)                      │
│         │                                                  │
│         ▼                                                  │
│   アプリのコールバック                                       │
└────────────────────────────────────────────────────────────┘
         ▲                           ▲
         │         USB 接続          │
┌─ PC ──────────────────────────────────────────────────────┐
│   relay.py                                                │
│   ├─ adb reverse tcp:PORT tcp:PORT  (セットアップ)         │
│   ├─ TCP サーバー (localhost:PORT) で待機                  │
│   └─ adb exec-out cat /dev/input/eventN のデータを転送    │
└───────────────────────────────────────────────────────────┘
```

### なぜこの構成になったか

| 試したこと | 結果 | 理由 |
|-----------|------|------|
| アプリ内 ServerSocket → shell の nc が接続 | ECONNREFUSED | SELinux が shell → untrusted_app のTCP接続をブロック |
| shell の nc -l → アプリが接続 | ECONNREFUSED | nc が即死（/dev/input の読み取り権限 or SELinux） |
| nc -l で IPv4/IPv6 | ECONNREFUSED | Android の nc は IPv6 バインド、アプリは IPv4 接続でミスマッチ |
| **adb reverse + PC relay** | **成功** | adbd 経由なので SELinux 制約を回避 |

---

## 3. 技術的知見（ハマりポイント）

### 3.1 SELinux (Enforcing)

- production ビルドでは `adb root` 不可
- `chmod /dev/input/event*` も不可（Permission denied）
- `untrusted_app` ドメインはネットワークソケットの相手が制限される
- **唯一の回避策：** adbd を経由するトンネル（adb reverse / adb exec-out）

### 3.2 /dev/input のパーミッション

```
crw-rw---- root input /dev/input/event3
crw-rw---- root input /dev/input/event6
```

- `shell` ユーザーは `input` グループに所属 → 読み取り可能
- アプリ（untrusted_app）は所属していない → 直接読み取り不可
- `adb exec-out cat /dev/input/eventN` は shell 権限で実行されるので OK

### 3.3 input_event 構造体 (64-bit Linux)

```c
struct input_event {
    __s64  tv_sec;    //  8 bytes  (64-bit カーネルでは time_t が 64-bit)
    __s64  tv_usec;   //  8 bytes
    __u16  type;      //  2 bytes
    __u16  code;      //  2 bytes
    __s32  value;     //  4 bytes
};                    // 計 24 bytes
```

**重要：** 32-bit カーネルでは timeval が 8 バイト（4+4）で EVENT_SIZE = 16 になる。
MovinkPad は 64-bit なので 24 バイト。汎用化時はカーネルのビット幅を検出する仕組みが必要。

### 3.4 Multi-Touch Protocol B (タッチスクリーン)

```
EV_ABS ABS_MT_SLOT        → 操作対象のスロット切り替え（指の識別）
EV_ABS ABS_MT_TRACKING_ID → スロットに指を割り当て（-1 = リフト）
EV_ABS ABS_MT_POSITION_X  → X 座標
EV_ABS ABS_MT_POSITION_Y  → Y 座標
EV_ABS ABS_MT_PRESSURE    → 筆圧
EV_SYN SYN_REPORT         → フレーム区切り（ここでまとめて処理）
```

- `ABS_MT_TRACKING_ID = -1` で指が離れたことを示す
- `prevId` と `trackingId` の遷移で begin/move/end を判定

### 3.5 シングルタッチプロトコル (EMR ペン)

```
EV_ABS ABS_X        → X 座標
EV_ABS ABS_Y        → Y 座標
EV_ABS ABS_PRESSURE → 筆圧
EV_KEY BTN_TOUCH    → 1=down, 0=up
EV_SYN SYN_REPORT   → フレーム区切り
```

### 3.6 座標変換

raw 座標はデバイスの **物理パネル座標（portrait 基準）** で来る。
Android が landscape モードの場合、回転変換が必要。

```
MovinkPad event3 (タッチ):  X 0-1440,  Y 0-2200
MovinkPad event6 (EMRペン): X 0-15928, Y 0-24334
```

**landscape への変換（USE_ROTATION_90 = false の場合）：**
```
viewX = rawY / Y_MAX * screenWidth  - viewOffsetX
viewY = (1 - rawX / X_MAX) * screenHeight - viewOffsetY
```

**注意：** raw 座標はスクリーン全体に対応するので、View がスクリーンより小さい場合は
`view.getLocationOnScreen()` でオフセットを引く必要がある。
（最初 `view.width` で計算して X がずれるバグがあった）

### 3.7 レイテンシ

`adb exec-out` → PC → `adb reverse` と USB を 2 往復するため、
直接入力より数十ms の遅延がある。

軽減策：
- `bufsize=0` で Popen を起動
- `read(24)` で 1 イベントずつ即座に転送（4096 バイトバッファだと遅い）
- USB 3.0 接続推奨

### 3.8 Windows 環境の注意

- `adb` に PATH が通っていないことが多い。フルパスが必要：
  `%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- `JAVA_HOME` 未設定の場合は Android Studio の JBR を使う：
  `C:\Program Files\Android\Android Studio\jbr`
- Windows Store 版の `python` はダミー。`py` ランチャーを使うこと
- PowerShell では `& "path\to\adb.exe"` のように `&` 演算子が必要

---

## 4. 汎用ライブラリの設計案

### 4.1 モジュール構成

```
RawInputLib/
├── rawinput-android/           ← Android ライブラリ (AAR)
│   └── RawInputReceiver.kt    ← TCP 接続・イベントパース・コールバック
├── rawinput-relay/             ← PC 側ツール
│   └── relay.py                ← デバイス設定ファイル対応の汎用リレー
└── rawinput-sample/            ← サンプルアプリ
```

### 4.2 Android ライブラリ API 案

```kotlin
// デバイス定義
data class InputDeviceConfig(
    val name: String,           // "touch", "emr_stylus" など
    val port: Int,              // TCP ポート番号
    val protocol: Protocol,     // MULTI_TOUCH_B, SINGLE_TOUCH, AUTO
    val xMax: Float,            // raw X の最大値
    val yMax: Float,            // raw Y の最大値
    val pressureMax: Float,     // raw pressure の最大値
)

enum class Protocol { MULTI_TOUCH_B, SINGLE_TOUCH, AUTO }

// イベント
data class RawPointerEvent(
    val device: String,         // デバイス名
    val pointerId: Int,         // スロット番号 or 0
    val action: Action,         // BEGIN, MOVE, END
    val x: Float,               // 正規化座標 0.0-1.0
    val y: Float,
    val pressure: Float,        // 0.0-1.0
)

enum class Action { BEGIN, MOVE, END }

// メインクラス
class RawInputReceiver(
    private val devices: List<InputDeviceConfig>,
) {
    var onEvent: ((RawPointerEvent) -> Unit)? = null
    var onStatusChanged: ((deviceName: String, connected: Boolean) -> Unit)? = null

    fun start() { ... }
    fun stop() { ... }
}
```

**ポイント：**
- 座標は 0.0-1.0 の正規化値で返し、画面座標への変換はアプリ側に任せる
- デバイス定義を外部化し、MovinkPad 以外のデバイスにも対応
- Protocol.AUTO は最初のイベントから自動判定（ABS_MT_SLOT が来れば Protocol B）

### 4.3 relay.py 汎用化案

```python
# config.json
{
  "devices": [
    {"name": "touch",      "port": 9003, "path": "/dev/input/event3"},
    {"name": "emr_stylus", "port": 9006, "path": "/dev/input/event6"}
  ]
}
```

```bash
py relay.py                    # config.json を自動読み込み
py relay.py --config my.json   # 別の設定ファイルを指定
py relay.py --discover         # getevent -p でデバイス一覧を表示
```

### 4.4 デバイス自動検出

`adb exec-out getevent -p` で `/dev/input/event*` の一覧と各デバイスの
capabilities (ABS_MT_POSITION_X の有無など) を取得し、
タッチパネル / ペン / ボタン等を自動分類できる。

---

## 5. 既存コード参照先

GitHub: https://github.com/nkmr-lab/DualDraw

| ファイル | 役割 | 汎用化時の参考箇所 |
|---------|------|-------------------|
| `RawInputBridge.kt` | イベントパース＋座標変換 | processTouch(), processStylus(), input_event 定数 |
| `DualDrawView.kt` | 描画（アプリ固有） | rawBegin/rawMove/rawEnd のインターフェース |
| `MainActivity.kt` | Bridge 管理、通常入力の抑制 | bridge.touchConnected による分岐 |
| `relay.py` | PC 側リレー | adb reverse + exec-out パターン |

---

## 6. 新セッションへの指示テンプレート

以下を新しい Claude Code セッションに貼り付けて開始してください：

```
HANDOFF.md を読んで、Android の /dev/input を直接読み取る汎用入力ライブラリを
新規プロジェクトとして作りたい。

参考実装: https://github.com/nkmr-lab/DualDraw
引き継ぎ資料: C:\Users\nakamura\DualDraw_project\HANDOFF.md

やりたいこと:
1. Android ライブラリ (AAR) として RawInputReceiver を実装
   - デバイス定義を設定で受け取り、TCP 接続・イベントパースを行う
   - 正規化座標 (0-1) でコールバックを返す
   - Multi-Touch Protocol B とシングルタッチの両方に対応
2. relay.py を汎用化（config.json 対応、--discover オプション）
3. サンプルアプリ（シンプルなタッチ可視化）

対象デバイス: Wacom MovinkPad (Android 15)
PC: Windows 11, Python 3.x
ビルド環境: AGP 8.3.0, Kotlin 1.9.23, Gradle 9.0.0
```
