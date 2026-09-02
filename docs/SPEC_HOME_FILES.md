# ホーム画面へのファイル/フォルダ配置 仕様書 v0.1（ドラフト）

作成日: 2026-09-02 / 対象: ohagi（現行 LayoutState v8 ベース）

## 1. 概要とゴール

macOS のデスクトップのように、ホームグリッドの任意のセルへ端末内の**ファイル**や**フォルダ**をピン留めできるようにする。置いたアイテムはアプリアイコンと同じ操作体系で扱う:

- **タップ** = 開く（ファイルは対応アプリ、フォルダはファイラー）
- **長押し → 動かさず離す** = メニュー
- **長押し → 動かす** = ドラッグ（並べ替え / 削除エリアでピン解除）

### 決定済みの方針
| 論点 | 決定 |
|---|---|
| 配置方式 | **個別ピン留め**（SAF ピッカーで 1 つずつ選ぶ）。フォルダ同期方式は将来拡張 |
| フォルダを開く | **dango 優先**、無ければシステムの Files（DocumentsUI） |
| アイコン | **サムネイルあり**（画像/動画/PDF）。他は種類別アイコン |
| スコープ | **ホームグリッドのみ**（ドック・ウィジェットページは対象外） |

## 2. Android の制約と技術アプローチ

### 2-1. ストレージアクセスは SAF 一択
- **ファイル選択**: `ACTION_OPEN_DOCUMENT`（`CATEGORY_OPENABLE`, `type="*/*"`)
- **フォルダ選択**: `ACTION_OPEN_DOCUMENT_TREE`
- 選択結果の URI に対し **`takePersistableUriPermission(READ)`** を取得して再起動後も参照可能にする。
- `MANAGE_EXTERNAL_STORAGE`（全ファイルアクセス権限）は**使わない**。Google Play の審査でランチャーは対象外カテゴリであり通らない。`java.io.File` による直接パスアクセスも行わない。
- 追加の manifest 権限は不要（SAF はインテント経由のため）。プライバシーポリシーの「ネットワーク通信なし・端末内完結」も維持される。

### 2-2. 永続 URI 許可の上限
- 永続化できる URI 許可はアプリあたり **Android 11+ で 512 件、Android 10 以前で 128 件**。
- ピン留め数の実質上限になる。**追加時に `contentResolver.persistedUriPermissions.size` を確認し、上限近く（例: minSdk26 を考慮し 120 件）で警告**する。
- ピン解除時は対応する `releasePersistableUriPermission` を呼ぶ（同じ URI を複数セルにピンしている場合は最後の 1 つを外すときのみ解放）。

### 2-3. 開く
- ファイル: `ACTION_VIEW` + `setDataAndType(uri, mime)` + `FLAG_GRANT_READ_URI_PERMISSION`。ハンドラ不在は `ActivityNotFoundException` → トースト（既存 `toast_launch_failed` と同系の文言）。
- フォルダ: 下記 4-3 参照（dango 連携）。

### 2-4. サムネイル
- API 29+: `ContentResolver.loadThumbnail(uri, size, null)`
- API 26–28: `DocumentsContract.getDocumentThumbnail(...)`
- 対象 MIME: `image/*`, `video/*`, `application/pdf`。失敗時は種類別アイコンへフォールバック。
- キャッシュ: `AppRepository` のアイコン LruCache と同じ発想の専用 LruCache（キー: uri 文字列、値: ImageBitmap）。IO ディスパッチャで読み、セル側は `produceState`（既存 `rememberAppIconBitmap` と同型の `rememberFileThumbnail` を新設）。

## 3. データモデル

### 3-1. HomeItem の拡張
```kotlin
@Serializable
sealed interface HomeItem {
    // 既存: HomeApp("app"), HomeFolder("folder") はそのまま

    /** SAF で選んだ単一ファイルへのピン。 */
    @Serializable
    @SerialName("file")
    data class HomeFile(
        val uri: String,           // ACTION_OPEN_DOCUMENT の document URI
        val displayName: String,   // 追加時に OpenableColumns.DISPLAY_NAME から取得
        val mimeType: String?,     // 追加時に contentResolver.getType()。null=不明
    ) : HomeItem

    /** SAF で選んだ実フォルダへのピン。アプリをまとめる HomeFolder とは別物。 */
    @Serializable
    @SerialName("dir")
    data class HomeDirectory(
        val treeUri: String,       // ACTION_OPEN_DOCUMENT_TREE の tree URI
        val displayName: String,
    ) : HomeItem
}
```
- 命名: アプリをまとめる既存 `HomeFolder` と区別するため、実フォルダは **`HomeDirectory`** とする（UI 上の表示名はどちらも「フォルダ」で良いが、コード上は明確に分ける）。
- `LayoutState.version = 9`。追記のみなので v8 JSON はデフォルト値で安全に読める（既存方針どおり）。
- **ダウングレード非互換の注意**: v9 で保存した JSON を旧バージョンの ohagi が読むと、sealed の未知 `@SerialName`（"file"/"dir"）でデシリアライズに失敗し、`ReplaceFileCorruptionHandler` により**レイアウトが初期化される**。リリースノートに明記する（アプリの通常アップデートでは問題にならない）。

### 3-2. LayoutRepository
- 既存のセル操作（`swapHomeItems` / `setHomeItem` / D&D の move 系）は `HomeItem?` を型非依存に扱うため**変更不要**（実装時に要確認）。
- 追加 API:
  - `placeFileOnHome(index: Int, item: HomeFile)` / `placeDirectoryOnHome(index: Int, item: HomeDirectory)`（空きセルのみ。既存 `placeAppOnHome` と同じガード方針）
  - `renameHomePin(index: Int, name: String)`（表示名のみ変更。実体はリネームしない）
- `pruneMissingPackages`（アンインストール掃除）は**ファイル/フォルダに触れない**。

## 4. UX 仕様

### 4-1. 追加
- **空きセルの長押し** → メニュー「ファイルを置く」「フォルダを置く」を新設。
  - 現在ホームの空きセルはタップ・長押しとも無反応なので、導線が競合しない。
  - （ドックの空きスロットは「タップで割り当て」だが、ホームの空きセルはページ切替スワイプ等と誤操作しやすいため**長押し**とする。実装時に触感を見て調整可）
- 選択後、そのセルへピンを配置。`takePersistableUriPermission` はこのタイミングで取得。
- 取得に失敗（プロバイダが persistable を許さない等）した場合は追加を中止しトースト。

### 4-2. 開く（ファイル）
- タップで `ACTION_VIEW`。複数ハンドラの既定解決は OS に任せる。
- 長押しメニューに「アプリを選んで開く」（`Intent.createChooser`）を用意。

### 4-3. 開く（フォルダ）= dango 連携
1. `Intent(ACTION_VIEW).setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR).setPackage("io.github.hatake716.dango")` を解決できれば dango で開く。
2. 解決できなければ `setPackage` を外して汎用 `ACTION_VIEW`（DocumentsUI / Files が受ける）。それも失敗ならトースト。
- **dango 側の要件（別リポジトリ作業）**: `vnd.android.document/directory` の `ACTION_VIEW` を受ける intent-filter と、受け取った tree URI をそのフォルダ表示で開く処理。dango 側仕様は本仕様確定後に dango リポジトリへ起こす。
- dango 連携が入るまでは 2. のフォールバックのみで出荷可能（M2 参照）。

### 4-4. 長押しメニュー（ファイル/フォルダ セル共通）
| 項目 | 動作 |
|---|---|
| 開く | 4-2 / 4-3 と同じ |
| アプリを選んで開く | chooser（ファイルのみ） |
| 名前を変更 | ohagi 上の表示名のみ変更（実体はリネームしない） |
| ピンを外す | セルから取り除く。**実体は削除しない**。文言・アイコンで「削除」と誤解させない（ゴミ箱アイコンは使わない） |

### 4-5. D&D
- 既存の公式 D&D（`ohagiDragSource` / `FromHome(index)`）にそのまま乗る。index ベースなのでセルの中身が何であっても並べ替え・ページ跨ぎ移動は既存ロジックで動く。
- **アプリフォルダへのスタック不可**: `canStackOnHome` で `HomeFile` / `HomeDirectory` を弾く（アプリ同士のフォルダ生成に巻き込まない）。
- **ドックへのドロップ不可**: ドック側 `onDrop` で `FromHome` の中身がファイル/フォルダなら拒否（return false）。
- **削除エリア**: ドロップで「ピンを外す」（4-4 と同じ意味論）。削除エリアのラベルはドラッグ中身に応じて「削除」→「ピンを外す」に切り替えると誤解がない（任意、実装時判断）。
- ドラッグ影（drawDragDecoration）: サムネイル/種類別アイコンをアプリと同じ角丸で描く。

### 4-6. 表示
- セル寸法・ラベル体裁はアプリセルと同一。
- アイコン:
  - 画像/動画/PDF: サムネイルを iOS 角丸（`IOS_ICON_CORNER_RATIO`）でクリップ。動画は再生バッジ、PDF は種別バッジを右下に小さく重ねる（任意）。
  - その他ファイル: 種類別アイコン（画像/動画/音楽/PDF/文書/圧縮/その他 の 7 種程度。MIME の大分類で振り分け）。
  - 実フォルダ: macOS 風フォルダアイコン。**アプリフォルダ（2×2 ミニグリッド）とは見た目を明確に変える**。
- ラベル: `displayName` を拡張子込みで表示（macOS 同様）。1 行 ellipsis。

## 5. エッジケース

| ケース | 挙動 |
|---|---|
| 実体が削除/移動/リネームされた | 開こうとした時に失敗を検出しトースト「ファイルが見つかりません。長押しでピンを外せます」。**自動掃除はしない**（下記理由） |
| SD カード/USB ストレージの一時取り外し | 上と同じ。自動掃除しないので、再装着すれば復活する |
| 永続許可の失効（プロバイダ側都合） | 同上 |
| 上限（512/128 件）到達 | 追加時に警告し追加しない |
| 同じファイルを複数セルにピン | 許可（許可管理は URI 単位なので released は最後の 1 つで） |
| 機種変更/バックアップ | `allowBackup=false`（既存方針）。URI は端末固有のため引き継ぎ不可。既知の制約として README に記載 |

**自動掃除をしない理由**: アプリと違い「一時的に見えない」（SD 取り外し・プロバイダ再起動）を「消えた」と区別できず、誤掃除がユーザーデータ（配置）の恒久喪失になるため。既存の Graph のアプリ掃除が「PackageManager で実在確認してから消す」のと同じ慎重さを、ファイルでは「消さない」に倒して実現する。

## 6. 非スコープ（今回やらないこと）

- 実体のファイル操作（削除・コピー・移動・実体リネーム）— ohagi はランチャーであり、ファイル操作は dango / Files の領分
- デスクトップフォルダ同期方式（1 フォルダの中身を自動表示）— 将来拡張。HomeItem を sealed で拡張できる構造は維持済み
- ドック・ウィジェットページへの配置
- ohagi 内ファイルブラウザ
- クラウドストレージ特有の最適化（SAF 経由で Google Drive 等のドキュメントも技術的にはピン可能。動けば儲けもの扱いとし、動作保証はローカルストレージのみ）

## 7. 実装マイルストーン

| 段階 | 内容 | 出荷可能ライン |
|---|---|---|
| **M0** | データモデル(v9) / 空きセル長押し→SAF ピッカー→ピン配置 / 種類別アイコン表示 / タップで開く（フォルダは汎用 VIEW） / 長押しメニュー / D&D 並べ替え・ピン解除 | ここで最小機能として成立 |
| **M1** | サムネイル（画像/動画/PDF）+ LruCache / フォルダアイコンの macOS 風仕上げ | 見栄え完成 |
| **M2** | dango 連携（dango 側 intent-filter 対応と同時） / 開けない時の誘導 UX / 上限警告 | 連携完成 |

各段階でエミュレータ検証（SAF ピッカー操作・サムネイル・D&D）→実機導入、の順で進める。

## 8. 未決事項

1. 空きセル長押しメニューの項目名（「ファイルを置く」「フォルダを置く」で仮置き）
2. 削除エリアのラベル切り替え（「ピンを外す」）を入れるか
3. 動画/PDF バッジの有無
4. dango 側 intent-filter の詳細仕様（dango リポジトリで別途）
