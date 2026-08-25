#!/usr/bin/env python3

import argparse
import json
import re
import signal
import subprocess
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
ADB_SCRIPT = REPO_ROOT / "scripts" / "adb.sh"
PAGE_TEMPLATE = (Path(__file__).parent / "theme-lab.html").read_text(encoding="utf-8")
PROTOTYPES = {
    "baseline",
    "instrument-rail",
    "quiet-dock",
    "immersive-utilities",
    "queue-deck",
    "session-button",
}


def parse_arguments():
    parser = argparse.ArgumentParser(description="Serve Coda's debug Now Playing lab")
    parser.add_argument("serial")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--no-open", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial):
        parser.error("serial contains unsupported characters")
    if not 1024 <= args.port <= 65535:
        parser.error("port must be between 1024 and 65535")
    return args


def run_adb(serial, arguments):
    result = subprocess.run(
        [str(ADB_SCRIPT), "-s", serial, *arguments],
        cwd=REPO_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode:
        raise RuntimeError((result.stderr or result.stdout or "adb failed").strip())
    return result.stdout.strip()


class ThemeLabServer(HTTPServer):
    def __init__(self, address, serial):
        super().__init__(address, ThemeLabHandler)
        self.serial = serial
        self.prototype = "baseline"


class ThemeLabHandler(BaseHTTPRequestHandler):
    server: ThemeLabServer

    def log_message(self, message, *args):
        print(f"{self.address_string()} - {message % args}")

    def send_json(self, status, value):
        body = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/":
            body = (
                PAGE_TEMPLATE
                .replace("__TARGET_SERIAL__", self.server.serial)
                .replace("__CURRENT_PROTOTYPE__", self.server.prototype)
                .encode("utf-8")
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self):
        try:
            if self.path == "/api/open-coda":
                run_adb(
                    self.server.serial,
                    ["shell", "am", "start", "-n", "io.github.iamtoolino.coda/.MainActivity"],
                )
                self.send_json(200, {"ok": True})
            elif self.path == "/api/prototype":
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 4096:
                    raise RuntimeError("invalid request body")
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                prototype = payload.get("prototype")
                if prototype not in PROTOTYPES:
                    raise RuntimeError("unknown Now Playing prototype")
                run_adb(
                    self.server.serial,
                    [
                        "shell",
                        "am",
                        "broadcast",
                        "-a",
                        "io.github.iamtoolino.coda.debug.NOW_PLAYING_PROTOTYPE",
                        "-n",
                        "io.github.iamtoolino.coda/.debug.NowPlayingPrototypeReceiver",
                        "--es",
                        "prototype",
                        prototype,
                    ],
                )
                self.server.prototype = prototype
                self.send_json(200, {"ok": True, "prototype": prototype})
            else:
                self.send_json(404, {"error": "not found"})
        except (RuntimeError, ValueError, json.JSONDecodeError) as error:
            self.send_json(400, {"error": str(error)})


def main():
    args = parse_arguments()
    server = ThemeLabServer(("127.0.0.1", args.port), args.serial)
    url = f"http://127.0.0.1:{args.port}"
    print(f"Coda Now Playing Lab: {url}")
    print(f"Target: {args.serial}")
    if not args.no_open:
        webbrowser.open(url)

    def stop_server(_signal, _frame):
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, stop_server)
    signal.signal(signal.SIGTERM, stop_server)
    server.serve_forever()
    server.server_close()


if __name__ == "__main__":
    sys.exit(main())
