import SwiftUI
import WebKit

/// 1つのタブの WebView の状態。タブを切り替えても WebView は作り直さない。
@MainActor
final class WebModel: ObservableObject {
    let start: URL
    @Published var failure: String?
    @Published var isLoading = false
    fileprivate(set) var webView: WKWebView?

    init(start: URL) { self.start = start }

    func reload() {
        failure = nil
        guard let webView else { return }
        // 最初の読み込みが失敗したときは url が無いので、開始 URL を読み直す
        if webView.url == nil { webView.load(URLRequest(url: start)) } else { webView.reload() }
    }
}

struct StaffWebScreen: View {
    @ObservedObject var model: WebModel

    var body: some View {
        ZStack {
            StaffWebView(model: model)
            if let failure = model.failure {
                VStack(spacing: 16) {
                    Image(systemName: "wifi.exclamationmark").font(.system(size: 40))
                    Text("読み込めませんでした").font(.headline)
                    Text(failure).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    Button("再読み込み") { model.reload() }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("reload")
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.background)
            }
        }
    }
}

struct StaffWebView: UIViewRepresentable {
    @ObservedObject var model: WebModel

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // ログインの Cookie は端末に残す(次の起動でもログイン済み)。
        config.websiteDataStore = WKWebsiteDataStore.default()
        // カメラの映像をページ内で再生する(全画面に切り替えない)。
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true

        let refresh = UIRefreshControl()
        refresh.addTarget(context.coordinator, action: #selector(Coordinator.pulled(_:)), for: .valueChanged)
        web.scrollView.refreshControl = refresh

        model.webView = web
        web.load(URLRequest(url: model.start))
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let model: WebModel
        init(model: WebModel) { self.model = model }

        @objc func pulled(_ sender: UIRefreshControl) {
            Task { @MainActor in model.reload() }
        }

        private func finish(_ webView: WKWebView) {
            webView.scrollView.refreshControl?.endRefreshing()
            Task { @MainActor in model.isLoading = false }
        }

        private func fail(_ webView: WKWebView, _ error: Error) {
            finish(webView)
            let ns = error as NSError
            // 遷移を自分で打ち切ったとき(Safari に渡した・リンクを開き直したとき)は失敗にしない
            if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { return }
            if ns.domain == "WebKitErrorDomain" && ns.code == 102 { return }
            Task { @MainActor in model.failure = ns.localizedDescription }
        }

        // MARK: WKNavigationDelegate

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            let url = navigationAction.request.url
            let isMain = navigationAction.targetFrame?.isMainFrame ?? true
            switch NavigationPolicy.decide(url: url, isMainFrame: isMain) {
            case .allow:
                // target=_blank など新しい窓を求める遷移は、この WebView で開く
                if navigationAction.targetFrame == nil, let url {
                    webView.load(URLRequest(url: url))
                    decisionHandler(.cancel)
                } else {
                    decisionHandler(.allow)
                }
            case .external:
                if let url { UIApplication.shared.open(url) }
                decisionHandler(.cancel)
            case .cancel:
                decisionHandler(.cancel)
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if navigationResponse.isForMainFrame, let http = navigationResponse.response as? HTTPURLResponse,
               NavigationPolicy.isServerError(statusCode: http.statusCode) {
                finish(webView)
                Task { @MainActor in model.failure = "サーバーが応答できません(\(http.statusCode))" }
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            Task { @MainActor in model.isLoading = true; model.failure = nil }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { finish(webView) }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { fail(webView, error) }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { fail(webView, error) }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { webView.reload() }

        // MARK: WKUIDelegate

        /// カメラ(getUserMedia)は bunrisai.jp のページにだけ許す。OS の許可の確認は、初回に別に出る。
        func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                     initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
                     decisionHandler: @escaping (WKPermissionDecision) -> Void) {
            let ok = type == .camera && MediaPolicy.shouldGrant(scheme: origin.protocol, host: origin.host, port: origin.port)
            decisionHandler(ok ? .grant : .deny)
        }

        /// window.open など。許す場所はこの WebView で、それ以外は Safari へ。
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            let url = navigationAction.request.url
            switch NavigationPolicy.decide(url: url, isMainFrame: true) {
            case .allow: if let url { webView.load(URLRequest(url: url)) }
            case .external: if let url { UIApplication.shared.open(url) }
            case .cancel: break
            }
            return nil
        }
    }
}
