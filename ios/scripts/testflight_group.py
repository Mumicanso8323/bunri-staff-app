#!/usr/bin/env python3
"""TestFlight の下ごしらえ(CI の testflight ジョブが archive の前に呼ぶ)。何度呼んでも同じ結果になる。

1. App Store Connect にアプリ(BUNDLE_ID)があるかを確かめる。無ければ、本人がやる1操作を出して止まる。
2. 内部テストのグループ(GROUP_NAME、既定「文理祭委員」)を、全ビルド自動配布(hasAccessToAllBuilds)で用意する。
3. TESTER_EMAIL(カンマ区切りで複数可)があれば、そのテスターをグループに入れる(メールアドレスはログに出さない)。

環境変数: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, BUNDLE_ID, GROUP_NAME(任意), TESTER_EMAIL(任意・カンマ区切り)
依存: pyjwt[crypto]
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

API = "https://api.appstoreconnect.apple.com"


def token() -> str:
    key = open(os.environ["ASC_KEY_PATH"]).read()
    now = int(time.time())
    return jwt.encode({"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"},
                      key, algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})


def call(method: str, path: str, body=None, query=None):
    url = API + path + ("?" + urllib.parse.urlencode(query) if query else "")
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"text": raw[:300].decode(errors="replace")}


def must(code, out, what):
    if code >= 300:
        errors = [f"{x.get('status')} {x.get('code')} {x.get('detail')}" for x in out.get("errors", [])] or [str(out)[:300]]
        sys.exit(f"::error::{what} に失敗({code}): " + " / ".join(errors))
    return out


def main():
    bundle = os.environ["BUNDLE_ID"]
    group_name = os.environ.get("GROUP_NAME") or "文理祭委員"
    emails = [e.strip() for e in (os.environ.get("TESTER_EMAIL") or "").split(",") if e.strip()]

    out = must(*call("GET", "/v1/apps", query={"filter[bundleId]": bundle, "fields[apps]": "name,bundleId"}), "アプリの確認")
    apps = [a for a in out.get("data", []) if a["attributes"].get("bundleId") == bundle]
    if not apps:
        sys.exit(f"::error::App Store Connect に {bundle} のアプリがまだありません。"
                 "本人が App Store Connect の「アプリ」→「+」→「新規 App」で作ってから、もう一度起動してください(docs/ios.md)。")
    app_id = apps[0]["id"]
    print(f"アプリ: {apps[0]['attributes'].get('name')} ({app_id})")

    out = must(*call("GET", f"/v1/apps/{app_id}/betaGroups",
                     query={"fields[betaGroups]": "name,isInternalGroup,hasAccessToAllBuilds", "limit": "200"}), "グループの確認")
    group = next((g for g in out.get("data", []) if g["attributes"].get("name") == group_name), None)
    if group is None:
        out = must(*call("POST", "/v1/betaGroups", {"data": {
            "type": "betaGroups",
            "attributes": {"name": group_name, "isInternalGroup": True, "hasAccessToAllBuilds": True},
            "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}}), "グループの作成")
        group = out["data"]
        print(f"グループ「{group_name}」を作った(内部・全ビルド自動配布)")
    else:
        a = group["attributes"]
        print(f"グループ「{group_name}」はある(内部={a.get('isInternalGroup')}・全ビルド={a.get('hasAccessToAllBuilds')})")
        if a.get("isInternalGroup") and not a.get("hasAccessToAllBuilds"):
            must(*call("PATCH", f"/v1/betaGroups/{group['id']}", {"data": {
                "type": "betaGroups", "id": group["id"], "attributes": {"hasAccessToAllBuilds": True}}}), "全ビルド自動配布の設定")
            print("全ビルド自動配布をオンにした")
    gid = group["id"]

    if not emails:
        print("TESTER_EMAIL が無いので、テスターは足さない")
        return
    for n, email in enumerate(emails, 1):
        add_tester(gid, email, n)


def add_tester(gid, email, n):
    out = must(*call("GET", "/v1/betaTesters",
                     query={"filter[email]": email, "filter[betaGroups]": gid, "limit": "5"}), f"テスター{n}の確認")
    if out.get("data"):
        print(f"テスター{n}はもうグループにいる")
        return
    # 既存テスターを relationships で内部グループへ足すと 409(Tester(s) cannot be assigned)になる。
    # メールアドレスごとグループ付きで作り直す形なら、既存テスターでも通る(ccf 10/12 実測)。
    # 内部グループに入れられるのは App Store Connect のユーザ(招待を受けた人)だけ。
    code, res = call("POST", "/v1/betaTesters", {"data": {
        "type": "betaTesters", "attributes": {"email": email},
        "relationships": {"betaGroups": {"data": [{"type": "betaGroups", "id": gid}]}}}})
    if code == 409:
        print(f"::warning::テスター{n}の追加は 409 で保留(App Store Connect のユーザに招待済みか確かめ、TestFlight 画面から足す)")
        return
    must(code, res, f"テスター{n}の追加")
    print(f"テスター{n}をグループに入れた")


if __name__ == "__main__":
    main()
