"""Exercise launcher device isolation and renderer guards without a real AVD."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


LAUNCHER = Path(__file__).with_name("test-emulator.sh")


@unittest.skipUnless(os.name == "posix", "shell launcher requires a POSIX host")
class EmulatorLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.calls = self.root / "calls"
        self.state = self.root / "state"
        self.state.write_text("device")
        self.renderer = self.root / "renderer"
        self.renderer.write_text("RE Vulkan (Ganesh)\n  Name: Goldfish GFXStream (Apple M5)\n")
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("BACKLOGIUM_")}
        self.env.update(ANDROID_HOME=str(self.root), TMPDIR=str(self.root),
                        MOCK_ROOT=str(self.root), BACKLOGIUM_TEST_EMULATOR_BOOT_TIMEOUT="1")
        self.write_tool("platform-tools/adb", """
            import os, sys
            from pathlib import Path
            root = Path(os.environ['MOCK_ROOT'])
            args = sys.argv[1:]
            with (root / 'calls').open('a') as f:
                f.write('adb ' + ' '.join(args) + '\\n')
            state = (root / 'state').read_text()
            if args == ['devices']:
                print('List of devices attached')
                print('wireless-phone:5555\\tdevice')
                if state:
                    print('emulator-5554\\t' + state)
                sys.exit(0)
            if args[:2] != ['-s', 'emulator-5554']:
                sys.exit('unexpected device target')
            cmd = args[2:]
            if cmd == ['get-state']:
                if state == 'device':
                    print('device')
                else:
                    sys.exit(1)
            elif cmd == ['shell', 'getprop', 'sys.boot_completed']:
                print(os.environ.get('MOCK_BOOT', '1'))
            elif cmd == ['shell', 'dumpsys', 'SurfaceFlinger']:
                print((root / 'renderer').read_text())
            elif cmd == ['emu', 'kill']:
                (root / 'state').write_text('')
            else:
                sys.exit('unexpected adb command')
        """)
        self.write_tool("emulator/emulator", """
            import os, sys
            from pathlib import Path
            root = Path(os.environ['MOCK_ROOT'])
            with (root / 'calls').open('a') as f:
                f.write('emulator ' + ' '.join(sys.argv[1:]) + '\\n')
            (root / 'state').write_text('device')
        """)

    def write_tool(self, name, body):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("#!/usr/bin/env python3\n" + textwrap.dedent(body))
        target.chmod(0o755)

    def run_launcher(self, action="start", **env):
        return subprocess.run(["bash", str(LAUNCHER), action], env=self.env | env,
                              capture_output=True, text=True, timeout=10)

    def test_reuses_hardware_guest_and_targets_only_emulator(self):
        result = self.run_launcher()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("renderer=gpu", result.stdout)
        self.assertIn("ANDROID_SERIAL=emulator-5554", result.stdout)
        calls = self.calls.read_text()
        self.assertNotIn("emulator -avd", calls)
        self.assertNotIn("-s wireless-phone", calls)
        self.assertIn("sys.boot_completed", calls)

    def test_rejects_software_guest_with_restart_hint(self):
        for renderer in ["GLES: SwiftShader Device (LLVM)", "GLES: Mesa lavapipe",
                         "GLES: llvmpipe (LLVM)", "GLES: swangle (Intel)"]:
            with self.subTest(renderer=renderer):
                self.renderer.write_text(renderer)
                result = self.run_launcher()
                self.assertEqual(3, result.returncode)
                self.assertIn("restart", result.stderr)

    def test_unknown_guest_cannot_be_approved_by_stale_host_log(self):
        self.renderer.write_text("GLES: unrecognized device")
        (self.root / "backlogium-test-emulator-5554.log").write_text("gles_mode_selected:host")
        self.assertEqual(3, self.run_launcher().returncode)

    def test_unknown_guest_is_rejected_even_with_escape_hatch(self):
        self.renderer.write_text("")
        self.assertEqual(3, self.run_launcher(BACKLOGIUM_ALLOW_SOFTWARE_GPU="1").returncode)

    def test_explicit_software_escape_hatch(self):
        self.renderer.write_text("GLES: SwiftShader Device (LLVM)")
        result = self.run_launcher(BACKLOGIUM_ALLOW_SOFTWARE_GPU="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("explicitly accepted", result.stderr)

    def test_status_also_rejects_software(self):
        self.renderer.write_text("Name: lavapipe (LLVM)")
        self.assertEqual(3, self.run_launcher("status").returncode)

    def test_new_boot_has_explicit_host_flags_and_no_snapshot(self):
        self.state.write_text("")
        result = self.run_launcher(BACKLOGIUM_TEST_AVD="Test AVD")
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls.read_text()
        self.assertIn("-gpu host -feature GuestAngle", calls)
        self.assertIn("-no-snapshot", calls)
        self.assertIn("-port 5554", calls)

    def test_rejects_unsafe_launch_args_without_starting(self):
        self.state.write_text("")
        for args in ["-feature GuestAngle", "-gpu auto", "-gpu swiftshader_indirect",
                     "-gpu host -gpu host", "-gpu host -gpu software"]:
            with self.subTest(args=args):
                result = self.run_launcher(BACKLOGIUM_TEST_EMULATOR_GPU_ARGS=args)
                self.assertEqual(2, result.returncode)
                self.assertNotIn("emulator -avd", self.calls.read_text())

    def test_stop_does_not_target_phone(self):
        result = self.run_launcher("stop")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", self.state.read_text())
        self.assertIn("adb -s emulator-5554 emu kill", self.calls.read_text())
        self.assertNotIn("adb -s wireless-phone", self.calls.read_text())

    def test_restart_stops_then_boots_with_host_gpu(self):
        result = self.run_launcher("restart")
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls.read_text()
        self.assertLess(calls.index("emu kill"), calls.index("emulator -avd"))

    def test_reused_guest_must_finish_booting(self):
        result = self.run_launcher(MOCK_BOOT="0")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("timed out", result.stderr)

    def test_invalid_port_is_rejected_before_adb(self):
        for port in ["5555", "22", "5684", "55x4"]:
            with self.subTest(port=port):
                self.assertEqual(2, self.run_launcher(BACKLOGIUM_TEST_EMULATOR_PORT=port).returncode)
        self.assertFalse(self.calls.exists())


if __name__ == "__main__":
    unittest.main()
