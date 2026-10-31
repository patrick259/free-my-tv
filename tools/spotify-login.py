#!/usr/bin/env python3
"""One-time Spotify sign-in for the suggestions (PKCE, no client secret).

Reads spotify_client_id from config.properties, opens Spotify's sign-in page in the browser, catches the redirect on
http://127.0.0.1:8888/callback and writes spotify_refresh_token back into config.properties. The token is never
printed. Add that redirect URI to the app in the Spotify developer dashboard first.

    python3 tools/spotify-login.py [config.properties]
"""
import base64, hashlib, http.server, json, os, re, secrets, sys, urllib.parse, urllib.request, webbrowser

CONFIG = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "config.properties")
REDIRECT = "http://127.0.0.1:8888/callback"
SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing user-top-read " \
         "user-read-recently-played playlist-read-private"


def prop(text, key):
    m = re.findall(r"^\s*%s\s*=(.*)$" % key, text, re.M)
    return m[-1].strip() if m else ""


def main():
    text = open(CONFIG).read()
    client = prop(text, "spotify_client_id")
    if not client:
        sys.exit("Add spotify_client_id to %s first." % CONFIG)

    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            ok = q.get("state", [""])[0] == state and "code" in q
            if ok:
                result["code"] = q["code"][0]
            else:
                result["error"] = q.get("error", ["unexpected reply"])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            msg = "Signed in. You can close this tab." if ok else "Sign-in failed: " + result["error"]
            self.wfile.write(("<h2 style='font-family:-apple-system'>%s</h2>" % msg).encode())

        def log_message(self, *a):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 8888), Handler)
    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": client, "response_type": "code", "redirect_uri": REDIRECT, "scope": SCOPES,
        "code_challenge_method": "S256", "code_challenge": challenge, "state": state})
    print("Opening Spotify sign-in in your browser...")
    webbrowser.open(url)
    while not result:
        server.handle_request()
    if "error" in result:
        sys.exit("Sign-in failed: " + result["error"])

    body = urllib.parse.urlencode({"grant_type": "authorization_code", "code": result["code"], "redirect_uri": REDIRECT,
                                   "client_id": client, "code_verifier": verifier}).encode()
    req = urllib.request.Request("https://accounts.spotify.com/api/token", body,
                                 {"Content-Type": "application/x-www-form-urlencoded"})
    token = json.load(urllib.request.urlopen(req))
    refresh = token.get("refresh_token")
    if not refresh:
        sys.exit("Spotify sent no refresh token.")

    line = "spotify_refresh_token=" + refresh
    if re.search(r"^\s*spotify_refresh_token\s*=", text, re.M):
        text = re.sub(r"^\s*spotify_refresh_token\s*=.*$", lambda m: line, text, flags=re.M)
    else:
        text = text.rstrip("\n") + "\n" + line + "\n"
    open(CONFIG, "w").write(text)

    me = json.load(urllib.request.urlopen(urllib.request.Request(
        "https://api.spotify.com/v1/me", headers={"Authorization": "Bearer " + token["access_token"]})))
    print("Signed in as %s (%s). Saved the refresh token to %s." % (me.get("display_name"), me.get("product"), CONFIG))


if __name__ == "__main__":
    main()
