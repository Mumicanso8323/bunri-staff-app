#!/usr/bin/env python3
"""archive に使う Apple Development 証明書を1枚だけ作り、GitHub の secret に置く(1回だけの下ごしらえ)。

なぜ: CI は毎回まっさらなマシンなので、自動署名の archive は鍵の無い証明書を見つけられず、
そのたびに新しい Apple Development 証明書(Created via API)を作る。チームの上限(reforge-ios・hub-ios と共有)に
すぐ達する。そこで1枚だけ作って p12 を secret に置き、testflight ジョブは archive の前にそれを一時キーチェーンに入れる。
配布用の署名は、export の段で Apple のクラウド管理の Distribution 証明書が使われる(これは端末に何も作らない)。

鍵の受け渡し(値はどこにも表示しない・git に入れない):
  1. hub: `signing_cert.py keypair DIR`            受け取り用の RSA 鍵を DIR に作る(公開鍵 DIR/recipient.pem)
  2. CI : `signing_cert.py create RECIPIENT OUT`   ASC API で証明書を作り、p12 とパスワードを受け取り用の公開鍵で包んで OUT に書く
         (ios.yml の dev-cert ジョブ。ubuntu で動く。OUT は artifact で hub へ)
  3. hub: `signing_cert.py install DIR BUNDLE REPO...`  包みを開き、各リポジトリの Environment「testflight」に
         IOS_DEV_CERT_P12(base64)と IOS_DEV_CERT_PASSWORD を `gh secret set` で置く。手元の鍵と包みは消す。

環境変数(create): ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH
依存: openssl、pyjwt[crypto](create だけ)、gh(install だけ)
"""
import base64
import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

API = "https://api.appstoreconnect.apple.com"


def run(*args, stdin: bytes | None = None) -> bytes:
    return subprocess.run(args, input=stdin, check=True, capture_output=True).stdout


def asc(method: str, path: str, body=None):
    import jwt
    key = open(os.environ["ASC_KEY_PATH"]).read()
    now = int(time.time())
    tok = jwt.encode({"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"},
                     key, algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"text": raw[:300].decode(errors="replace")}


def keypair(dir_: str):
    os.makedirs(dir_, mode=0o700, exist_ok=True)
    run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072", "-out", f"{dir_}/recipient.key")
    os.chmod(f"{dir_}/recipient.key", 0o600)
    run("openssl", "pkey", "-in", f"{dir_}/recipient.key", "-pubout", "-out", f"{dir_}/recipient.pem")
    print(f"受け取り用の公開鍵: {dir_}/recipient.pem")


def create(recipient: str, out: str):
    with tempfile.TemporaryDirectory() as t:
        os.chmod(t, 0o700)
        run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", f"{t}/dev.key")
        csr = run("openssl", "req", "-new", "-key", f"{t}/dev.key", "-subj", "/CN=bunrisai-staff CI/O=CI", "-outform", "PEM")
        code, res = asc("POST", "/v1/certificates", {"data": {"type": "certificates", "attributes": {
            "certificateType": "DEVELOPMENT", "csrContent": csr.decode()}}})
        if code >= 300:
            errors = [f"{x.get('status')} {x.get('code')} {x.get('detail')}" for x in res.get("errors", [])] or [str(res)[:300]]
            sys.exit("::error::証明書を作れない: " + " / ".join(errors))
        attrs = res["data"]["attributes"]
        print(f"作った証明書: id={res['data']['id']} 名前={attrs.get('name')} 期限={attrs.get('expirationDate')}")
        with open(f"{t}/dev.cer", "wb") as f:
            f.write(base64.b64decode(attrs["certificateContent"]))
        run("openssl", "x509", "-inform", "DER", "-in", f"{t}/dev.cer", "-out", f"{t}/dev.pem")
        password = secrets.token_urlsafe(24)
        # macOS のキーチェーンが読める形(SHA1-3DES・MAC は sha1)
        subprocess.run(["openssl", "pkcs12", "-export", "-inkey", f"{t}/dev.key", "-in", f"{t}/dev.pem", "-out", f"{t}/dev.p12",
                        "-keypbe", "PBE-SHA1-3DES", "-certpbe", "PBE-SHA1-3DES", "-macalg", "sha1", "-passout", "env:P12_PASS"],
                       check=True, capture_output=True, env={**os.environ, "P12_PASS": password})
        bundle = json.dumps({"p12": base64.b64encode(open(f"{t}/dev.p12", "rb").read()).decode(),
                             "password": password, "id": res["data"]["id"],
                             "expires": attrs.get("expirationDate")}).encode()
        # 包む: ランダムな鍵で AES、その鍵を受け取り用の公開鍵(RSA-OAEP)で包む
        aes = secrets.token_bytes(32)
        iv = secrets.token_bytes(16)
        body = run("openssl", "enc", "-aes-256-cbc", "-K", aes.hex(), "-iv", iv.hex(), stdin=bundle)
        wrapped = run("openssl", "pkeyutl", "-encrypt", "-pubin", "-inkey", recipient,
                      "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256", stdin=aes)
        with open(out, "w") as f:
            json.dump({"key": base64.b64encode(wrapped).decode(), "iv": iv.hex(), "body": base64.b64encode(body).decode()}, f)
    print(f"包んだもの: {out}(受け取り用の秘密鍵が無いと開けない)")


def install(dir_: str, bundle_path: str, repos: list[str]):
    sealed = json.load(open(bundle_path))
    aes = run("openssl", "pkeyutl", "-decrypt", "-inkey", f"{dir_}/recipient.key",
              "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256", stdin=base64.b64decode(sealed["key"]))
    bundle = json.loads(run("openssl", "enc", "-d", "-aes-256-cbc", "-K", aes.hex(), "-iv", sealed["iv"],
                            stdin=base64.b64decode(sealed["body"])))
    for repo in repos:
        for name, value in (("IOS_DEV_CERT_P12", bundle["p12"]), ("IOS_DEV_CERT_PASSWORD", bundle["password"])):
            run("gh", "secret", "set", name, "--env", "testflight", "--repo", repo, stdin=value.encode())
        print(f"{repo}: IOS_DEV_CERT_P12・IOS_DEV_CERT_PASSWORD を置いた(証明書 id={bundle['id']} 期限={bundle['expires']})")
    os.remove(bundle_path)
    shutil.rmtree(dir_)
    print("手元の受け取り用の鍵と包みを消した")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "keypair" and len(sys.argv) == 3:
        keypair(sys.argv[2])
    elif cmd == "create" and len(sys.argv) == 4:
        create(sys.argv[2], sys.argv[3])
    elif cmd == "install" and len(sys.argv) >= 5:
        install(sys.argv[2], sys.argv[3], sys.argv[4:])
    else:
        sys.exit(__doc__)
