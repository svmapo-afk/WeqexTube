#!/usr/bin/env python3
"""Install two isolated VLESS REALITY inbounds via local 3x-ui v3.11 API.

Run on your own Ubuntu VPS: sudo python3 weqex_3xui_inbounds.py
The script does not download or execute any code from the Internet.
"""
import getpass
import http.cookiejar
import json
import os
import re
import secrets
import shutil
import socket
import sqlite3
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

# Management API is accessed only through 127.0.0.1; no external IP is hardcoded.
XRAY = "/usr/local/x-ui/bin/xray-linux-amd64"
DB = Path("/etc/x-ui/x-ui.db")
TARGET = "www.microsoft.com"
PROFILES = [("WEQEX-Turkey-XHTTP", 443, "xhttp"),
            ("WEQEX-Turkey-Vision", 8443, "tcp")]


def abort(message):
    raise RuntimeError(message)


def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def panel_config():
    output = command("x-ui", "settings")
    port = re.search(r"^port:\s*(\d+)\s*$", output, re.M)
    path = re.search(r"^webBasePath:\s*(\S+)\s*$", output, re.M)
    if not (port and path):
        abort("Не удалось определить порт и Web Base Path через 'x-ui settings'.")
    basepath = path.group(1)
    if not (basepath.startswith("/") and basepath.endswith("/")):
        abort("Некорректный Web Base Path панели.")
    return int(port.group(1)), basepath


def backup_database():
    if not DB.is_file():
        abort(f"Не найдена база {DB}")
    backup = DB.parent / ("x-ui.backup-" + time.strftime("%Y%m%d-%H%M%S") + ".db")
    oldmask = os.umask(0o077)
    try:
        source = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
        destination = sqlite3.connect(backup)
        try:
            source.backup(destination)
        finally:
            destination.close()
            source.close()
        os.chmod(backup, 0o600)
    finally:
        os.umask(oldmask)
    return backup


def generate_reality_pair():
    if not os.path.isfile(XRAY):
        abort(f"Не найден исполняемый файл Xray: {XRAY}")
    output = command(XRAY, "x25519")
    private = re.search(r"(?im)^\s*(?:PrivateKey|Private key)\s*:\s*(\S+)", output)
    public = re.search(r"(?im)^\s*(?:Password|PublicKey|Public key)\s*:\s*(\S+)", output)
    if not (private and public):
        abort("Не удалось распознать ключи из 'xray x25519' (ключи не выводились).")
    return private.group(1), public.group(1)


class PanelAPI:
    def __init__(self, port, path):
        # ONLY loopback. Public IP SSL cert cannot validate for 127.0.0.1.
        self.base = f"https://127.0.0.1:{port}{path}"
        jar = http.cookiejar.CookieJar()
        ssl_local = ssl._create_unverified_context()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(jar),
            urllib.request.HTTPSHandler(context=ssl_local))
        self.csrf = None

    def call(self, route, fields=None):
        url = self.base + route.lstrip("/")
        headers = {"Accept": "application/json", "User-Agent": "weqex-local-setup/1.0"}
        data = None
        if fields is not None:
            if route == "panel/api/inbounds/add":
                data = json.dumps(fields, separators=(",", ":")).encode("utf-8")
                headers["Content-Type"] = "application/json"
            else:
                data = urllib.parse.urlencode(fields).encode("utf-8")
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            if self.csrf:
                headers["X-CSRF-Token"] = self.csrf
        request = urllib.request.Request(url, data=data, headers=headers)
        try:
            with self.opener.open(request, timeout=15) as response:
                body = response.read(1048576)
                result = json.loads(body)
        except urllib.error.HTTPError as e:
            abort(f"Ошибка HTTP {e.code} для {route}: {e.read(300)!r}")
        except (urllib.error.URLError, json.JSONDecodeError) as e:
            abort(f"Ошибка запроса {route}: {e}")
        if not isinstance(result, dict) or not result.get("success"):
            abort(f"3x-ui отклонила {route}: {result.get('msg', result)}")
        return result.get("obj")

    def get_csrf(self):
        self.csrf = self.call("csrf-token")
        if not isinstance(self.csrf, str) or not self.csrf:
            abort("Не удалось получить CSRF-токен")

    def login(self):
        self.get_csrf()
        username = input("Логин администратора 3x-ui: ").strip()
        password = getpass.getpass("Пароль 3x-ui (не отображается): ")
        otp = getpass.getpass("Код 2FA, если включена (иначе Enter): ").strip()
        fields = {"username": username, "password": password}
        if otp:
            fields["twoFactorCode"] = otp
        self.call("login", fields)
        self.get_csrf()


def make_profile(name, port, network):
    private, public = generate_reality_pair()
    short_id = secrets.token_hex(8)
    client_id = str(uuid.uuid4())
    settings = {
        "clients": [{
            "id": client_id,
            "email": f"weqex-{network}-{secrets.token_hex(4)}",
            "flow": "xtls-rprx-vision" if network == "tcp" else "",
            "limitIp": 0,
            "totalGB": 0,
            "expiryTime": 0,
            "enable": True,
        }],
        "decryption": "none",
        "fallbacks": [],
    }
    reality = {
        "show": False,
        "xver": 0,
        "dest": TARGET + ":443",
        "serverNames": [TARGET],
        "privateKey": private,
        "shortIds": [short_id],
        "settings": {
            "publicKey": public,
            "fingerprint": "chrome",
            "serverName": TARGET,
            "spiderX": "/",
        },
    }
    stream = {"network": network, "security": "reality", "realitySettings": reality}
    if network == "xhttp":
        stream["xhttpSettings"] = {
            "path": "/" + secrets.token_hex(12) + "/", "mode": "auto"
        }
    else:
        stream["tcpSettings"] = {"acceptProxyProtocol": False, "header": {"type": "none"}}
    return {
        "enable": True, "remark": name, "listen": "", "port": port,
        "protocol": "vless", "expiryTime": 0, "total": 0,
        "settings": settings, "streamSettings": stream,
        "sniffing": {"enabled": True, "destOverride": ["http", "tls", "quic"], "metadataOnly": False},
    }


def main():
    if os.geteuid() != 0:
        abort("Запусти скрипт от root: sudo python3 weqex_3xui_inbounds.py")
    port, path = panel_config()
    print(f"Локальная панель найдена: 127.0.0.1:{port} (HTTPS)")
    print("Будут созданы: XHTTP/REALITY:443 и TCP/Vision/REALITY:8443")
    print("Существующие настройки и пользователи не удаляются.")
    api = PanelAPI(port, path)
    api.login()
    rows = api.call("panel/api/inbounds/list")
    if not isinstance(rows, list):
        abort("API вернул неожиданный список Inbound")
    used_ports = {int(x["port"]): x.get("remark", "без названия") for x in rows}
    to_create = []
    for name, p, proto in PROFILES:
        if p in used_ports:
            if used_ports[p] == name:
                print(f"Уже создан: {name}, порт {p}; пропускаю")
                continue
            abort(f"Порт {p} занят Inbound '{used_ports[p]}'; ничего нового не создано")
        to_create.append((name, p, proto))
    if not to_create:
        print("Оба Inbound уже существуют. Изменений нет.")
        return
    for _, p, _ in to_create:
        for family, type_, proto, _, addr in socket.getaddrinfo("127.0.0.1", p, type=socket.SOCK_STREAM):
            sock = socket.socket(family, socket.SOCK_STREAM)
            sock.settimeout(0.5)
            try:
                if sock.connect_ex(addr) == 0:
                    abort(f"Порт {p} занят другим процессом. Новых Inbound не создано")
            finally:
                sock.close()
    backup = backup_database()
    print(f"Резервная копия базы: {backup}")
    for name, p, proto in to_create:
        profile = make_profile(name, p, proto)
        result = api.call("panel/api/inbounds/add", {
            "enable": True, "remark": name, "listen": "", "port": p,
            "protocol": "vless", "expiryTime": 0, "total": 0,
            "settings": profile["settings"],
            "streamSettings": profile["streamSettings"],
            "sniffing": profile["sniffing"],
        })
        print(f"Создано: {name} (порт {p})")
    print("Проверка API:")
    rows_after = api.call("panel/api/inbounds/list")
    for name, p, _ in PROFILES:
        row = next((r for r in rows_after if int(r.get("port", -1)) == p and r.get("remark") == name), None)
        print(f" - {name}: {'есть в панели' if row else 'НЕ НАЙДЕН'}")
    print("\nОткрой 3x-ui -> Inbounds. Для HAPP копируй ссылку каждого клиента из панели.")
    print("Проверь порты: ss -tlnp | grep -E ':(443|8443)[[:space:]]'")
    print("Если порты открыты локально, но недоступны снаружи, проверь firewall провайдера VPS.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, sqlite3.Error, OSError) as e:
        print(f"ОШИБКА: {e}", file=sys.stderr)
        sys.exit(1)