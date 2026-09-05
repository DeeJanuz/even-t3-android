"""Release guard regressions. Run: python3 -m unittest discover -s scripts."""

import unittest
from build_release import verify_manifest


MANIFEST = '''E: manifest (line=1)
  E: application (line=3)
    A: android:allowBackup(0x01010280)=(type 0x12)0x0
    A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0
    E: service (line=4)
      A: android:permission(0x01010006)="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
'''
BADGING = "package: name='dev.evenbridge.companion' versionCode='3' versionName='0.1.2-beta.1'"


class ReleaseGuardTest(unittest.TestCase):
    def test_release_manifest_accepted(self):
        verify_manifest(MANIFEST, BADGING)

    def test_cleartext_or_backup_enablement_rejected(self):
        for attribute in ("allowBackup", "usesCleartextTraffic"):
            changed = "\n".join(line.replace("0x0", "0xffffffff") if attribute in line else line for line in MANIFEST.splitlines())
            with self.assertRaises(ValueError):
                verify_manifest(changed, BADGING)

    def test_missing_explicit_security_control_rejected(self):
        for attribute in ("allowBackup", "usesCleartextTraffic", "BIND_NOTIFICATION_LISTENER_SERVICE"):
            changed = "\n".join(line for line in MANIFEST.splitlines() if attribute not in line)
            with self.assertRaises(ValueError):
                verify_manifest(changed, BADGING)

    def test_debug_build_rejected(self):
        with self.assertRaises(ValueError):
            verify_manifest(MANIFEST, BADGING + "\napplication-debuggable")
        with self.assertRaises(ValueError):
            verify_manifest(MANIFEST + "\nA: android:debuggable(0x0101000f)=(type 0x12)0xffffffff", BADGING)

    def test_debug_receiver_rejected(self):
        with self.assertRaises(ValueError):
            verify_manifest(MANIFEST + "\nE: receiver (line=10)", BADGING)

    def test_other_application_rejected(self):
        with self.assertRaises(ValueError):
            verify_manifest(MANIFEST, BADGING.replace("dev.evenbridge.companion", "other.app"))


if __name__ == "__main__":
    unittest.main()
