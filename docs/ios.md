# 文理祭ビンゴ運営(iOS アプリ)

表示名「文理祭ビンゴ運営」、bundle id `com.yusukedoi.bunrisaistaff`(変えない)、iOS 17 以上。文理祭(2026-10-18 日 14:55〜 ビンゴ本番)の委員だけが使う。配布は TestFlight の内部テスター(App Store Connect のチームのメンバー)のみ。審査には出さない。
手本は ccf-companion(置き卓通知)。XcodeGen・GitHub Actions(macOS)・内部グループの用意の形を揃えてある。hub に Xcode は無いので、ここでは Swift を書くだけでビルドはしていない。

## 中身(既存の Web 画面を包む)
- タブ「抽選」: `https://bunrisai.jp/admin/bingo`(セッション作成・開始・終了・次の番号)
- タブ「QR確認」: `https://bunrisai.jp/admin/bingo/verify`(来場者の QR をカメラで読み、景品を受け取り済みにする)
- ログインは Web 側の画面(`POST /api/admin/login` → Cookie `admin_token`)。アプリはパスワードを持たない・書かない・表示しない。Cookie は `WKWebsiteDataStore.default()` に残る。Web 側の Cookie は `maxAge` 24 時間(`src/lib/admin-auth.ts`)なので、前日以前のログインは当日に切れる。**当日の朝に一度ログインしておく**。
- 2タブは同じデータストアを共有する(片方でログインすればもう片方も入る)。WebView はタブごとに1つ持ち、切り替えても作り直さない。

## ファイル
- `ios/project.yml`: XcodeGen。Info.plist は生成(`INFOPLIST_KEY_*`)。`NSCameraUsageDescription` もここ。
- `ios/BunriStaff/WebPolicy.swift`: 純粋な判定(`NavigationPolicy`・`MediaPolicy`・`StaffSite`)。単体テストの対象。
- `ios/BunriStaff/StaffWebView.swift`: WKWebView の包み。`WKNavigationDelegate`(遷移の判定・5xx・失敗)と `WKUIDelegate`(カメラ許可・window.open)、引っ張って再読み込み、失敗の表示(再読み込みボタン)。
- `ios/BunriStaff/BunriStaffApp.swift`: 2タブ。
- `ios/BunriStaffTests/WebPolicyTests.swift`: 単体テスト。UI テストは作らない。
- `ios/scripts/testflight_group.py`: App Store Connect の下ごしらえ(アプリの有無・内部グループ「文理祭委員」・テスター)。`signing_cert.py`: 開発証明書を1枚だけ作る(ccf と同じ)。
- `.github/workflows/ios.yml`: 手動起動のみ。

## 動作の決め
- 遷移: `https://bunrisai.jp` と完全一致(ポートは既定のみ・利用者名なし)だけ WebView で開く。`www.`・`bunrisai.jp.example.com`・`https://bunrisai.jp@example.com`・http は Safari に渡す(`mailto:` `tel:` も)。`javascript:` `file:` `data:` などは何もしない。他サイトの iframe は黙って止める。`target=_blank` と `window.open` は、許す場所ならこの WebView で開く。
- カメラ: `requestMediaCapturePermissionFor` で、https の bunrisai.jp のカメラだけ `.grant`(マイクは `.deny`)。`allowsInlineMediaPlayback = true`・`mediaTypesRequiringUserActionForPlayback = []`。初回は iOS 自身の許可ダイアログが別に出る(文面は `NSCameraUsageDescription`)。
- 失敗: 読み込み失敗(オフライン等)と、画面全体のレスポンスが 5xx のとき、全面に「読み込めませんでした」+「再読み込み」。引っ張って再読み込みも可。キャンセル(Safari に渡した遷移)は失敗にしない。WebContent プロセスが落ちたら自動で再読み込み。

## Web 側が使っているもの(読んで確かめた)
- QR 読み取りは `html5-qrcode`(`src/app/admin/bingo/verify/page.tsx`)=`getUserMedia` + canvas 解析。`BarcodeDetector` に依らないので WKWebView でも通るはず(実機では未確認)。
- `POST /api/admin/bingo/claim`(Cookie `admin_token` 必須): body `{guestId: string, sessionId: string}`。200 = `{ok:true,...}`、401 `unauthorized`、400 `invalid_request`、404 `not_found`、409 `{error: <理由>, player}`(受け取り済みなど)。ほかに `draw`・`player`・`session` の各 route がある。

## 代替案の設計メモ(実装していない・判断待ち)
WKWebView の `getUserMedia` がどうしても動かないとき: AVFoundation(`AVCaptureMetadataOutput` の `.qr`)でネイティブに QR を読み、`POST /api/admin/bingo/claim` を叩く。
- 認証: WebView でログインした Cookie を `WKWebsiteDataStore.default().httpCookieStore.getAllCookies` から取り、`URLSession` の `HTTPCookieStorage` に写して同じ Cookie で叩く(パスワードをアプリが持たない原則を保つ)。
- QR の中身から `guestId`・`sessionId` を取り出す形は、Web 側の来場者画面が出す QR の生成(`src/app/(public)/bingo` と `verify/page.tsx` の読み取り後の処理)を読んで揃える必要がある(未調査)。
- 結果の表示は Web の verify 画面と同じ文言(受け取り済み・見つからない・すでに受け取り済み)に揃える。
- 費用: 画面 1 つ + API クライアント 1 つ + 単体テスト(QR 文字列の解析・409 の分岐)。Info.plist・Cookie の扱いは今のままで足りる。

## 確かめられていないこと(実機でやる)
1. Swift はコンパイルしていない(hub に Xcode が無い)。最初の `ios.yml`(何も選ばない起動)でビルドと単体テストが通るか。
2. WKWebView でのカメラ(最大の未確認): TestFlight に入れた実機で、QR確認タブ →「スキャン開始」→ iOS の許可ダイアログ → 映像がページ内に出る → 別端末の QR を読めて「受け取り済み」にできる。映像が出ない・黒いときは上の代替案へ。
3. ログインの保持: 一度ログイン → アプリを完全に終了(タスクスイッチャーで上へ)→ 再起動してもログイン済みか。直前に終了すると Cookie がディスクへ書かれていない場合がある(WebKit の既知の癖)ので、ログインして少し使ってから終了して試す。
4. 24 時間切れ: 翌日にログイン画面に戻ること(想定どおり)。
5. タブを切り替えてもカメラが動き続けないか(QR確認タブを離れても `html5-qrcode` は止まらない可能性。電池と発熱。気になれば離れたとき WebView を `about:blank` にする)。
6. 横向き: iPhone は縦・横を許可している。運営で横向きが不要なら縦のみにする(project.yml の `INFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone`)。

## 公開前の手順(リードが本人の了承を取ってから)
下の `<owner>/<repo>` は GitHub のリポジトリ(例 Mumicanso8323/bunri-staff-app)。値は表示しない。
1. **GitHub リポジトリ**: `gh repo create <owner>/<repo> --private --source /data/ashwell/bunri-staff-app --push`(private 推奨)。
2. **Apple Developer に Bundle ID**: `com.yusukedoi.bunrisaistaff` を登録(ccf は ASC API の `POST /v1/bundleIds`・platform IOS で登録した。capability 追加なし。Developer サイトの Identifiers からでも可)。
3. **App Store Connect にアプリ**(本人の1操作・API では作れない): https://appstoreconnect.apple.com/apps →「+」→「新規 App」: プラットフォーム iOS・名前「文理祭ビンゴ運営」・言語 日本語・バンドル ID `com.yusukedoi.bunrisaistaff`・SKU `bunrisai-staff`・ユーザアクセス「アクセス制限なし」。
4. **Environment「testflight」**(リポジトリの Settings → Environments)に secret。ccf・hub-ios と同じ値を使う: `ASC_KEY_ID`・`ASC_ISSUER_ID`・`ASC_KEY_P8`・`APPLE_TEAM_ID`。任意で `STAFF_TESTER_EMAIL`(内部グループに入れる人のメール。カンマ区切りで複数)。値はチャットに貼らない(`gh secret set --env testflight` で hub の既存の控えから流すか、本人が画面で入れる)。
5. **署名の証明書**(`IOS_DEV_CERT_P12`・`IOS_DEV_CERT_PASSWORD`): ccf の docs/ios.md「署名」と同じ。チームで共有の1枚を、このリポジトリの Environment にも置く(ccf で作った p12 を再利用するのが、証明書を増やさない)。新規に作るなら 1) hub: `python3 ios/scripts/signing_cert.py keypair <一時フォルダ>` 2) `gh workflow run ios -R <owner>/<repo> --ref main -f dev_cert=true -f recipient="$(cat <一時フォルダ>/recipient.pem)"` 3) `gh run download <run> -n dev-cert-sealed -D <一時フォルダ>` → `python3 ios/scripts/signing_cert.py install <一時フォルダ> <一時フォルダ>/dev-cert.sealed.json <owner>/<repo>` で secret を置き、手元の鍵と包みを消す。
6. **先にテスト**: `gh workflow run ios -R <owner>/<repo> --ref main`(何も選ばない)→ 単体テストと未署名ビルドが通ることを確かめる。ここで Swift の書き間違いが出たら直す。
7. **TestFlight へ**: `gh workflow run ios -R <owner>/<repo> --ref main -f testflight=true`。下ごしらえが内部グループ「文理祭委員」(全ビルド自動配布)を用意し、`STAFF_TESTER_EMAIL` の人を入れる。Apple の処理に 10〜30 分。
8. **委員の招待**: 内部テスターは App Store Connect の「ユーザとアクセス」に入っている人だけ。委員を「ユーザ」として招待(役割は「マーケティング」か「開発者」など最小で足りる。「App Store Connect へのアクセス」だけでよい)→ 本人がメールの招待を承諾 → グループ「文理祭委員」に追加(`STAFF_TESTER_EMAIL` で入るのは招待承諾済みの人だけ。409 で保留になった人は TestFlight 画面から足す)→ 各自が TestFlight アプリで「文理祭ビンゴ運営」をインストール。人数が多いなら、ユーザ招待は本人の判断(外向き)。
9. **当日の前に**: 上の「確かめられていないこと」を実機で。10/18 の朝に各端末でログインしておく。
