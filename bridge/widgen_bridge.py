#!/usr/bin/env python3
"""
Widgen Bridge - Unified AI Limits Monitor & Multi-Account Switcher
Monitors both Google Antigravity (multi-account via Antigravity Tools & LanguageServer)
and OpenAI Codex (via ~/.codex/auth.json), providing live quotas, PC account switching,
and standalone REST endpoints for Android widgets.
"""

import os
import sys
import json
import time
import ssl
import re
import glob
import subprocess
import threading
import hmac
import secrets
import html
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse
from pathlib import Path
from datetime import datetime, timezone

BRIDGE_PORT = int(os.environ.get("WIDGEN_BRIDGE_PORT", "59123"))
BRIDGE_HOST = os.environ.get("WIDGEN_BRIDGE_HOST", "0.0.0.0")
CACHE_TTL_SECONDS = 10
MAX_JSON_BODY_BYTES = 8 * 1024

# Paths
USER_PROFILE = os.environ.get("USERPROFILE") or str(Path.home())
ANTIGRAVITY_TOOLS_DIR = os.path.join(USER_PROFILE, ".antigravity_tools")
CODEX_AUTH_FILE = os.path.join(USER_PROFILE, ".codex", "auth.json")
WIDGEN_DIR = os.path.join(USER_PROFILE, ".widgen")
WIDGEN_CONFIG_FILE = os.path.join(WIDGEN_DIR, "config.json")

# Global cache
_cache_lock = threading.Lock()
_cached_quota = None
_last_fetch_time = 0
_cached_target = None  # (port, csrf_token, use_ssl)
_api_token_cache = None

ALLOWED_ORIGINS = {
    f"http://127.0.0.1:{BRIDGE_PORT}",
    f"http://localhost:{BRIDGE_PORT}",
}


def get_api_token() -> str:
    """
    Retrieves or generates shared bearer token for API security.
    Deterministic across calls within process lifetime.
    Priority:
    1. Env var WIDGEN_API_TOKEN
    2. Process-level memory cache _api_token_cache
    3. Config file ~/.widgen/config.json
    4. Auto-generate 32-byte hex token, cache in memory, and persist to ~/.widgen/config.json
    """
    global _api_token_cache
    env_tok = os.environ.get("WIDGEN_API_TOKEN")
    if env_tok and env_tok.strip():
        return env_tok.strip()

    if _api_token_cache:
        return _api_token_cache

    if os.path.exists(WIDGEN_CONFIG_FILE):
        try:
            with open(WIDGEN_CONFIG_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                tok = data.get("api_token")
                if tok and str(tok).strip():
                    _api_token_cache = str(tok).strip()
                    return _api_token_cache
        except Exception:
            pass

    # Auto-generate secure token and cache in memory
    new_token = secrets.token_hex(32)
    _api_token_cache = new_token
    try:
        os.makedirs(WIDGEN_DIR, exist_ok=True)
        with open(WIDGEN_CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump({"api_token": new_token}, f, indent=2)
    except Exception as e:
        print(f"[Bridge] Warning: could not persist api_token: {e}", file=sys.stderr)
    return _api_token_cache


def mask_token(tok: str) -> str:
    if not tok or len(tok) < 8:
        return "****"
    return f"{tok[:4]}...{tok[-4:]}"


def is_authorized(headers) -> bool:
    """Constant-time validation of Bearer token."""
    expected = get_api_token()
    if not expected:
        return False
    auth_header = headers.get("Authorization", "").strip()
    if not auth_header.startswith("Bearer "):
        return False
    received = auth_header[7:].strip()
    return hmac.compare_digest(received, expected)


def get_antigravity_tools_api_key():
    """Reads Antigravity Tools proxy API key from gui_config.json."""
    cfg_file = os.path.join(ANTIGRAVITY_TOOLS_DIR, "gui_config.json")
    if os.path.exists(cfg_file):
        try:
            with open(cfg_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data.get("proxy", {}).get("api_key")
        except Exception:
            pass
    return None


def fetch_antigravity_tools_accounts():
    """Fetches all registered Antigravity accounts from local Antigravity Tools on port 8045."""
    api_key = get_antigravity_tools_api_key()
    if not api_key:
        return None
    url = "http://127.0.0.1:8045/api/accounts"
    headers = {"Authorization": f"Bearer {api_key}"}
    
    import urllib.request
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode("utf-8"))
                return data.get("accounts", [])
    except Exception as e:
        print(f"[Bridge] Could not query Antigravity Tools on 8045: {e}", file=sys.stderr)
    return None


def switch_antigravity_account(account_id):
    """Switches active Antigravity account in Antigravity Tools."""
    api_key = get_antigravity_tools_api_key()
    if not api_key:
        return False
    url = "http://127.0.0.1:8045/api/accounts/switch"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    payload = json.dumps({"account_id": account_id}).encode("utf-8")
    
    import urllib.request
    req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    try:
        # Antigravity Tools restarts the IDE process when switching, so allow up to 25 seconds
        with urllib.request.urlopen(req, timeout=25) as resp:
            return resp.status == 200
    except Exception as e:
        print(f"[Bridge] Switch account error: {e}", file=sys.stderr)
        return False


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
        item = data[0] if isinstance(data, list) else data
        pid = item.get("ProcessId")
        cmd_line = item.get("CommandLine", "")
        if not pid or not cmd_line:
            return None
        
        csrf_match = re.search(r"--csrf_token\s+([a-zA-Z0-9\-]+)", cmd_line)
        csrf_token = csrf_match.group(1) if csrf_match else None
        
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
        
        return {"pid": pid, "csrf_token": csrf_token, "ports": ports}
    except Exception:
        return None


def fetch_from_language_server(port, csrf_token, use_ssl=False):
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
        for use_ssl in [False, True]:
            try:
                data = fetch_from_language_server(port, csrf_token, use_ssl)
                if data and "userStatus" in data:
                    _cached_target = (port, csrf_token, use_ssl)
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
    except Exception:
        return iso_str, 0, "Unknown"


def fetch_codex_usage():
    """Fetches live rate limits from chatgpt.com using ~/.codex/auth.json."""
    if not os.path.exists(CODEX_AUTH_FILE):
        return None
    try:
        with open(CODEX_AUTH_FILE, "r", encoding="utf-8") as f:
            auth_data = json.load(f)
        tok = auth_data.get("tokens", {})
        access_token = tok.get("access_token")
        account_id = tok.get("account_id")
        if not access_token:
            return None
        
        import urllib.request
        url = "https://chatgpt.com/backend-api/codex/usage"
        headers = {
            "Authorization": f"Bearer {access_token}",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Accept": "application/json"
        }
        if account_id:
            headers["ChatGPT-Account-Id"] = account_id
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode("utf-8"))
                rate_limit = data.get("rate_limit", {})
                primary = rate_limit.get("primary_window", {})
                secondary = rate_limit.get("secondary_window", {})
                
                # Primary = 5-hour session window
                p_used = primary.get("used_percent", 0)
                p_rem = max(0, 100 - p_used)
                p_reset_sec = primary.get("reset_after_seconds", 0)
                
                # Secondary = weekly window
                s_used = secondary.get("used_percent", 0) if secondary else 0
                s_rem = max(0, 100 - s_used)
                s_reset_sec = secondary.get("reset_after_seconds", 0) if secondary else 0
                
                return {
                    "status": "online",
                    "email": data.get("email", ""),
                    "plan": data.get("plan_type", "Plus").capitalize(),
                    "sessionWindow": {
                        "remainingPercent": p_rem,
                        "usedPercent": p_used,
                        "resetInSeconds": p_reset_sec,
                        "resetFormatted": format_duration(p_reset_sec)
                    },
                    "weeklyWindow": {
                        "remainingPercent": s_rem,
                        "usedPercent": s_used,
                        "resetInSeconds": s_reset_sec,
                        "resetFormatted": format_duration(s_reset_sec)
                    },
                    "resetCredits": data.get("rate_limit_reset_credits", {}).get("available_count", 0)
                }
    except Exception as e:
        print(f"[Bridge] Codex quota error: {e}", file=sys.stderr)
    return None


def get_normalized_quota_snapshot():
    """Builds unified quota snapshot covering Antigravity multi-accounts and Codex."""
    global _cached_quota, _last_fetch_time
    now = time.time()
    
    with _cache_lock:
        if _cached_quota and (now - _last_fetch_time < CACHE_TTL_SECONDS):
            return _cached_quota
    
    # 1. Codex limits
    codex_data = fetch_codex_usage()
    
    # 2. Antigravity accounts from Antigravity Tools (multi-account)
    raw_accounts = fetch_antigravity_tools_accounts()
    accounts_list = []
    active_account = None
    
    if raw_accounts:
        for a in raw_accounts:
            q = a.get("quota", {})
            models = q.get("models", [])
            
            gemini_pct = 100
            claude_pct = 100
            gemini_reset = "Ready"
            claude_reset = "Ready"
            
            for m in models:
                name_l = m.get("name", "").lower()
                pct = m.get("percentage", 100)
                r_iso, r_sec, r_str = parse_reset_time(m.get("reset_time"))
                if "gemini" in name_l:
                    gemini_pct = min(gemini_pct, pct)
                    gemini_reset = r_str
                elif any(k in name_l for k in ["claude", "gpt", "oss"]):
                    claude_pct = min(claude_pct, pct)
                    claude_reset = r_str
            
            acc_entry = {
                "id": a.get("id"),
                "email": a.get("email"),
                "name": a.get("name"),
                "isCurrent": a.get("is_current", False),
                "geminiPercent": gemini_pct,
                "geminiReset": gemini_reset,
                "claudePercent": claude_pct,
                "claudeReset": claude_reset,
                "modelsCount": len(models)
            }
            accounts_list.append(acc_entry)
            if a.get("is_current"):
                active_account = acc_entry
    
    # 3. Direct language_server check for live session details
    target = resolve_active_server()
    ls_models = []
    gemini_pool = None
    claude_pool = None
    plan_name = "Pro"
    credits_prompt = 0
    credits_flow = 0
    
    if target:
        port, csrf_token, use_ssl = target
        try:
            raw_ls = fetch_from_language_server(port, csrf_token, use_ssl)
            u_status = raw_ls.get("userStatus", {})
            p_status = u_status.get("planStatus", {})
            plan_name = p_status.get("planInfo", {}).get("planName", "Pro")
            credits_prompt = p_status.get("availablePromptCredits", 0)
            credits_flow = p_status.get("availableFlowCredits", 0)
            
            cfgs = u_status.get("cascadeModelConfigData", {}).get("clientModelConfigs", [])
            for c in cfgs:
                lbl = c.get("label", "")
                q = c.get("quotaInfo", {})
                frac = q.get("remainingFraction", 1.0)
                r_iso, r_sec, r_str = parse_reset_time(q.get("resetTime"))
                entry = {
                    "name": lbl,
                    "remainingPercent": round(frac * 100),
                    "remainingFraction": frac,
                    "resetFormatted": r_str,
                    "resetTime": r_iso,
                    "isExhausted": frac <= 0.001
                }
                ls_models.append(entry)
                
            g_models = [m for m in ls_models if "gemini" in m["name"].lower()]
            c_models = [m for m in ls_models if any(k in m["name"].lower() for k in ["claude", "gpt", "oss"])]
            
            if g_models:
                lead = g_models[0]
                gemini_pool = {
                    "name": "Gemini Models",
                    "remainingPercent": lead["remainingPercent"],
                    "remainingFraction": lead["remainingFraction"],
                    "resetFormatted": lead["resetFormatted"],
                    "models": g_models
                }
            if c_models:
                lead = c_models[0]
                claude_pool = {
                    "name": "Claude & GPT Models",
                    "remainingPercent": lead["remainingPercent"],
                    "remainingFraction": lead["remainingFraction"],
                    "resetFormatted": lead["resetFormatted"],
                    "models": c_models
                }
        except Exception:
            pass
            
    # Fallback to accounts_list if language_server not ready
    if not gemini_pool and active_account:
        gemini_pool = {
            "name": "Gemini Models",
            "remainingPercent": active_account["geminiPercent"],
            "remainingFraction": active_account["geminiPercent"] / 100.0,
            "resetFormatted": active_account["geminiReset"],
            "models": []
        }
    if not claude_pool and active_account:
        claude_pool = {
            "name": "Claude & GPT Models",
            "remainingPercent": active_account["claudePercent"],
            "remainingFraction": active_account["claudePercent"] / 100.0,
            "resetFormatted": active_account["claudeReset"],
            "models": []
        }

    is_any_online = bool(codex_data or target or accounts_list)
    active_email = active_account.get("email", "") if active_account else ""
    active_name = active_account.get("name", "") if active_account else ""
    active_id = active_account.get("id") if active_account else None

    snapshot = {
        "status": "online" if is_any_online else "offline",
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "codex": codex_data,
        "antigravity": {
            "status": "online" if (target or accounts_list) else "offline",
            "activeAccount": {
                "id": active_id,
                "email": active_email,
                "name": active_name,
                "plan": plan_name,
                "promptCredits": credits_prompt,
                "flowCredits": credits_flow
            } if active_account else None,
            "accounts": accounts_list,
            "pools": {
                "gemini": gemini_pool,
                "claude_gpt": claude_pool
            } if (gemini_pool or claude_pool) else None
        },
        # Backwards compatibility fields for widget v1
        "account": {
            "email": active_email,
            "name": active_name,
            "plan": plan_name,
            "promptCredits": credits_prompt,
            "flowCredits": credits_flow
        } if active_account else None,
        "pools": {
            "gemini": gemini_pool,
            "claude_gpt": claude_pool
        } if (gemini_pool or claude_pool) else None
    }
    
    with _cache_lock:
        _cached_quota = snapshot
        _last_fetch_time = time.time()
        
    return snapshot


class WidgenHandler(BaseHTTPRequestHandler):
    def send_cors_headers(self):
        origin = self.headers.get("Origin")
        if origin in ALLOWED_ORIGINS:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
            self.send_header("Vary", "Origin")

    def send_json_error(self, code: int, message: str, extra: dict = None):
        payload = {"error": message}
        if extra:
            payload.update(extra)
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        
        if path == "/api/accounts/switch":
            # Drain body if present to avoid TCP RST on client
            try:
                cl = int(self.headers.get("Content-Length", 0))
                if 0 < cl <= MAX_JSON_BODY_BYTES:
                    body_bytes = self.rfile.read(cl)
                else:
                    body_bytes = b""
            except Exception:
                body_bytes = b""

            # API Authorization
            if not is_authorized(self.headers):
                self.send_json_error(401, "unauthorized")
                return

            # CSRF protection: if Origin header present, must match allowlist
            origin = self.headers.get("Origin")
            if origin and origin not in ALLOWED_ORIGINS:
                self.send_json_error(403, "Forbidden cross-origin switch request")
                return

            # Validate Content-Length
            try:
                content_len_header = self.headers.get("Content-Length")
                if content_len_header is None:
                    self.send_json_error(400, "Missing Content-Length")
                    return
                content_len = int(content_len_header)
            except (ValueError, TypeError):
                self.send_json_error(400, "Invalid Content-Length")
                return

            if content_len <= 0:
                self.send_json_error(400, "Empty request body")
                return

            if content_len > MAX_JSON_BODY_BYTES:
                self.send_json_error(413, "Payload Too Large")
                return

            try:
                body = body_bytes.decode("utf-8")
            except Exception:
                self.send_json_error(400, "Invalid payload encoding")
                return

            try:
                data = json.loads(body)
            except (json.JSONDecodeError, ValueError):
                self.send_json_error(400, "Invalid JSON")
                return

            if not isinstance(data, dict):
                self.send_json_error(400, "JSON payload must be an object")
                return

            account_id = data.get("account_id") or data.get("id")
            if not isinstance(account_id, str) or not account_id.strip():
                self.send_json_error(400, "Invalid account_id")
                return
            account_id = account_id.strip()

            # Server-side validation of account existence
            known_accounts = fetch_antigravity_tools_accounts()
            if known_accounts is not None:
                known_ids = {a.get("id") for a in known_accounts if a.get("id")}
                if account_id not in known_ids:
                    self.send_json_error(404, "Account not found", {"account_id": account_id})
                    return

            try:
                success = switch_antigravity_account(account_id)
                # Invalidate cache
                global _cached_quota, _cached_target
                with _cache_lock:
                    _cached_quota = None
                    _cached_target = None

                self.send_response(200 if success else 500)
                self.send_header("Content-Type", "application/json")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"success": success, "active_account_id": account_id}).encode("utf-8"))
            except Exception as e:
                self.send_json_error(500, str(e))
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        
        if path == "/api/quota":
            if not is_authorized(self.headers):
                self.send_response(401)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(b'{"error": "unauthorized"}')
                return

            data = get_normalized_quota_snapshot()
            body = json.dumps(data, indent=2, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
            
        elif path == "/api/accounts":
            if not is_authorized(self.headers):
                self.send_response(401)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(b'{"error": "unauthorized"}')
                return

            data = get_normalized_quota_snapshot()
            accounts = data.get("antigravity", {}).get("accounts", [])
            body = json.dumps({"accounts": accounts}, indent=2, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
            
        elif path == "/api/health":
            payload = {
                "status": "ok",
                "timestamp": datetime.now(timezone.utc).isoformat()
            }
            body = json.dumps(payload).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
            
        elif path == "/" or path == "/dashboard":
            client_ip = self.client_address[0]
            if client_ip not in ("127.0.0.1", "::1", "localhost") and not is_authorized(self.headers):
                self.send_response(403)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.end_headers()
                self.wfile.write(b"Forbidden: Dashboard is accessible only from localhost.")
                return
            self.render_dashboard()
        else:
            self.send_response(404)
            self.end_headers()

    def render_dashboard(self):
        data = get_normalized_quota_snapshot()
        anti = data.get("antigravity", {})
        codex = data.get("codex") or {}
        accounts = anti.get("accounts", [])
        api_token = get_api_token()
        
        acc_cards_html = ""
        for a in accounts:
            is_cur = a.get("isCurrent")
            bg_card = "#1C212B" if is_cur else "#14171E"
            border_col = "#00E5FF" if is_cur else "#282F3D"
            safe_email = html.escape(str(a.get('email', '')), quote=True)
            safe_name = html.escape(str(a.get('name', '')), quote=True)
            js_acc_id = json.dumps(str(a.get("id", "")))
            btn_html = f'<span style="color:#00E5FF;font-size:12px;font-weight:bold;">● ACTIVE ON PC</span>' if is_cur else f'<button onclick=\'switchAccount({js_acc_id})\' style="background:#282F3D;color:#FFF;border:none;padding:6px 12px;border-radius:6px;cursor:pointer;font-size:12px;">Switch to this</button>'
            
            acc_cards_html += f"""
            <div style="background:{bg_card};border:1px solid {border_col};border-radius:12px;padding:14px;margin-bottom:10px;">
                <div style="display:flex;justify-content:space-between;align-items:center;">
                    <div>
                        <div style="font-weight:600;font-size:14px;">{safe_email}</div>
                        <div style="color:#9CA3AF;font-size:12px;">{safe_name}</div>
                    </div>
                    <div>{btn_html}</div>
                </div>
                <div style="display:flex;gap:12px;margin-top:10px;font-size:12px;">
                    <div>Gemini: <b style="color:#00E5FF;">{a.get('geminiPercent')}%</b> (reset: {html.escape(str(a.get('geminiReset', 'Ready')))})</div>
                    <div>Claude: <b style="color:#A855F7;">{a.get('claudePercent')}%</b> (reset: {html.escape(str(a.get('claudeReset', 'Ready')))})</div>
                </div>
            </div>
            """
            
        codex_session = codex.get("sessionWindow", {})
        codex_weekly = codex.get("weeklyWindow", {})
        
        dashboard_html = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI Limits — Unified Dashboard</title>
    <style>
        body {{
            background: #0B0D11;
            color: #F3F4F6;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            padding: 24px 16px;
            display: flex;
            flex-direction: column;
            align-items: center;
        }}
        .container {{ width: 100%; max-width: 520px; }}
        h2 {{ font-size: 18px; margin: 20px 0 12px; color: #9CA3AF; text-transform: uppercase; font-size: 12px; letter-spacing: 1px; }}
        .card {{ background: #14171E; border: 1px solid #282F3D; border-radius: 14px; padding: 16px; margin-bottom: 14px; }}
        .row {{ display: flex; justify-content: space-between; margin-bottom: 8px; }}
        .bar-bg {{ height: 8px; background: #1C212B; border-radius: 4px; overflow: hidden; margin-bottom: 6px; }}
        .bar-fill {{ height: 100%; border-radius: 4px; }}
    </style>
</head>
<body>
    <div class="container">
        <h1 style="font-size:22px;font-weight:800;margin-bottom:20px;">AI Limits Monitor</h1>

        <h2>OpenAI Codex</h2>
        <div class="card">
            <div class="row">
                <span>Codex Session (5h window)</span>
                <b style="color:#10B981;">{codex_session.get('remainingPercent', 100)}% left</b>
            </div>
            <div class="bar-bg"><div class="bar-fill" style="width:{codex_session.get('remainingPercent', 100)}%;background:#10B981;"></div></div>
            <div style="font-size:12px;color:#9CA3AF;">Reset in: {html.escape(str(codex_session.get('resetFormatted', 'Ready')))}</div>

            <div class="row" style="margin-top:14px;">
                <span>Codex Weekly Limit</span>
                <b style="color:{'#EF4444' if codex_weekly.get('remainingPercent', 100) < 20 else '#F59E0B'};">{codex_weekly.get('remainingPercent', 100)}% left</b>
            </div>
            <div class="bar-bg"><div class="bar-fill" style="width:{codex_weekly.get('remainingPercent', 100)}%;background:{'#EF4444' if codex_weekly.get('remainingPercent', 100) < 20 else '#F59E0B'};"></div></div>
            <div style="font-size:12px;color:#9CA3AF;">Reset in: {html.escape(str(codex_weekly.get('resetFormatted', 'Ready')))}</div>
        </div>

        <h2>Google Antigravity Accounts</h2>
        {acc_cards_html}

        <div style="background:#14171E;border:1px dashed #282F3D;border-radius:12px;padding:12px;font-size:12px;color:#9CA3AF;margin-top:20px;">
            API endpoint: <code>http://&lt;PC-LAN-IP&gt;:{BRIDGE_PORT}/api/quota</code><br>
            Auth: <code>Authorization: Bearer {mask_token(api_token)}</code>
        </div>
    </div>

    <script>
        const API_TOKEN = {json.dumps(api_token)};
        function switchAccount(accId) {{
            fetch('/api/accounts/switch', {{
                method: 'POST',
                headers: {{
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + API_TOKEN
                }},
                body: JSON.stringify({{ account_id: accId }})
            }}).then(r => r.json()).then(res => {{
                if (res.success) location.reload();
                else alert('Switch failed');
            }}).catch(e => alert('Error: ' + e));
        }}
        setTimeout(() => location.reload(), 30000);
    </script>
</body>
</html>
"""
        body = dashboard_html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(body)


def run_bridge():
    token = get_api_token()
    print(f"=== Unified AI Limits Bridge (Antigravity Multi-Account + Codex) ===")
    print(f"Starting on http://{BRIDGE_HOST}:{BRIDGE_PORT}...")
    print(f"[Bridge] API Bearer Token: {mask_token(token)}")
    server = ThreadingHTTPServer((BRIDGE_HOST, BRIDGE_PORT), WidgenHandler)
    server.daemon_threads = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()


if __name__ == "__main__":
    run_bridge()
