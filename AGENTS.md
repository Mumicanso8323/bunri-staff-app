# 文理祭運営(iOS)

文理祭サイト(https://bunrisai.jp ・ /data/ashwell/bunri)のビンゴ・トーナメント運営用ネイティブ(SwiftUI)アプリ。同じ API を URLSession で使う。委員だけが TestFlight の内部テスターとして使う。審査には出さない。
- 構成・手順・未確認事項は docs/ios.md。iOS は ios/(XcodeGen の ios/project.yml)。hub に Xcode は無い=Swift は書くだけ、ビルドは GitHub Actions(手動起動のみ)。
- 管理者パスワードは secret BUNRI_ADMIN_PASSWORD → ビルド時に Config/secrets.xcconfig(16進・.gitignore)→ Info.plist。値をソース・リポジトリ・ログ・チャット・artifact に出さない。
- Web 側(/data/ashwell/bunri)は本番ツリー=ここから変更しない。
- 外向き操作(リポジトリ作成・push・Actions 起動・App Store Connect・招待)はリードが本人の了承を取ってから。
