import SwiftUI

/// 使い方。画面の中だけで完結する(ネットにつながっていなくても読める)。
/// ボタン名は LotteryView・VerifyView と同じ言葉にそろえてある。
struct GuideView: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("このタブは、ネットにつながっていなくても読めます。").font(.footnote).foregroundStyle(.secondary)

                    GuideCard(title: "はじめに", symbol: "key.fill") {
                        GuideText("アプリを開くと、自動でログインします。パスワードを入れる必要はありません。")
                        GuideText("画面の上に赤い帯が出たら、ログインできていません。「やり直す」を押してください。")
                    }

                    GuideCard(title: "1. 抽選の流れ(「抽選」タブ)", symbol: "dice") {
                        GuideSteps([
                            "「新しいセッション」を押します。状態が「待機中」になります。",
                            "「開始」を押します。状態が「進行中」になります。来場者の画面にカードが出ます。",
                            "「次の番号を引く」を押します。番号が大きく出ます。ホールで読み上げます。",
                            "手順3を、ビンゴが終わるまで繰り返します。押したあと、3秒ほどは押せません。",
                            "おわりは、画面の一番下の「終了」を押します。確認が出るので「終了する」を押します。",
                        ])
                        Warning(title: "「開始」と「終了」を間違えないで", lines: [
                            "会場のテストで、「開始」のつもりで「終了」を押しました。作ったばかりのセッションが、5秒で終わりました。",
                            "押す前に、ボタンの名前を読んでください。",
                            "このアプリでは、「終了」は一番下にあります。色は赤です。押すと確認が出ます。",
                        ])
                        GuideText("間違えて終了したとき").emphasized()
                        GuideSteps([
                            "あわてなくて大丈夫です。番号と参加者は消えていません。",
                            "「抽選」タブで「終了を取り消す(同じセッションを再開)」を押します。確認で「進行中に戻す」を押します。",
                            "同じセッションが「進行中」に戻ります。続きから引けます。",
                        ])
                        GuideText("「新しいセッション」を押すのは、おすすめしません。前のセッションの参加者は「過去のセッション」になって、景品の対象外になります。")
                    }

                    GuideCard(title: "2. 景品の確認(「QR確認」タブ)", symbol: "qrcode.viewfinder") {
                        GuideSteps([
                            "ビンゴした人に、画面のQRを出してもらいます。",
                            "「QR確認」タブを開き、カメラの枠にQRを入れます。",
                            "「ビンゴ達成」と出たら、景品をお渡しします。",
                            "「景品受取済みにする」を押します。「受け取り済みにしました」と出れば記録できています。",
                            "「次を読む」を押すと、次の人を読めます。",
                        ])
                        GuideText("出る表示").emphasized()
                        GuideBullets([
                            "「すでに受け取り済み」:もう景品を渡した人です。同じQRをもう一度読むと、この表示になります。渡さないでください。",
                            "「まだビンゴではありません」:ビンゴになっていません。当たったマスを本人がタップして開けているか、確かめてもらいます。",
                            "「過去のセッションです」:前のセッションの人です。景品の対象外です。",
                            "「無効な QR」:ビンゴのQRではありません。",
                            "「見つかりません」:その人の記録がありません。",
                        ])
                        GuideText("読めないとき").emphasized()
                        GuideBullets([
                            "QRを枠の真ん中に入れて、手前や奥に動かして、ピントを合わせます。",
                            "相手の画面を明るくしてもらいます。画面に光が映りこんでいたら、少し傾けます。",
                            "カメラが動かないときは、iPhoneの「設定」でカメラを許可します(画面の「設定を開く」からも行けます)。",
                            "それでも読めないときは、画面の下の「読めないときは、IDを入力」にIDを入れて「検索」を押します。",
                        ])
                    }

                    GuideCard(title: "トーナメント(「トーナメント」タブ)", symbol: "trophy") {
                        GuideText("組み合わせは、最初は空です。クイズで先に抜けたチームから順に、枠に入れていきます。").emphasized()
                        GuideSteps([
                            "画面の上の「抜けた順に入れる」に、まだ枠に入っていないチームが並びます。クイズで抜けたチームの名前のボタンを1つ押すと、次の空き枠に入ります(確認は出ません)。",
                            "押し間違えたら、すぐ「1手戻す」を押します。",
                            "全部の枠が埋まると、この欄は消えます。空き枠のある試合には、勝者のボタンは出ません。",
                        ])
                        GuideText("全部の枠が埋まってから、次の手順で勝敗を入れます。")
                        GuideSteps([
                            "上の回戦の名前(「1回戦」など)を押すと、その回戦の試合が並びます。",
                            "試合が終わったら、勝ったチームの大きいボタンを押します。",
                            "確認が出るので、チーム名を読んで「勝ちにする」を押します。自動で次の回戦に上がり、ホールの画面にも1〜2秒で出ます。",
                        ])
                        GuideText("押し間違えたとき").emphasized()
                        GuideBullets([
                            "「1手戻す」を押すと、直前の操作が取り消されます。",
                            "別のチームに直すときは、正しいほうのチームのボタンを押し直します。この試合に絡む後の勝ち上がりは、取り消されます。",
                            "「勝者を取り消す」で、その試合を未決着に戻せます。",
                        ])
                        GuideText("「いまの試合にする」を押すと、ホールの画面で両チームの枠が光ります。勝者を入れると、次の試合に移ります。")
                        GuideText("「シード(不戦勝)」の枠は、相手が自動で勝ち上がります。ボタンはありません。")
                        GuideText("チームの入れ替え・枠・回戦名は、このアプリでは変えられません。必要なときはリードに知らせてください。")
                        Warning(title: "一番下の「リセット」は押さないで", lines: [
                            "チーム・勝ち上がりが、すべて初期状態に戻ります。本番中は押しません。",
                        ])
                    }

                    GuideCard(title: "3. ステージの投影", symbol: "tv") {
                        GuideSteps([
                            "ホールのパソコンで、次のページを開きます。",
                            "キーボードの F11 を押して、全画面にします。",
                        ])
                        Text("https://bunrisai.jp/bingo/stage").font(.callout.monospaced()).textSelection(.enabled)
                        GuideText("引いた番号は、自動で投影の画面にも出ます。")
                    }

                    GuideCard(title: "4. 来場者のしくみ", symbol: "person.2.fill") {
                        GuideText("来場者は、読み上げられた番号のマスを、自分でタップして開けます。")
                        GuideText("タップしないと、そろっていてもビンゴになりません。")
                        Warning(title: "「ビンゴなのに出ない」と言われたら", lines: [
                            "当たったマスを、タップして開けたか聞いてください。",
                            "開けていなければ、タップしてもらいます。一列そろうと、QRが出ます。",
                        ])
                    }

                    GuideCard(title: "5. 困ったとき", symbol: "lifepreserver") {
                        GuideBullets([
                            "「接続待ち…」と出たとき:電波が弱いです。電波のよい場所へ行くか、少し待ちます。つながると消えます。",
                            "「通信できませんでした」と出たとき:もう一度押す前に、番号の一覧を見てください。引けていることがあります。同じ番号は引かれません。",
                            "景品の記録で通信できなかったとき:もう一度QRを読みます。「すでに受け取り済み」と出れば、記録できています。",
                            "「ログインが切れました」と出たとき:自動でログインし直します。もう一度ボタンを押してください。",
                            "赤い帯が消えないとき:アプリを一度閉じて、開き直します。それでもだめなら、リードに知らせてください。",
                            "ログインを何度もやり直さないでください。続けて失敗すると、10分ほど止められます。",
                        ])
                    }
                }
                .padding(16)
            }
            .navigationTitle("使い方")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct GuideCard<Content: View>: View {
    let title: String
    let symbol: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: symbol).font(.title3.bold())
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct GuideText: View {
    let text: String
    private var isBold = false
    init(_ text: String) { self.text = text }
    func emphasized() -> GuideText { var copy = self; copy.isBold = true; return copy }
    var body: some View {
        Text(text).font(isBold ? .body.bold() : .body).fixedSize(horizontal: false, vertical: true)
    }
}

private struct GuideSteps: View {
    let items: [String]
    init(_ items: [String]) { self.items = items }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("\(index + 1)").font(.callout.bold()).foregroundStyle(.white)
                        .frame(width: 24, height: 24).background(Color.accentColor, in: Circle())
                    Text(item).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

private struct GuideBullets: View {
    let items: [String]
    init(_ items: [String]) { self.items = items }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(items, id: \.self) { item in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("・")
                    Text(item).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

private struct Warning: View {
    let title: String
    let lines: [String]
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: "exclamationmark.triangle.fill").font(.headline).foregroundStyle(.red)
            ForEach(lines, id: \.self) { Text($0).fixedSize(horizontal: false, vertical: true) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Color.red.opacity(0.5), lineWidth: 1.5))
    }
}
