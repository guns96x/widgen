import unittest
import os
import sys
import json
import threading
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
from unittest.mock import patch

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import widgen_bridge
from widgen_bridge import WidgenHandler, get_api_token


class TestHttpApiIntegration(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.test_token = "test_bearer_token_integration_12345"
        os.environ["WIDGEN_API_TOKEN"] = cls.test_token
        widgen_bridge._api_token_cache = cls.test_token

        # Bind to port 0 for OS dynamic port allocation
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), WidgenHandler)
        cls.server.daemon_threads = True
        cls.port = cls.server.server_address[1]
        cls.base_url = f"http://127.0.0.1:{cls.port}"

        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        os.environ.pop("WIDGEN_API_TOKEN", None)
        widgen_bridge._api_token_cache = None

    def _request(self, path, method="GET", headers=None, data=None):
        url = f"{self.base_url}{path}"
        req_headers = headers or {}
        req_data = data.encode("utf-8") if isinstance(data, str) else data
        req = urllib.request.Request(url, data=req_data, headers=req_headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                body = resp.read().decode("utf-8")
                return resp.status, resp.headers, body
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8")
            return e.code, e.headers, body

    def test_health_endpoint_public(self):
        status, headers, body = self._request("/api/health")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data.get("status"), "ok")
        self.assertIn("timestamp", data)
        # Verify no secret data leaks
        self.assertNotIn("api_token", data)
        self.assertNotIn("email", data)

    def test_quota_unauthorized(self):
        status, _, body = self._request("/api/quota")
        self.assertEqual(status, 401)
        data = json.loads(body)
        self.assertEqual(data.get("error"), "unauthorized")

    def test_quota_wrong_token(self):
        headers = {"Authorization": "Bearer wrong_token"}
        status, _, body = self._request("/api/quota", headers=headers)
        self.assertEqual(status, 401)

    @patch("widgen_bridge.get_normalized_quota_snapshot")
    def test_quota_authorized(self, mock_snapshot):
        mock_snapshot.return_value = {
            "status": "online",
            "antigravity": {"status": "online"},
            "codex": {"status": "online"}
        }
        headers = {"Authorization": f"Bearer {self.test_token}"}
        status, _, body = self._request("/api/quota", headers=headers)
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data.get("status"), "online")

    def test_accounts_unauthorized(self):
        status, _, _ = self._request("/api/accounts")
        self.assertEqual(status, 401)

    @patch("widgen_bridge.get_normalized_quota_snapshot")
    def test_accounts_authorized(self, mock_snapshot):
        mock_snapshot.return_value = {
            "antigravity": {
                "accounts": [{"id": "acc-1", "email": "test@domain.com", "isCurrent": True}]
            }
        }
        headers = {"Authorization": f"Bearer {self.test_token}"}
        status, _, body = self._request("/api/accounts", headers=headers)
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertIn("accounts", data)
        self.assertEqual(len(data["accounts"]), 1)

    def test_switch_unauthorized(self):
        payload = json.dumps({"account_id": "acc-1"})
        status, _, _ = self._request(
            "/api/accounts/switch",
            method="POST",
            headers={"Content-Type": "application/json"},
            data=payload
        )
        self.assertEqual(status, 401)

    def test_switch_missing_body(self):
        headers = {
            "Authorization": f"Bearer {self.test_token}",
            "Content-Type": "application/json"
        }
        # Zero content-length / empty body
        status, _, _ = self._request("/api/accounts/switch", method="POST", headers=headers, data="")
        self.assertEqual(status, 400)

    def test_switch_missing_account_id(self):
        headers = {
            "Authorization": f"Bearer {self.test_token}",
            "Content-Type": "application/json"
        }
        status, _, _ = self._request(
            "/api/accounts/switch",
            method="POST",
            headers=headers,
            data=json.dumps({})
        )
        self.assertEqual(status, 400)

    @patch("widgen_bridge.fetch_antigravity_tools_accounts")
    def test_switch_unknown_account(self, mock_fetch):
        mock_fetch.return_value = [{"id": "known-1"}]
        headers = {
            "Authorization": f"Bearer {self.test_token}",
            "Content-Type": "application/json"
        }
        status, _, body = self._request(
            "/api/accounts/switch",
            method="POST",
            headers=headers,
            data=json.dumps({"account_id": "non-existent"})
        )
        self.assertEqual(status, 404)

    @patch("widgen_bridge.switch_antigravity_account")
    @patch("widgen_bridge.fetch_antigravity_tools_accounts")
    def test_switch_success(self, mock_fetch, mock_switch):
        mock_fetch.return_value = [{"id": "acc-target", "email": "target@domain.com"}]
        mock_switch.return_value = True
        headers = {
            "Authorization": f"Bearer {self.test_token}",
            "Content-Type": "application/json"
        }
        status, _, body = self._request(
            "/api/accounts/switch",
            method="POST",
            headers=headers,
            data=json.dumps({"account_id": "acc-target"})
        )
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertTrue(data.get("success"))
        self.assertEqual(data.get("active_account_id"), "acc-target")

    def test_cors_headers_restricted(self):
        # Localhost origin allowed
        headers = {
            "Origin": f"http://localhost:{widgen_bridge.BRIDGE_PORT}",
            "Authorization": f"Bearer {self.test_token}"
        }
        status, resp_headers, _ = self._request("/api/health", headers=headers)
        # In do_OPTIONS or with send_cors_headers
        status_opt, opt_headers, _ = self._request("/api/health", method="OPTIONS", headers=headers)
        self.assertEqual(status_opt, 204)
        self.assertEqual(opt_headers.get("Access-Control-Allow-Origin"), f"http://localhost:{widgen_bridge.BRIDGE_PORT}")

        # Malicious origin not allowed
        bad_headers = {"Origin": "https://evil-hacker.com"}
        status_opt2, opt_headers2, _ = self._request("/api/health", method="OPTIONS", headers=bad_headers)
        self.assertIsNone(opt_headers2.get("Access-Control-Allow-Origin"))


if __name__ == "__main__":
    unittest.main()
