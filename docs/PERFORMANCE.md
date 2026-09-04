# 動作・メモリ最適化の検証記録

2026-09-04。比較元は `42537d6cccd9f9fca7bab74d63866bebf3163200`（v0.2.0）。機能と保存形式を維持し、描画の更新範囲、画像の生成・保持量、メモリ圧迫時の再生成可能なデータを対象に変更した。

## 実装

- **描画更新**：Pagerの小数位置、押下・ドラッグの縮尺と透明度、フォルダ背景と編集時の揺れ、端末回転のアニメーション値を描画フェーズで読み取る。HomeScreenやセル全体の毎フレームの再compositionを避ける。読み上げ用ページ番号は整数ページの変化を監視する。D&Dの安定したキー、最新payload、ドロップ対象の登録順は維持する。
- **アプリアイコン**：既存の96／144／192pxに32／48／72pxを追加し、表示に必要な解像度以上を選択する。密度3倍の小さなフォルダプレビューでは96→48pxとなり、その画像のピクセル容量は75%減る。これは画像単位の計算であり、プロセス全体のメモリ削減率ではない。Hardware Bitmap、既存の最大192px、6MiB／Low-RAM端末3MiBのキャッシュ上限を維持する。
- **ファイルサムネイル**：48件の件数制限を実容量4MiB／Low-RAM端末2MiBのLRUに変更。URIと要求サイズが同じ読み込みを共有し、providerへの同時アクセスを2件／Low-RAM端末1件に制限する。最後の利用者が離れた要求はキャンセルし、失敗を保存しない。providerが返した過大な画像は縦横比を保ち、要求サイズ内に縮小する。表示中のBitmapをキャッシュ解放時にrecycleしない。
- **メモリ通知**：画像キャッシュに世代番号を持たせ、解放前に開始した読み込みによるキャッシュの再充填を防ぐ。進行中の画面表示には取得結果を渡せる。
- **ウィジェット**：通常のページ往復では既存のHostViewを再利用する。実際のメモリ圧迫通知を受けた場合は、表示中のViewがなくなってからAndroidのAppWidgetHost内部の強参照も解放する。ID、バインド、設定、保存サイズは維持し、再表示時にRemoteViewsを取得する。

Composeの状態読み取りを遅らせる方針は[公式の性能ガイド](https://developer.android.com/develop/ui/compose/performance/bestpractices)に沿う。ウィジェットはアプリ側の弱参照だけでは保持量を管理できないため、[AppWidgetHostの実装](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/appwidget/AppWidgetHost.java)のView保持も対象にした。通常のページ離脱で毎回再生成するとprovider内のスクロール位置などを失う可能性があるため、解放はメモリ圧迫時に限定した。

ホーム／Dock／フォルダ／Appライブラリ、SAFの永続アクセス許可、アプリ起動と分割画面、横画面の配置、ウィジェットの保存形式に仕様変更はない。

## ビルドと自動検証

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease
```

- 単体テスト66件成功（既存53件＋追加13件）、失敗・エラー・skipなし。
- Lintはエラー0件、警告21件。警告数は比較元と同じ。
- Debug APK、R8による縮小を有効にしたRelease APK、Release AABのビルド成功。
- 追加テストは、表示解像度の境界、描画時の状態購読、サムネイルの容量・LRU・重複要求共有・利用者ごとのキャンセル・待機中キャンセル・失敗再試行・並列数・メモリ通知との競合を検証する。

Release成果物の検証用APKはローカルDebug証明書で署名する。公開用のRelease署名を行ったAABではない。

## 比較測定の条件

専用のAndroid 15 / API 35 Google APIs x86_64エミュレータを使用した。Pixel 7プロファイル、1080×2400、420dpi、RAM 3072MB、4 vCPU、SwiftShader。両版ともDebug APKを使い、測定中にGradle／R8ビルドを並行実行しない。

専用fixtureは96個の異なるアイコンを持つ合成アプリと標準アプリ19件、ホーム3ページ、直接配置51件、ホーム内24アプリのフォルダ、Dock内12アプリのフォルダ、Clockウィジェット、DocumentsUIで権限を取得したファイル／ディレクトリピンからなる。アプリのデータ消去やアンインストールは行わない。

APK交換ごとに、同じインストール方式とARTのコンパイル条件を明示する。

```sh
adb -s emulator-5554 install --no-incremental -r <debug-apk>
adb -s emulator-5554 shell cmd package compile -f -m verify io.github.hatake716.ohagi
```

両版で実効filterが`verify`、理由が`cmdline`、プロセスが`base.vdex`をmapしていることを確認する。[Android公式のメモリ測定手順](https://developer.android.com/topic/performance/memory/guide/app-code#art-compilation-modes-and-memory)でもコンパイル条件を指定して測定する。

各試行は同一fixtureを復元し、Ohagiをforce-stopして`am start -W`で起動、5秒後にcoldメモリを記録する。ウィジェット→ホーム3ページ→ライブラリの上下スクロール→ホーム／Dockフォルダのページ送りを1回暖機し、gfxinfoをリセットして同じ操作を2回実施する。8秒静置した後のメモリをwarmとして記録する。各版3回の中央値を使う。fixture復元スクリプトは専用エミュレータだけを対象にする。

最初に取得した`baseline/`と`optimized/`の比較は採用しない。比較元は圧縮DEX約60,575KiBを匿名メモリに展開していた一方、更新後はfile-backedなVDEXを使っていた。`Unknown`項目の約60,592KiB差はコード最適化の成果とはみなせない。採用するデータは、条件を統一して再取得した`baseline-controlled/`と`optimized-controlled/`のみ。

これらはDebug・ソフトウェアGPU・合成fixtureの比較であり、実機のRelease性能を示すものではない。PSSは共有コードページの帰属やGC時期にも影響される。総PSS、Java／Native Heap、描画時間を併記し、サムネイルの容量制限をアプリ全体の上限と混同しない。

## 比較結果

全6試行のJSONとraw meminfo／gfxinfo／起動ログを照合し、一致を確認した。表は各3回の中央値［最小–最大］。メモリ単位はKiB、Java／Native HeapはmeminfoのApp Summary値であり、raw表のHeap Allocとは異なる。

| 指標 | 比較元 | 最適化後 | 中央値の変化 |
| --- | ---: | ---: | ---: |
| 起動時間（ms） | 1,298［1,284–1,319］ | 1,347［1,296–1,352］ | +3.8% |
| 描画p50（ms） | 30［29–31］ | 28［27–28］ | −6.7% |
| 描画p90（ms） | 40［38–40］ | 36［34–36］ | −10.0% |
| 描画p95（ms） | 44［42–44］ | 40［38–40］ | −9.1% |
| Janky frames率 | 9.55%［9.19–9.57］ | 8.96%［8.84–8.99］ | −0.59ポイント |
| cold 総PSS | 177,743［168,478–178,224］ | 175,367［168,743–175,457］ | −1.3% |
| warm 総PSS | 173,859［145,401–191,416］ | 187,763［184,299–195,108］ | +8.0% |
| cold Java Heap | 17,736［17,732–17,752］ | 17,716［17,712–17,756］ | −0.1% |
| warm Java Heap | 18,464［18,380–20,212］ | 22,868［18,564–23,268］ | +23.9% |
| cold Native Heap | 45,636［45,568–45,636］ | 44,864［44,852–44,868］ | −1.7% |
| warm Native Heap | 55,772［50,488–55,780］ | 56,748［55,900–56,868］ | +1.7% |

今回確認できたのはページ・フォルダ操作の描画時間の改善傾向であり、アプリ全体のメモリ削減や起動高速化は確認できていない。操作後の総PSSは中央値で8.0%増えている。raw Dalvik Heap Alloc中央値は9,185→9,201KiBとほぼ同じだが、Native Heap Alloc中央値は64,395→65,067KiBで約1.0%増えた。GC時期や共有ページなどの影響はあり得るものの、差の原因は今回の記録だけでは確定できない。

通常操作後の総PSSとは別に、ホーム3ページ目まで離れてメモリ圧迫を通知した場合のView数は比較元14個、最適化後8個だった。ウィジェットのIDと保存レイアウトを維持したまま非使用Viewを解放し、再表示後に時計が更新され、タップでClockアプリが開くことを確認した。このView数は専用fixtureの1回の確認であり、全providerに共通するメモリ削減量ではない。

## 操作と保存データの確認

| 対象 | 確認内容 |
| --- | --- |
| ホーム／DockのD&D | 実ジェスチャーでホーム→空Dock→元のホームへ移動。Debugの保存レイアウトで対象AppRefの移動と復元をassert |
| ページ／フォルダ | ホーム3ページ、Appライブラリ、ホーム／Dockの複数ページフォルダを反復操作 |
| ファイルサムネイル | DocumentsUIでPNGを登録し、ページ往復・force-stop後の再起動・メモリ圧迫後もRGB格子の表示を確認。ReleaseではPhotosで実画像を開けることも確認 |
| PDF | このproviderはサムネイルを提供せず、PDFの種類アイコンを表示 |
| ウィジェット | OSのバインド／設定画面を通してClockを追加。メモリ圧迫後もID・レイアウトを維持し、再表示・時刻更新・タップ起動を確認 |
| アプリ起動／履歴 | ライブラリ検索から合成アプリを起動。usage.jsonと「よく使うアプリ」表示への反映を確認 |
| 分割画面 | ReleaseでSettingsを起動し、Ohagiの通知から2つ目のアプリ選択画面を開いてClockを選択。両アプリの表示とOSのmulti-window状態を確認 |
| Release APK | Debugから同じデータへ上書きし、ホーム／Dock／フォルダ／ピン／メニュー／Clock／縦横表示を確認。non-debuggableを確認、確認時のcrash bufferは空 |

確認範囲には次の限界がある。

- 回転時にホーム1ページ目へ戻る挙動を観測した。縦横でHomeScreenのcomposition位置を切り替える既存のPortraitStage分岐と整合する。この分岐、初期ページ設定、MainActivity、Manifestは今回未変更。変更前APKでの同操作の再現試験は追加していない。
- ライブラリから外部アプリを起動した後のHOME復帰ではホーム1が表示され、この試験では元のライブラリページ保持を確認できなかった。起動イベント経路は今回未変更。
- ディレクトリピンはFilesを起動できたが、このGoogle DocumentsUIではDownloads親階層を表示した。dango未導入のため、対象ディレクトリ直下への遷移は未確認。
- 全Androidバージョン、全ファイルprovider、全ウィジェットproviderを網羅した検証ではない。

## 実機への導入

エミュレータで確認したものと同一SHAのRelease APKを、接続中のPixel 10aへ`install -r`で導入した。インストールは成功し、versionCode 2／versionName 0.2.0、non-debuggable、Ohagiの既定HOMEロールを確認した。既存アプリと同一の署名証明書を使い、データ消去・アンインストールは行っていない。実機への入力操作は行っておらず、実機の操作感・Release性能を計測した結果ではない。

検証用APKは`app/build/outputs/apk/release/ohagi-optimized-debugsigned.apk`。SHA-256:

```text
252e37761e27ec009a7c682efe26570276e96f8fca231070f77c723d83ded70a
```

詳細な測定スクリプト、raw dumps、ビルドログ、操作検証結果はローカルの`app/build/reports/optimization/`へ保存する。同ディレクトリはビルド成果物でありGitの対象外。
