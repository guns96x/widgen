#!/usr/bin/env python3
"""
Widgen Bridge - Antigravity Quota Monitor & Relay Server
Detects local Antigravity Language Server, extracts CSRF token and listening port,
fetches live quotas, and exposes a clean REST API and mobile-ready Web UI.
"""

import os
import sys
import json
import time
import ssl
import re
import subprocess
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse
from datetime import datetime, timezone

BRIDGE_PORT = int(os.environ.get("WIDGEN_BRIDGE_PORT", "59123"))
BRIDGE_HOST = os.environ.get("WIDGEN_BRIDGE_HOST", "0.0.0.0")
CACHE_TTL_SECONDS = 15

# Global cache
_cache_lock = threading.Lock()
_cached_quota = None
_last_fetch_time = 0
_cached_target = None  # (port, csrf_token, use_ssl)


def get_process_info_windows():
    """Finds language_server.exe process, extracting PID, commandline, and ports."""
    try:
        cmd = [
            "powershell", "-NoProfile", "-Command",
            "Get-CimInstance Win32_Process | "
            "Where-Object { $_.Name -like '*language_server*' } | "
            "Select-Object ProcessId, CommandLine | ConvertTo-Json"
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if res.returncode != 0 or not res.stdout.strip():
            return None
        
        data = json.loads(res.stdout)
        if isinstance(data, list):
            item = data[0]
        else:
            item = data
        
        pid = item.get("ProcessId")
        cmd_line = item.get("CommandLine", "")
        if not pid or not cmd_line:
            return None
        
        # Extract CSRF token
        csrf_match = re.search(r"--csrf_token\s+([a-zA-Z0-9\-]+)", cmd_line)
        csrf_token = csrf_match.group(1) if csrf_match else None
        
        # Find listening TCP ports for this PID
        net_cmd = [
            "powershell", "-NoProfile", "-Command",
            f"Get-NetTCPConnection | Where-Object {{ $_.OwningProcess -eq {pid} -and $_.State -eq 'Listen' }} | "
            "Select-Object LocalAddress, LocalPort | ConvertTo-Json"
        ]
        net_res = subprocess.run(net_cmd, capture_output=True, text=True, timeout=10)
        ports = []
        if net_res.returncode == 0 and net_res.stdout.strip():
            net_data = json.loads(net_res.stdout)
            if isinstance(net_data, list):
                ports = [int(p.get("LocalPort")) for p in net_data if p.get("LocalPort")]
            elif isinstance(net_data, dict) and net_data.get("LocalPort"):
                ports = [int(net_data.get("LocalPort"))]
        
        return {
            "pid": pid,
            "csrf_token": csrf_token,
            "ports": ports
        }
    except Exception as e:
        print(f"[Bridge] Error finding language_server: {e}", file=sys.stderr)
        return None


def fetch_from_language_server(port, csrf_token, use_ssl=False):
    """Sends GetUserStatus request to local language server."""
    scheme = "https" if use_ssl else "http"
    url = f"{scheme}://127.0.0.1:{port}/exa.language_server_pb.LanguageServerService/GetUserStatus"
    
    headers = {
        "Content-Type": "application/json",
        "Connect-Protocol-Version": "1",
        "X-Codeium-Csrf-Token": csrf_token
    }
    
    import urllib.request
    req = urllib.request.Request(url, data=b"{}", headers=headers, method="POST")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    
    with urllib.request.urlopen(req, context=ctx if use_ssl else None, timeout=3) as resp:
        if resp.status == 200:
            return json.loads(resp.read().decode("utf-8"))
    return None


def resolve_active_server():
    """Tries to find and verify the working port and csrf_token."""
    global _cached_target
    if _cached_target:
        port, token, use_ssl = _cached_target
        try:
            data = fetch_from_language_server(port, token, use_ssl)
            if data:
                return port, token, use_ssl
        except Exception:
            _cached_target = None
    
    info = get_process_info_windows()
    if not info or not info.get("csrf_token") or not info.get("ports"):
        return None
    
    csrf_token = info["csrf_token"]
    for port in info["ports"]:
        # Try HTTP then HTTPS
        for use_ssl in [False, True]:
            try:
                data = fetch_from_language_server(port, csrf_token, use_ssl)
                if data and "userStatus" in data:
                    _cached_target = (port, csrf_token, use_ssl)
                    print(f"[Bridge] Connected to Antigravity LanguageServer on port {port} (ssl={use_ssl})")
                    return port, csrf_token, use_ssl
            except Exception:
                continue
    return None


def format_duration(seconds):
    if seconds <= 0:
        return "Now"
    days = int(seconds // 86400)
    hours = int((seconds % 86400) // 3600)
    mins = int((seconds % 3600) // 60)
    
    if days > 0:
        return f"{days}d {hours}h"
    elif hours > 0:
        return f"{hours}h {mins}m"
    else:
        return f"{mins}m"


def parse_reset_time(iso_str):
    if not iso_str:
        return None, 0, "Unknown"
    try:
        cleaned = iso_str.replace("Z", "+00:00")
        dt = datetime.fromisoformat(cleaned)
        now = datetime.now(timezone.utc)
        diff = (dt - now).total_seconds()
        readable = format_duration(diff)
        return dt.isoformat(), max(0, int(diff)), readable
    except Exception as e:
        return iso_str, 0, "Unknown"


def get_normalized_quota_snapshot():
    """Returns normalized quota snapshot, with caching."""
    global _cached_quota, _last_fetch_time
    now = time.time()
    
    with _cache_lock:
        if _cached_quota and (now - _last_fetch_time < CACHE_TTL_SECONDS):
            return _cached_quota
    
    target = resolve_active_server()
    if not target:
        with _cache_lock:
            if _cached_quota:
                stale = dict(_cached_quota)
                stale["status"] = "stale"
                stale["staleReason"] = "Antigravity process not detected"
                return stale
        return {
            "status": "offline",
            "message": "Google Antigravity is not currently running on this PC.",
            "updatedAt": datetime.now(timezone.utc).isoformat(),
            "account": None,
            "pools": {}
        }
    
    port, csrf_token, use_ssl = target
    try:
        raw_data = fetch_from_language_server(port, csrf_token, use_ssl)
    except Exception as e:
        return {
            "status": "error",
            "message": f"Failed to query language server: {str(e)}",
            "updatedAt": datetime.now(timezone.utc).isoformat(),
            "account": None,
            "pools": {}
        }
    
    user_status = raw_data.get("userStatus", {})
    plan_status = user_status.get("planStatus", {})
    plan_info = plan_status.get("planInfo", {})
    
    configs = user_status.get("cascadeModelConfigData", {}).get("clientModelConfigs", [])
    
    gemini_models = []
    claude_gpt_models = []
    
    for cfg in configs:
        label = cfg.get("label", "")
        quota = cfg.get("quotaInfo", {})
        fraction = quota.get("remainingFraction", 1.0)
        reset_time_raw = quota.get("resetTime")
        reset_iso, reset_sec, reset_str = parse_reset_time(reset_time_raw)
        
        entry = {
            "name": label,
            "remainingFraction": fraction,
            "remainingPercent": round(fraction * 100),
            "resetTime": reset_iso,
            "resetInSeconds": reset_sec,
            "resetFormatted": reset_str,
            "isExhausted": fraction <= 0.001
        }
        
        name_lower = label.lower()
        if "gemini" in name_lower:
            gemini_models.append(entry)
        elif any(k in name_lower for k in ["claude", "gpt", "oss"]):
            claude_gpt_models.append(entry)
    
    def summarize_pool(models, pool_name):
        if not models:
            return {
                "name": pool_name,
                "remainingPercent": 100,
                "remainingFraction": 1.0,
                "resetTime": None,
                "resetInSeconds": 0,
                "resetFormatted": "Ready",
                "isExhausted": False,
                "modelsCount": 0
            }
        
        lead = models[0]
        return {
            "name": pool_name,
            "remainingPercent": lead["remainingPercent"],
            "remainingFraction": lead["remainingFraction"],
            "resetTime": lead["resetTime"],
            "resetInSeconds": lead["resetInSeconds"],
            "resetFormatted": lead["resetFormatted"],
            "isExhausted": lead["isExhausted"],
            "modelsCount": len(models),
            "models": models
        }
    
    snapshot = {
        "status": "online",
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "account": {
            "email": user_status.get("email", ""),
            "name": user_status.get("name", ""),
            "plan": plan_info.get("planName", "Pro"),
            "promptCredits": plan_status.get("availablePromptCredits", 0),
            "flowCredits": plan_status.get("availableFlowCredits", 0)
        },
        "pools": {
            "gemini": summarize_pool(gemini_models, "Gemini Pool (Flash / Pro)"),
            "claude_gpt": summarize_pool(claude_gpt_models, "Claude / GPT Pool (Sonnet / Opus / OSS)")
        }
    }
    
    with _cache_lock:
        _cached_quota = snapshot
        _last_fetch_time = time.time()
        
    return snapshot


class WidgenHandler(BaseHTTPRequestHandler):
    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        
        if path == "/api/quota":
            data = get_normalized_quota_snapshot()
            body = json.dumps(data, indent=2, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
            
        elif path == "/api/health":
            target = resolve_active_server()
            status = "connected" if target else "searching"
            payload = {
                "status": "ok",
                "antigravity": status,
                "timestamp": datetime.now(timezone.utc).isoformat()
            }
            body = json.dumps(payload).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
            
        elif path == "/" or path == "/dashboard":
            self.render_dashboard()
            
        else:
            self.send_response(404)
            self.end_headers()

    def render_dashboard(self):
        snapshot = get_normalized_quota_snapshot()
        acc = snapshot.get("account") or {}
        email = acc.get("email", "Unknown")
        plan = acc.get("plan", "Standard")
        gemini = snapshot.get("pools", {}).get("gemini", {})
        claude = snapshot.get("pools", {}).get("claude_gpt", {})
        
        gemini_pct = gemini.get("remainingPercent", 100)
        claude_pct = claude.get("remainingPercent", 100)
        gemini_reset = gemini.get("resetFormatted", "Ready")
        claude_reset = claude.get("resetFormatted", "Ready")
        
        html = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Antigravity Limits — Widgen</title>
    <style>
        :root {{
            --bg: #0B0D11;
            --surface: #14171E;
            --surface-elevated: #1C212B;
            --border: #282F3D;
            --text-primary: #F3F4F6;
            --text-secondary: #9CA3AF;
            --gemini-cyan: #00E5FF;
            --claude-purple: #A855F7;
            --green: #10B981;
            --amber: #F59E0B;
            --rose: #EF4444;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--bg);
            color: var(--text-primary);
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            padding: 24px 16px;
            display: flex;
            flex-direction: column;
            align-items: center;
            min-height: 100vh;
        }}
        .container {{
            width: 100%;
            max-width: 480px;
        }}
        header {{
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 24px;
        }}
        .brand {{
            font-size: 20px;
            font-weight: 700;
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        .badge {{
            background: var(--surface-elevated);
            color: var(--gemini-cyan);
            font-size: 11px;
            font-weight: 600;
            padding: 4px 8px;
            border-radius: 6px;
            border: 1px solid var(--border);
            text-transform: uppercase;
        }}
        .account-card {{
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 16px;
            margin-bottom: 16px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }}
        .quota-card {{
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 20px;
            margin-bottom: 16px;
        }}
        .card-header {{
            display: flex;
            justify-content: space-between;
            align-items: baseline;
            margin-bottom: 12px;
        }}
        .card-title {{
            font-size: 15px;
            font-weight: 600;
            color: var(--text-secondary);
        }}
        .card-percent {{
            font-size: 32px;
            font-weight: 800;
            letter-spacing: -0.5px;
        }}
        .progress-bar-bg {{
            height: 10px;
            background: var(--surface-elevated);
            border-radius: 5px;
            overflow: hidden;
            margin-bottom: 12px;
        }}
        .progress-bar-fill {{
            height: 100%;
            border-radius: 5px;
            transition: width 0.4s ease;
        }}
        .card-meta {{
            display: flex;
            justify-content: space-between;
            font-size: 13px;
            color: var(--text-secondary);
        }}
        .endpoint-card {{
            background: var(--surface);
            border: 1px dashed var(--border);
            border-radius: 16px;
            padding: 16px;
            font-size: 12px;
            color: var(--text-secondary);
            margin-top: 24px;
        }}
        code {{
            background: var(--surface-elevated);
            padding: 2px 6px;
            border-radius: 4px;
            color: var(--gemini-cyan);
            font-family: monospace;
        }}
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div class="brand">
                <span>Antigravity Limits</span>
            </div>
            <div class="badge">{plan}</div>
        </header>

        <div class="account-card">
            <div>
                <div style="font-size: 14px; font-weight: 600;">{email}</div>
                <div style="font-size: 12px; color: var(--text-secondary); margin-top: 4px;">Credits: {acc.get('promptCredits', 0)} prompt · {acc.get('flowCredits', 0)} flow</div>
            </div>
            <div style="width: 10px; height: 10px; border-radius: 50%; background: {'#10B981' if snapshot.get('status') == 'online' else '#EF4444'};"></div>
        </div>

        <div class="quota-card">
            <div class="card-header">
                <span class="card-title">Gemini Models (Flash/Pro)</span>
                <span class="card-percent" style="color: var(--gemini-cyan);">{gemini_pct}%</span>
            </div>
            <div class="progress-bar-bg">
                <div class="progress-bar-fill" style="width: {gemini_pct}%; background: var(--gemini-cyan);"></div>
            </div>
            <div class="card-meta">
                <span>Session limit</span>
                <span>Reset in: <b>{gemini_reset}</b></span>
            </div>
        </div>

        <div class="quota-card">
            <div class="card-header">
                <span class="card-title">Claude & GPT Models</span>
                <span class="card-percent" style="color: var(--claude-purple);">{claude_pct}%</span>
            </div>
            <div class="progress-bar-bg">
                <div class="progress-bar-fill" style="width: {claude_pct}%; background: var(--claude-purple);"></div>
            </div>
            <div class="card-meta">
                <span>Weekly quota</span>
                <span>Reset in: <b>{claude_reset}</b></span>
            </div>
        </div>

        <div class="endpoint-card">
            <div>Widget API endpoint:</div>
            <div style="margin-top: 6px;"><code>http://&lt;PC-LAN-IP&gt;:{BRIDGE_PORT}/api/quota</code></div>
            <div style="margin-top: 8px;">Enter this IP address in the mobile app settings to sync the widget.</div>
        </div>
    </div>
    <script>
        setTimeout(() => {{ location.reload(); }}, 30000);
    </script>
</body>
</html>
"""
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(body)


def run_bridge():
    print(f"=== Antigravity Widgen Bridge ===")
    print(f"Starting on http://{BRIDGE_HOST}:{BRIDGE_PORT}...")
    server = HTTPServer((BRIDGE_HOST, BRIDGE_PORT), WidgenHandler)
    
    target = resolve_active_server()
    if target:
        print(f"[Bridge] Connected! Verified live Antigravity connection.")
    else:
        print(f"[Bridge] LanguageServer not detected yet. Will auto-discover on first request.")
        
    print(f"[Bridge] REST API available at: http://localhost:{BRIDGE_PORT}/api/quota")
    print(f"[Bridge] Mobile web view at:    http://localhost:{BRIDGE_PORT}/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Widgen Bridge.")
        server.server_close()


if __name__ == "__main__":
    run_bridge()
