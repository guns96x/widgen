import unittest
import os
import sys
from datetime import datetime, timezone, timedelta

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from widgen_bridge import format_duration, parse_reset_time


class TestTimeFormat(unittest.TestCase):

    def test_format_duration_zero_or_negative(self):
        self.assertEqual(format_duration(0), "Now")
        self.assertEqual(format_duration(-10), "Now")

    def test_format_duration_minutes(self):
        self.assertEqual(format_duration(120), "2m")
        self.assertEqual(format_duration(3599), "59m")

    def test_format_duration_hours(self):
        self.assertEqual(format_duration(3600), "1h 0m")
        self.assertEqual(format_duration(7500), "2h 5m")

    def test_format_duration_days(self):
        self.assertEqual(format_duration(86400), "1d 0h")
        self.assertEqual(format_duration(90000), "1d 1h")

    def test_parse_reset_time_empty(self):
        iso, secs, readable = parse_reset_time(None)
        self.assertIsNone(iso)
        self.assertEqual(secs, 0)
        self.assertEqual(readable, "Unknown")

    def test_parse_reset_time_future(self):
        future = datetime.now(timezone.utc) + timedelta(minutes=45)
        iso_str = future.isoformat()
        iso, secs, readable = parse_reset_time(iso_str)
        self.assertIsNotNone(iso)
        self.assertGreater(secs, 0)
        self.assertTrue("m" in readable or "h" in readable)


if __name__ == "__main__":
    unittest.main()
