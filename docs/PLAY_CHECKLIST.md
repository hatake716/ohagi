# Google Play 公開チェックリスト

ohagi(`io.github.hatake716.ohagi`)を Google Play で公開するための手順です。

## 1. 署名鍵の作成

アップロード鍵を `keytool` で作成します(JDK 17 に同梱)。

```sh
keytool -genkeypair \
  -keystore release.keystore \
  -alias ohagi \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

- `release.keystore` はプロジェクトルートに置きます(場所は `keystore.properties` の `storeFile` で変更可能)。
- 鍵とパスワードは必ずバックアップしてください。Play App Signing を有効にすればアプリ署名鍵は Google が管理し、この鍵は「アップロード鍵」になります(紛失時の再発行が可能になるため有効化を推奨)。

## 2. keystore.properties の配置

プロジェクトルートに `keystore.properties` を作成します。このファイルが存在すると release ビルドに自動で署名されます。

```properties
storeFile=release.keystore
storePassword=あなたのストアパスワード
keyAlias=ohagi
keyPassword=あなたの鍵パスワード
```

注意:

- `keystore.properties` と `*.keystore` は **絶対に Git にコミットしない** でください(`.gitignore` に追加)。
- ファイルが無い場合、release は未署名でビルドされます(エラーにはなりません)。

## 3. AAB のビルド

```sh
./gradlew bundleRelease
```

- 出力先: `app/build/outputs/bundle/release/app-release.aab`
- release ビルドは minify + resource shrink が有効です。実機で動作確認してから提出してください。
- バージョン更新時は `app/build.gradle.kts` の `versionCode`(必ずインクリメント)と `versionName` を上げます。

## 4. Play Console での設定

### ストア掲載情報

- [ ] アプリ名・短い説明(80 文字)・詳しい説明(4000 文字)
- [ ] アプリアイコン 512x512 PNG
- [ ] フィーチャーグラフィック 1024x500
- [ ] スクリーンショット(スマートフォン用最低 2 枚。ワークスペース・分割カラム・ドロワー・ドックフォルダなど特徴が伝わる画面を推奨)
- [ ] カテゴリ: 「カスタマイズ」(ランチャーの定番カテゴリ)
- [ ] プライバシーポリシーの URL(`docs/PRIVACY_POLICY.md` を GitHub Pages などで公開し、その URL を登録)

### データセーフティ

- [ ] 「データを収集しない」「データを共有しない」を申告
  - ohagi はネットワーク通信を行わず、レイアウトデータ(`layout.json`)は端末内の DataStore にのみ保存されます。
  - 広告 SDK・解析 SDK は含まれていません。

### アプリのコンテンツ

- [ ] コンテンツレーティングのアンケートに回答(ユーザー生成コンテンツなし)
- [ ] ターゲットユーザー層(子ども向けではない設定を推奨)
- [ ] 広告の有無: 「広告なし」

### ターゲット API 要件

- [ ] targetSdk 36 — 2026 年時点の Google Play のターゲット API レベル要件を満たしています。以後も毎年の要件引き上げに合わせて更新してください。

### 権限の申告

- [ ] `QUERY_ALL_PACKAGES` は **使用していません**。マニフェストの `<queries>` で `MAIN` + `LAUNCHER` インテントを列挙する方式のため、Play の機密性の高い権限に関する申告フォームは不要です。
- [ ] `REQUEST_DELETE_PACKAGES` は通常権限(normal permission)で、アンインストール確認ダイアログを出すためのものです。特別な申告は不要です。

## 5. ホームランチャー特有の注意

- **デフォルトホーム切替の UX**: ohagi はインストールしただけではホームにならず、ユーザーが自分でデフォルトホームに設定する必要があります。アプリ内の「中央ボタン長押し → デフォルトホームに設定」から RoleManager(Android 10 以降)またはホーム設定画面を開けます。ストア説明文にも切替手順を書いておくと低評価レビューを減らせます。
- **HOME インテントフィルタ**: `MainActivity` が `HOME` / `DEFAULT` / `LAUNCHER` カテゴリを宣言しているため、ホームボタンの選択肢に表示されます。審査でランチャーとしての実体があるか確認されることがあります。
- **アンインストールのしにくさ**: デフォルトホームに設定中はアンインストールしづらいため、切替方法(設定 → アプリ → デフォルトのアプリ)を説明文に記載しておくと親切です。
- **分割起動の非対応端末**: 一部メーカー ROM では `FLAG_ACTIVITY_LAUNCH_ADJACENT` が機能しません。ストア説明文に「端末によっては分割起動が動作しない場合があります」と明記しておくとトラブルを防げます。

## 6. リリース前の最終確認

- [ ] 実機(できれば複数メーカー)で release ビルドを動作確認(minify 起因の不具合チェック)
- [ ] 縦横回転・フォルダ・分割起動・壁紙変更・デフォルトホーム切替の動作確認
- [ ] 内部テストトラックで配信 → 問題なければ製品版へ昇格
