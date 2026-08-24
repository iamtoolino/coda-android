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


ACTION = "io.github.iamtoolino.coda.debug.BACKGROUND_DESIGN_TUNING"
RECEIVER = "io.github.iamtoolino.coda/.debug.BackgroundDesignTuningReceiver"
VARIANTS = {"current", "oled_glow", "macos_field", "deep_artwork"}
FIELDS = {
    "artworkOpacity": (0.0, 0.40),
    "blurRadiusDp": (0.0, 120.0),
    "artworkScale": (1.0, 1.50),
    "artworkSaturation": (0.0, 1.50),
    "accentOpacity": (0.0, 0.65),
    "glowOpacity": (0.0, 0.65),
    "vignetteOpacity": (0.0, 0.90),
    "blackFalloffOpacity": (0.0, 1.0),
    "baseLuminance": (0.0, 0.10),
}
DEFAULT_STATE = {
    "variant": "deep_artwork",
    "artworkOpacity": 0.11,
    "blurRadiusDp": 84.0,
    "artworkScale": 1.24,
    "artworkSaturation": 0.86,
    "accentOpacity": 0.26,
    "glowOpacity": 0.20,
    "vignetteOpacity": 0.62,
    "blackFalloffOpacity": 0.90,
    "baseLuminance": 0.0,
}
ADB_EXTRAS = {
    "artworkOpacity": "artwork_opacity",
    "blurRadiusDp": "blur_radius_dp",
    "artworkScale": "artwork_scale",
    "artworkSaturation": "artwork_saturation",
    "accentOpacity": "accent_opacity",
    "glowOpacity": "glow_opacity",
    "vignetteOpacity": "vignette_opacity",
    "blackFalloffOpacity": "black_falloff_opacity",
    "baseLuminance": "base_luminance",
}
REPO_ROOT = Path(__file__).resolve().parent.parent
ADB_SCRIPT = REPO_ROOT / "scripts" / "adb.sh"
PAGE_TEMPLATE = (Path(__file__).parent / "theme-lab.html").read_text(encoding="utf-8")


def parse_arguments():
    parser = argparse.ArgumentParser(description="Control Coda's debug artwork background")
    parser.add_argument("serial")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--no-open", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial):
        parser.error("serial contains unsupported characters")
    if not 1024 <= args.port <= 65535:
        parser.error("port must be between 1024 and 65535")
    return args


def validated_number(value, name):
    try:
        number = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{name} must be a number") from error
    minimum, maximum = FIELDS[name]
    if not minimum <= number <= maximum:
        raise ValueError(f"{name} must be between {minimum:g} and {maximum:g}")
    return round(number, 3)


def validated_state(value):
    variant = value.get("variant") if isinstance(value, dict) else None
    if variant not in VARIANTS:
        raise ValueError("unknown background design")
    return {
        "variant": variant,
        **{name: validated_number(value.get(name), name) for name in FIELDS},
    }


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
        self.state = DEFAULT_STATE.copy()
        self.state_lock = threading.Lock()

    def publish(self, next_state):
        arguments = [
            "shell", "am", "broadcast", "-a", ACTION, "-n", RECEIVER,
            "--es", "variant", next_state["variant"],
        ]
        for name, extra in ADB_EXTRAS.items():
            arguments.extend(["--ef", extra, str(next_state[name])])
        run_adb(self.serial, arguments)
        with self.state_lock:
            self.state = next_state


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

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 32768:
            raise ValueError("request body is too large")
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self):
        if self.path == "/":
            body = PAGE_TEMPLATE.replace("__TARGET_SERIAL__", self.server.serial).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/api/state":
            with self.server.state_lock:
                self.send_json(200, {"serial": self.server.serial, **self.server.state})
        elif self.path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self):
        try:
            if self.path == "/api/tuning":
                next_state = validated_state(self.read_json())
                self.server.publish(next_state)
                self.send_json(200, {"ok": True, **next_state})
            elif self.path == "/api/open-coda":
                run_adb(
                    self.server.serial,
                    ["shell", "am", "start", "-n", "io.github.iamtoolino.coda/.MainActivity"],
                )
                self.send_json(200, {"ok": True})
            else:
                self.send_json(404, {"error": "not found"})
        except (json.JSONDecodeError, RuntimeError, ValueError) as error:
            self.send_json(400, {"error": str(error)})


def main():
    args = parse_arguments()
    server = ThemeLabServer(("127.0.0.1", args.port), args.serial)
    url = f"http://127.0.0.1:{args.port}"
    print(f"Coda Theme Lab: {url}")
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
