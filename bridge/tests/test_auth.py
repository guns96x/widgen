import unittest
import os
import sys

# Ensure bridge directory is in path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from widgen_bridge import is_authorized, mask_token, get_api_token


class TestAuth(unittest.TestCase):

    def setUp(self):
        # Set a known test token in env
        self.test_token = "test_secret_token_1234567890abcdef"
        os.environ["WIDGEN_API_TOKEN"] = self.test_token

    def tearDown(self):
        os.environ.pop("WIDGEN_API_TOKEN", None)

    def test_get_api_token_from_env(self):
        token = get_api_token()
        self.assertEqual(token, self.test_token)

    def test_mask_token(self):
        self.assertEqual(mask_token(""), "****")
        self.assertEqual(mask_token("short"), "****")
        self.assertEqual(mask_token("12345678"), "1234...5678")
        self.assertEqual(mask_token(self.test_token), "test...cdef")

    def test_is_authorized_missing_header(self):
        headers = {}
        self.assertFalse(is_authorized(headers))

    def test_is_authorized_wrong_scheme(self):
        headers = {"Authorization": f"Basic {self.test_token}"}
        self.assertFalse(is_authorized(headers))

    def test_is_authorized_empty_bearer(self):
        headers = {"Authorization": "Bearer "}
        self.assertFalse(is_authorized(headers))

    def test_is_authorized_wrong_token(self):
        headers = {"Authorization": "Bearer wrong_token_value"}
        self.assertFalse(is_authorized(headers))

    def test_is_authorized_valid_token(self):
        headers = {"Authorization": f"Bearer {self.test_token}"}
        self.assertTrue(is_authorized(headers))


if __name__ == "__main__":
    unittest.main()
