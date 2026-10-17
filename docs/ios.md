# 文理祭運営(iOS アプリ)

表示名「文理祭運営」(ビンゴとサークル対抗戦トーナメントを扱う)、bundle id `com.yusukedoi.bunrisaistaff`(変えない)、iOS 17 以上。文理祭(2026-10-18 日 14:55〜 ビンゴ本番)の委員だけが使う。配布は TestFlight の内部テスター(App Store Connect のチームのメンバー)のみ。審査には出さない。
手本は ccf-companion(置き卓通知)。XcodeGen・GitHub Actions(macOS)・内部グループの用意の形を揃えてある。hub に Xcode は無いので、ここでは Swift を書くだけでビルドはしていない。

## 作り(ネイティブ)
WebView ではなく SwiftUI の画面で、Web 版と同じ API を URLSession で直接使う(サーバーと DB は Web 版と共通)。最初の WKWebView 版は git の履歴に残っている(8c8f979)。
- タブ「抽選」: 状態・参加者数・ビンゴ達成者・最新の番号(大)・1〜75 の一覧。ボタンは状態に合うものだけ(なし→「新しいセッション」/待機中→「開始」/進行中→「次の番号を引く」/終了→「新しいセッション」)。
  - 取り違え対策: 「終了」は画面の一番下・赤・確認ダイアログ(「終了する」)。「開始」「次の番号を引く」は上の大きいボタンで、離してある。「次の番号を引く」は押した直後の約3秒と通信中は押せない(`LotteryModel.canDraw`)。
  - 立て直し: 終了後に「終了を取り消す(同じセッションを再開)」(確認つき)。`PATCH session {status:"active"}` で同じセッションを進行中に戻す。番号と参加者は残る(Web 画面には無い操作だが、API は `waiting|active|finished` を受ける=`bingo/session/route.ts`)。
  - 「新しいセッション」は、既存の全セッションを終了して新しい待機中を作る(`createSession`)。参加者は前のセッションに紐づいたままなので、claim は `stale_session` で通らない=景品の対象外。間違えた終了の立て直しには使わない。
  - 景品抽選(0.2.0〜): 番号を引くボタンの下の「景品抽選」。「景品を引く」→ `POST api/admin/bingo/prize {sessionId,action:"draw"}` → 結果の番号を「景品 No.NN」と大きく表示(会場のスクリーンではルーレットが回る)。「1つ戻す」(確認つき)は `action:"undo"`。回の状態(待機中・進行中・終了)に関係なく引ける。押した直後の約3秒と通信中は押せない。引いた景品は `GET /api/bingo/state` の `prizes`(引いた順)と `prizeTotal`(81)から出す(古いサーバーでは無いので空・81 扱い)。全部引いたら `400 {remaining:0}` =「景品は全部引き終わりました」。
  - 状態は `GET /api/bingo/state` を 2.5 秒ごとにポーリング(`If-None-Match`、304 は何もしない)。画面が前面のときだけ。
- タブ「トーナメント」(`TournamentModel/View.swift`、`TournamentModels.swift` に型と勝ち上がりの解決=`src/lib/tournament.ts` の `resolve` の写し): `GET /api/tournament/state`(ETag・2.5 秒ポーリング)で表を回戦ごと(上のセグメント)に見る。各試合でチームのボタンを押す → 確認(「〇〇の勝ちにしますか?」。決着済みの試合の付け替えは後続が消える旨)→ `POST /api/admin/tournament {action:"win",round,match,team}`。ほかに「勝者を取り消す」(`unwin`)・「1手戻す」(`undo`・確認つき)・「いまの試合にする/解除」(`current`/`clearCurrent`)・最下部の赤い「リセット」(`reset`・確認つき)。サーバーが 400 で返す理由(日本語)はそのまま帯に出す。チーム入れ替え・枠・回戦名(`setTeams`/`setSlots`/`labels`)は Web 管理画面で行う(アプリには無い)。
- タブ「QR確認」: AVFoundation で QR を読む → `GET admin/bingo/player?guest=<UUID>` → 結果を大きく表示 →(ビンゴ達成かつ未受取のとき)「景品受取済みにする」→ `POST admin/bingo/claim`。「次を読む」で再開。表示中は読み取りを止める。カメラが使えない/読めないときのために、ID の手入力欄もある。
  - QR の中身は `https://<host>/admin/bingo/verify?player=<UUID>`(`components/bingo/bingo-result.tsx`)。UUID の形でなければ API を呼ばずに「無効な QR」。
  - **指示との違い**: 読んだだけでは claim せず、「景品受取済みにする」を1回押してから claim する(Web の verify 画面と同じ。claim は取り消せないので、渡す前に記録してしまうのを避ける)。読んだら即 claim にしたければ `VerifyModel.handle` の `.readyToClaim` の分岐で `claim` を呼ぶだけ。
  - 表示: 受け取り済みにしました(200)/すでに受け取り済み(409 `already_claimed`、または読んだ時点で `claimed`)/まだビンゴではありません(`no_bingo`)/過去のセッションです(`stale_session`・`isLatestSession:false`)/見つかりません(404)/無効な QR/通信エラー(「もう一度QRを読むと記録できたか分かる」)。
- タブ「使い方」: ネイティブの静的な画面(オフラインで読める)。ボタン名・表示名はアプリの画面と同じ言葉(`GuideView.swift`)。
- ログイン: 起動時に `POST /api/admin/login`(`application/x-www-form-urlencoded` の `password`)。成功は 303 + `Set-Cookie: admin_token`(24 時間)。失敗は 303 の `Location: /admin?error=1`(`error=locked` は 5 回失敗で 10 分)。3xx は追わず Set-Cookie だけ取り、`HTTPCookieStorage` に入れる。管理 API が 401 を返したら、ログインし直して1回だけ再試行(2回目の 401 は失敗)。通信エラーでは再試行しない(抽選が二重に走るのを避ける)。ログインの失敗は自動で繰り返さない(IP 単位の制限が会場全体に効くため)。上の赤い帯の「やり直す」だけ。

## ファイル
- `ios/project.yml`: XcodeGen。`Config/app.xcconfig`(`BUNRI_ADMIN_PASSWORD_HEX` の既定は空、`#include? "secrets.xcconfig"`)→ `Config/Info.plist` の `StaffAdminPasswordHex`。`NSCameraUsageDescription` もここ。
- `ios/BunriStaff/AdminAPI.swift`(API クライアント・actor)、`Models.swift`、`Messages.swift`(エラー文・QR の取り出し)、`Credentials.swift`(16 進の復号)、`LotteryModel/View.swift`、`VerifyModel/View.swift`、`QRScannerView.swift`、`GuideView.swift`、`BunriStaffApp.swift`。
- `ios/BunriStaffTests/`: URLProtocol のモックで API クライアント(ログイン・401 のやり直し・ETag と 304・抽選と claim の各結果)、画面のモデル(結果の振り分け・二重送信)、小さな純関数。UI テストは無い。
- `ios/Config/secrets.xcconfig.example`: 値は空。手元でログインまで試すときだけコピーして 16 進を入れる(`secrets.xcconfig` は .gitignore)。
- `ios/scripts/testflight_group.py`(内部グループ「文理祭委員」の用意)・`signing_cert.py`(開発証明書を1枚だけ作る)、`.github/workflows/ios.yml`(手動起動のみ)。

## パスワード(BUNRI_ADMIN_PASSWORD)の扱い
- GitHub の Environment「testflight」の secret `BUNRI_ADMIN_PASSWORD`(Web 側の `ADMIN_PASSWORD` と同じ値)。testflight ジョブが archive の直前に 16 進にして `Config/secrets.xcconfig` に書き(xcconfig は `//` と `$` を特別に扱うため)、Info.plist 経由でアプリに入る。ソース・git・ログには出ない。ジョブの最後に消す。
- 値入りのビルドは artifact に上げない(testflight ジョブに upload-artifact は無い。build ジョブは値が空で、失敗時のテストログだけ上げる)。
- **IPA を開けば読める**(ccf の中継の鍵と同じ)。TestFlight の内部テスターにだけ配る前提。委員でなくなった人・端末をなくしたときは、Web 側の `ADMIN_PASSWORD` を変えて(古い Cookie も無効になる)、`BUNRI_ADMIN_PASSWORD` を更新してビルドし直す。

## 確かめられていないこと(実機でやる)
1. Swift はコンパイルもテストもしていない(hub に Xcode が無い)。最初の `ios.yml`(何も選ばない起動)でビルドと単体テストが通るか。
2. カメラ: TestFlight の実機で、QR確認タブ → iOS の許可ダイアログ → 映像 → 別端末の QR を読めるか。Simulator にはカメラが無い。
3. 本物のサーバーでのログイン: 303 を追わずに Set-Cookie を取れること、その Cookie で管理 API が通ること(単体テストはモックのみ)。URLProtocol のテストが 303 を勝手に追わないことも CI で確かめる。
4. 抽選の通し: 新しいセッション → 開始 → 引く → 終了 → 終了を取り消す。投影ページ(/bingo/stage)と来場者の画面に反映されること。
5. 24 時間: Cookie が切れたあとの最初の操作で、自動のログインし直しが働くか。
6. 向き: iPhone は縦・横を許可している。縦だけで足りるなら絞る。

## 公開前の手順(リードが本人の了承を取ってから)
下の `<owner>/<repo>` は GitHub のリポジトリ(例 Mumicanso8323/bunri-staff-app)。値は表示しない。
1. **GitHub リポジトリ**: `gh repo create <owner>/<repo> --private --source /data/ashwell/bunri-staff-app --push`(private 推奨)。
2. **Apple Developer に Bundle ID**: `com.yusukedoi.bunrisaistaff` を登録(ccf は ASC API の `POST /v1/bundleIds`・platform IOS で登録した。capability 追加なし。Developer サイトの Identifiers からでも可)。
3. **App Store Connect にアプリ**(本人の1操作・API では作れない): https://appstoreconnect.apple.com/apps →「+」→「新規 App」: プラットフォーム iOS・名前「文理祭運営」・言語 日本語・バンドル ID `com.yusukedoi.bunrisaistaff`・SKU `bunrisai-staff`・ユーザアクセス「アクセス制限なし」。
4. **Environment「testflight」**(リポジトリの Settings → Environments)に secret。ccf・hub-ios と同じ値を使う: `ASC_KEY_ID`・`ASC_ISSUER_ID`・`ASC_KEY_P8`・`APPLE_TEAM_ID`。このアプリ専用に `BUNRI_ADMIN_PASSWORD`(Web 側の ADMIN_PASSWORD と同じ値・無いと testflight ジョブが止まる)。任意で `STAFF_TESTER_EMAIL`(内部グループに入れる人のメール。カンマ区切りで複数)。値はチャットに貼らない(`gh secret set --env testflight` で hub の既存の控えから流すか、本人が画面で入れる)。
5. **署名の証明書**(`IOS_DEV_CERT_P12`・`IOS_DEV_CERT_PASSWORD`): ccf の docs/ios.md「署名」と同じ。チームで共有の1枚を、このリポジトリの Environment にも置く(ccf で作った p12 を再利用するのが、証明書を増やさない)。新規に作るなら 1) hub: `python3 ios/scripts/signing_cert.py keypair <一時フォルダ>` 2) `gh workflow run ios -R <owner>/<repo> --ref main -f dev_cert=true -f recipient="$(cat <一時フォルダ>/recipient.pem)"` 3) `gh run download <run> -n dev-cert-sealed -D <一時フォルダ>` → `python3 ios/scripts/signing_cert.py install <一時フォルダ> <一時フォルダ>/dev-cert.sealed.json <owner>/<repo>` で secret を置き、手元の鍵と包みを消す。
6. **先にテスト**: `gh workflow run ios -R <owner>/<repo> --ref main`(何も選ばない)→ 単体テストと未署名ビルドが通ることを確かめる。ここで Swift の書き間違いが出たら直す。
7. **TestFlight へ**: `gh workflow run ios -R <owner>/<repo> --ref main -f testflight=true`。下ごしらえが内部グループ「文理祭委員」(全ビルド自動配布)を用意し、`STAFF_TESTER_EMAIL` の人を入れる。Apple の処理に 10〜30 分。
8. **委員の招待**: 内部テスターは App Store Connect の「ユーザとアクセス」に入っている人だけ。委員を「ユーザ」として招待(役割は「マーケティング」か「開発者」など最小で足りる。「App Store Connect へのアクセス」だけでよい)→ 本人がメールの招待を承諾 → グループ「文理祭委員」に追加(`STAFF_TESTER_EMAIL` で入るのは招待承諾済みの人だけ。409 で保留になった人は TestFlight 画面から足す)→ 各自が TestFlight アプリで「文理祭運営」をインストール。人数が多いなら、ユーザ招待は本人の判断(外向き)。
9. **当日の前に**: 上の「確かめられていないこと」を実機で。
