#!/usr/bin/env python3
"""GrowQuiz 兼容性报告接收端：电视端 ReportUpload 每天 POST 一份 JSON 报告到这里。

入口:  Caddy :8443 /growquiz-report/* -> 127.0.0.1:8092（本服务）
落地:  /root/growquiz-reports/<设备ID>/<时间戳>.json，另维护 latest/<设备ID>.json 便于速查
安全:  校验 X-Report-Token 共享口令；单份报告上限 1 MiB；设备ID只接受安全字符
由 growquiz-report.service 常驻；定时适配任务直接读 /root/growquiz-reports/ 目录
"""

import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DEST = Path("/root/growquiz-reports")
TOKEN = "growquiz-report-2026"  # 与 APP 端 ReportUpload.TOKEN 一致；仅挡随手滥用，不是机密
MAX_BODY = 1024 * 1024
LISTEN = ("127.0.0.1", 8092)
SAFE_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


class Handler(BaseHTTPRequestHandler):
    server_version = "growquiz-report/1.0"

    def log_message(self, fmt, *args):  # 静音逐请求日志；异常仍走 stderr
        pass

    def _reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # 健康检查
        self._reply(200, {"ok": True, "service": "growquiz-report"})

    def do_POST(self):
        if self.headers.get("X-Report-Token") != TOKEN:
            return self._reply(403, {"ok": False, "error": "bad token"})
        try:
            size = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            size = 0
        if size <= 0 or size > MAX_BODY:
            return self._reply(413, {"ok": False, "error": "bad size"})
        try:
            report = json.loads(self.rfile.read(size))
        except Exception:
            return self._reply(400, {"ok": False, "error": "bad json"})
        device = str(report.get("id") or "")
        if not SAFE_ID.match(device):
            return self._reply(400, {"ok": False, "error": "bad id"})
        sent_at = int(report.get("sentAt") or time.time() * 1000)
        folder = DEST / device
        folder.mkdir(parents=True, exist_ok=True)
        name = time.strftime("%Y%m%d-%H%M%S", time.localtime(sent_at / 1000))
        path = folder / f"{name}.json"
        n = 1
        while path.exists():  # 同一秒两份报告时不互相覆盖
            n += 1
            path = folder / f"{name}-{n}.json"
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        latest = DEST / "latest"
        latest.mkdir(exist_ok=True)
        (latest / f"{device}.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        events = len(report.get("events") or [])
        print(f"{time.strftime('%F %T')} {device} events={events} -> {path.name}", flush=True)
        self._reply(200, {"ok": True})


if __name__ == "__main__":
    DEST.mkdir(parents=True, exist_ok=True)
    ThreadingHTTPServer(LISTEN, Handler).serve_forever()
