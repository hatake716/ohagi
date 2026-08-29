# プライバシーポリシー / Privacy Policy

最終更新日 / Last updated: 2026-08-29

対象アプリ / Application: ohagi (`io.github.hatake716.ohagi`)

## 日本語

ohagi はユーザーのプライバシーを尊重します。

- **個人情報の収集・送信は一切行いません。** 氏名、連絡先、位置情報、端末識別子、利用状況などのデータを収集しません。
- **すべてのデータは端末内にのみ保存されます。** ホーム画面のレイアウト(ページ・ドック・フォルダの構成)と、配置したウィジェットのID・提供元・表示サイズ・並び順は端末内のアプリ専用領域に保存され、外部に送信されることはありません。
- **ネットワーク通信を行いません。** 本アプリはインターネットへのアクセスを一切行わず、広告 SDK や解析 SDK も含みません。
- **Androidのクラウドバックアップと端末間転送を無効化しています。** ランチャー配置を端末外へ複製しないよう、Manifestとデータ抽出ルールの両方で除外します。
- **インストール済みアプリの一覧**は、ランチャーとしてアプリを表示・起動するためだけに端末内で参照され、外部に送信されることはありません。
- **通知権限は分割画面起動にだけ使用します。** ホーム、Dock、フォルダ、またはAppライブラリからアプリを通常起動した時、ohagiはそのアプリへ追加する2つ目を選べる常駐通知を表示します。通知はタップ後や分割完了後も自動では消えず、次の通常起動時に最新のアプリ用へ置き換わります。ユーザーはOSが提供する横スワイプまたはAndroidの通知設定から通知を消すことができます。通知に保持するのはohagiが起動したアプリのコンポーネント名だけで、処理は端末内で完結します。ohagiは通知アクセス権限を要求せず、他のアプリが表示した通知やその内容を読み取りません。
- **ウィジェットの内容**はインストール済みの提供元アプリがAndroid標準App Widget機構を通じて表示します。ohagi自身はその内容を収集・送信しません。提供元アプリ自身の通信やデータ処理には、その提供元のプライバシーポリシーが適用されます。
- アプリをアンインストールすると、保存されたレイアウトデータは端末から削除されます。

本ポリシーに変更がある場合は、本ページを更新します。ご質問は GitHub リポジトリ(https://github.com/hatake716/ohagi)の Issue にてお寄せください。

## English

ohagi respects your privacy.

- **No personal information is collected or transmitted.** The app does not collect names, contacts, location, device identifiers, or usage data.
- **All data stays on your device.** Your home screen layout (pages, dock, and folders), together with the IDs, providers, display sizes, and ordering of placed widgets, is stored only in the app's private storage and is never sent anywhere.
- **No network communication.** The app never accesses the internet and contains no advertising or analytics SDKs.
- **Android cloud backup and device-to-device transfer are disabled.** The manifest and data-extraction rules both exclude launcher layout data from being copied off the device.
- **The list of installed apps** is read on-device solely to display and launch apps as a launcher, and is never transmitted externally.
- **Notification permission is used only for split-screen launching.** When you normally launch an app from the home screen, Dock, a folder, or the App Library, ohagi posts an ongoing notification that lets you choose a second app to add beside it. The notification is not automatically removed when tapped or after split-screen setup completes; the next normal launch updates it for the latest app. You can dismiss it using the gesture provided by the OS or disable it in Android's notification settings. The notification stores only the component name of the app that ohagi launched, and all processing stays on-device. ohagi does not request notification-listener access and cannot read notifications or notification content posted by other apps.
- **Widget content** is supplied by installed provider apps through Android's standard App Widget framework. ohagi does not collect or transmit that content. Any network or data processing performed by a provider app is governed by that provider's privacy policy.
- Uninstalling the app removes the stored layout data from your device.

If this policy changes, this page will be updated. For questions, please open an issue on the GitHub repository (https://github.com/hatake716/ohagi).
