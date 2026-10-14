# 文理祭ビンゴ運営(iOS)

文理祭サイト(https://bunrisai.jp ・ /data/ashwell/bunri)のビンゴ運営画面を包む薄い WKWebView アプリ。委員だけが TestFlight の内部テスターとして使う。審査には出さない。
- 構成・手順・未確認事項は docs/ios.md。iOS は ios/(XcodeGen の ios/project.yml)。hub に Xcode は無い=Swift は書くだけ、ビルドは GitHub Actions(手動起動のみ)。
- パスワード・鍵・Cookie の値をリポジトリ・ログ・画面に出さない。Web 側(/data/ashwell/bunri)は本番ツリー=ここから変更しない。
- 外向き操作(リポジトリ作成・push・Actions 起動・App Store Connect・招待)はリードが本人の了承を取ってから。
