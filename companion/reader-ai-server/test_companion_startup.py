"""Startup and Windows instance-handoff regressions."""
from __future__ import annotations

import ctypes
import errno
import json
import os
import socket
import subprocess
import sys
import threading
import time
import unittest
from contextlib import ExitStack
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

import reader_ai_companion as companion


class CompanionStartupTests(unittest.TestCase):
    def setUp(self):
        self.log_patch = patch.object(companion, "_emit_log_line")
        self.log_patch.start()
        self.addCleanup(self.log_patch.stop)

    def test_bind_failure_preserves_os_error_before_workers_exist(self):
        server = companion.ReaderAiServer(("127.0.0.1", 0), companion.Config(mode="mock_copy"))
        self.addCleanup(server.server_close)
        with self.assertRaises(OSError) as raised:
            companion.ReaderAiServer(server.server_address, companion.Config(mode="mock_copy"))
        self.assertIn(raised.exception.errno, (errno.EADDRINUSE, 10048, 10013))

    def test_bind_race_closes_discovered_instance_and_retries(self):
        config = companion.Config(host="127.0.0.1", port=0, mode="mock_copy")
        original_bind = companion.ReaderAiServer.server_bind
        attempts = []

        def bind(server):
            attempts.append(server)
            if len(attempts) == 1:
                raise OSError(10048, "Address already in use")
            original_bind(server)

        with (
            patch.object(companion.ReaderAiServer, "server_bind", bind),
            patch.object(companion, "_find_listening_process_id", side_effect=[None, 91234, None]),
            patch.object(companion, "_close_existing_instance", return_value=True) as close,
        ):
            server = companion._create_server_with_duplicate_resolution(config)
        self.assertIsNotNone(server)
        self.addCleanup(server.server_close)
        self.assertEqual(len(attempts), 2)
        close.assert_called_once_with(0, 91234, "127.0.0.1")

    def test_windows_bind_uses_exclusive_socket_without_reuse(self):
        server = companion.ReaderAiServer.__new__(companion.ReaderAiServer)
        server.socket = Mock()
        with (
            patch.object(companion.os, "name", "nt"),
            patch.object(companion.socket, "SO_EXCLUSIVEADDRUSE", -5, create=True),
            patch.object(companion.ThreadingHTTPServer, "server_bind") as bind,
        ):
            server.server_bind()
        self.assertFalse(server.allow_reuse_address)
        self.assertFalse(server.allow_reuse_port)
        server.socket.setsockopt.assert_called_once_with(socket.SOL_SOCKET, -5, 1)
        bind.assert_called_once_with()

    def test_native_listener_lookup_handles_table_growth_and_host_filter(self):
        rows = [
            companion._WindowsTcpListener(2, 0, socket.htons(9999), 0, 0, 11),
            companion._WindowsTcpListener(2, int.from_bytes(socket.inet_aton("192.0.2.4"), "little"), socket.htons(8765), 0, 0, 22),
            companion._WindowsTcpListener(2, 0, socket.htons(8765), 0, 0, 33),
        ]
        payload = bytes(ctypes.c_uint32(len(rows))) + b"".join(bytes(row) for row in rows)
        calls = []

        def get_table(buffer, size, ordered, family, table_class, reserved):
            calls.append(buffer)
            self.assertEqual((family, table_class, reserved), (socket.AF_INET, 3, 0))
            size_pointer = ctypes.cast(size, ctypes.POINTER(ctypes.c_uint32))
            if len(calls) <= 2:
                size_pointer.contents.value = len(payload) + len(calls) * 32
                return 122
            ctypes.memmove(buffer, payload, len(payload))
            return 0

        api = Mock()
        api.GetExtendedTcpTable.side_effect = get_table
        with (
            patch.object(companion.os, "name", "nt"),
            patch.object(companion.ctypes, "WinDLL", return_value=api, create=True),
            patch.object(companion, "_run_hidden_windows_command") as command,
        ):
            self.assertEqual(companion._find_listening_process_id(8765, "127.0.0.1"), 33)
        self.assertEqual(len(calls), 3)
        command.assert_not_called()

    def test_listener_inspection_failure_is_not_treated_as_a_free_port(self):
        api = Mock()
        api.GetExtendedTcpTable.return_value = 5
        with (
            patch.object(companion.os, "name", "nt"),
            patch.object(companion.ctypes, "WinDLL", return_value=api, create=True),
            self.assertRaisesRegex(OSError, "Cannot inspect"),
        ):
            companion._find_listening_process_id(8765)

    def test_process_identity_uses_full_width_handle_and_closes_it(self):
        api = Mock()
        handle = 0x123456789
        api.OpenProcess.return_value = handle

        def query(received_handle, flags, buffer, size):
            self.assertEqual(received_handle, handle)
            buffer.value = r"C:\Users\Reader\MihonAiCompanion.exe"
            return 1

        api.QueryFullProcessImageNameW.side_effect = query
        with patch.object(companion.ctypes, "WinDLL", return_value=api, create=True):
            self.assertEqual(companion._windows_process_executable(123), r"C:\Users\Reader\MihonAiCompanion.exe")
        self.assertIs(api.OpenProcess.restype, ctypes.c_void_p)
        api.CloseHandle.assert_called_once_with(handle)

    def test_unrelated_listener_is_never_terminated(self):
        connection = Mock()
        connection.getresponse.return_value.status = 200
        connection.getresponse.return_value.read.return_value = json.dumps({"ok": True}).encode()
        with (
            patch.object(companion, "_windows_process_executable", return_value=r"C:\Other\service.exe"),
            patch.object(companion, "_find_windows_listener", return_value=(123, "127.0.0.1")),
            patch.object(companion, "HTTPConnection", return_value=connection),
            patch.object(companion, "_run_hidden_windows_command") as command,
            patch.object(companion, "_show_duplicate_close_failed_dialog"),
        ):
            self.assertFalse(companion._close_existing_instance(8765, 123))
        command.assert_not_called()

    def test_renamed_or_source_companion_is_identified_by_existing_health_api(self):
        server = companion.ReaderAiServer(("127.0.0.1", 0), companion.Config(mode="mock_copy"))
        self.addCleanup(server.server_close)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(server.shutdown)
        with (
            patch.object(companion, "_windows_process_executable", return_value=r"C:\Python\python.exe"),
            patch.object(companion, "_find_windows_listener", return_value=(123, "127.0.0.1")),
        ):
            self.assertTrue(companion._is_companion_process(server.server_port, 123, "127.0.0.1"))

    def test_wildcard_lookup_does_not_identify_other_address_from_companion_health(self):
        companion_health = {
            "ok": True, "mode": "mock_copy", "companion_release_tag": "v0.1.23",
            "supported_models": ["realesr-animevideov3"],
        }
        requested_addresses = []

        def connect(host, port, timeout):
            requested_addresses.append(host)
            response = Mock(status=200)
            response.read.return_value = json.dumps(companion_health if host == "127.0.0.1" else {"ok": True}).encode()
            connection = Mock()
            connection.getresponse.return_value = response
            return connection

        with (
            patch.object(companion, "_windows_process_executable", return_value=r"C:\Other\service.exe"),
            patch.object(companion, "_find_windows_listener", return_value=(123, "127.0.0.2")),
            patch.object(companion, "HTTPConnection", side_effect=connect),
            patch.object(companion, "_run_hidden_windows_command") as command,
            patch.object(companion, "_show_duplicate_close_failed_dialog"),
        ):
            self.assertFalse(companion._close_existing_instance(8765, 123, "0.0.0.0"))
        self.assertEqual(requested_addresses, ["127.0.0.2"])
        command.assert_not_called()

    def test_health_identity_is_rejected_if_probe_address_owner_changes(self):
        connection = Mock()
        connection.getresponse.return_value.status = 200
        connection.getresponse.return_value.read.return_value = json.dumps({
            "ok": True, "mode": "mock_copy", "companion_release_tag": "v0.1.23",
            "supported_models": ["realesr-animevideov3"],
        }).encode()
        with (
            patch.object(companion, "_windows_process_executable", return_value=r"C:\Python\python.exe"),
            patch.object(companion, "_find_windows_listener", return_value=(123, "127.0.0.2")),
            patch.object(companion, "_find_listening_process_id", side_effect=[123, 456]),
            patch.object(companion, "HTTPConnection", return_value=connection),
        ):
            self.assertFalse(companion._is_companion_process(8765, 123, "0.0.0.0"))

    def test_previous_release_closes_listener_and_worker_tree(self):
        with (
            patch.object(companion, "_windows_process_executable", return_value=r"C:\Downloads\MihonAiCompanion-v0.1.23-windows (1).exe"),
            patch.object(companion, "_find_listening_process_id", side_effect=[123, None]),
            patch.object(companion, "_run_hidden_windows_command", return_value=subprocess.CompletedProcess([], 0, "", "")) as command,
        ):
            self.assertTrue(companion._close_existing_instance(8765, 123))
        command.assert_called_once_with(["taskkill", "/PID", "123", "/T", "/F"])

    def test_listener_change_during_identity_check_does_not_kill_new_process(self):
        with (
            patch.object(companion, "_is_companion_process", return_value=True),
            patch.object(companion, "_find_listening_process_id", return_value=456),
            patch.object(companion, "_run_hidden_windows_command") as command,
        ):
            self.assertFalse(companion._close_existing_instance(8765, 123))
        command.assert_not_called()

    def test_old_instance_disappearing_during_taskkill_is_successful(self):
        with (
            patch.object(companion, "_is_companion_process", return_value=True),
            patch.object(companion, "_find_listening_process_id", side_effect=[123, None]),
            patch.object(companion, "_run_hidden_windows_command", return_value=subprocess.CompletedProcess([], 128, "", "Process not found")),
        ):
            self.assertTrue(companion._close_existing_instance(8765, 123))

    def test_multiple_legacy_listeners_are_closed(self):
        with (
            patch.object(companion, "_find_listening_process_id", side_effect=[91234, 91235, None]),
            patch.object(companion, "_close_existing_instance", return_value=True) as close,
        ):
            self.assertTrue(companion._close_previous_instances(companion.Config()))
        self.assertEqual([call.args[1] for call in close.call_args_list], [91234, 91235])

    def test_handoff_finishes_before_self_update_can_replace_executable(self):
        order = []
        with (
            patch.object(companion.sys, "argv", ["companion", "--mode", "mock_copy"]),
            patch.object(companion, "_configure_output_tee", return_value=Path("companion.log")),
            patch.object(companion, "_LOG_FILE_HANDLE", None),
            patch.object(companion.Config, "load", return_value=companion.Config(mode="mock_copy")),
            patch.object(companion, "_close_previous_instances", side_effect=lambda config: order.append("close") or True),
            patch.object(companion, "_maybe_perform_self_update", side_effect=lambda **kwargs: order.append("update") or True),
        ):
            self.assertEqual(companion.main(), 0)
        self.assertEqual(order, ["close", "update"])

    def test_updater_relaunch_uses_independent_pyinstaller_environment(self):
        with TemporaryDirectory() as temporary:
            with (
                patch.object(companion.tempfile, "gettempdir", return_value=temporary),
                patch.object(companion.subprocess, "Popen") as popen,
            ):
                companion._launch_windows_self_replace(
                    current_executable=Path(temporary) / "companion.exe",
                    downloaded_executable=Path(temporary) / "download.exe",
                    relaunch_arguments=[],
                )
            self.assertEqual(popen.call_args.kwargs["env"]["PYINSTALLER_RESET_ENVIRONMENT"], "1")
            self.assertTrue(popen.call_args.kwargs["close_fds"])
            script = Path(popen.call_args.args[0][-1]).read_text()
            self.assertIn("if ($arguments.Length -gt 0)", script)
            self.assertIn("  Start-Process -FilePath $target\n}", script)

    def test_updater_preserves_windows_command_line_argument_boundaries(self):
        arguments = ["--config", r"C:\Users\Читатель\Reader's settings\reader config.json", "--token", 'value with "quotes"', ""]
        with TemporaryDirectory() as temporary:
            with (
                patch.object(companion.tempfile, "gettempdir", return_value=temporary),
                patch.object(companion.subprocess, "Popen") as popen,
            ):
                companion._launch_windows_self_replace(
                    current_executable=Path(temporary) / "companion.exe",
                    downloaded_executable=Path(temporary) / "download.exe",
                    relaunch_arguments=arguments,
                )
            script_path = Path(popen.call_args.args[0][-1])
            self.assertTrue(script_path.read_bytes().startswith(b"\xef\xbb\xbf"))
            script = script_path.read_text(encoding="utf-8-sig")
        argument_assignment = next(line for line in script.splitlines() if line.startswith("$arguments = "))
        self.assertEqual(
            argument_assignment,
            "$arguments = '--config \"C:\\Users\\Читатель\\Reader''s settings\\reader config.json\" --token \"value with \\\"quotes\\\"\" \"\"'",
        )
        self.assertIn("Start-Process -FilePath $target -ArgumentList $arguments", script)

    @unittest.skipUnless(os.name == "nt", "Requires Windows PowerShell/native argument parsing")
    def test_windows_updater_arguments_reach_relaunched_process_unchanged(self):
        arguments = ["--config", r"C:\Users\Читатель\Reader's settings\reader config.json", 'value with "quotes"', ""]
        with TemporaryDirectory() as temporary:
            script_dir = Path(temporary) / "Читатель's launch directory"
            script_dir.mkdir()
            capture_script = script_dir / "capture arguments.py"
            output_path = script_dir / "captured arguments.json"
            capture_script.write_text(
                "import json, sys\nfrom pathlib import Path\n"
                "Path(sys.argv[1]).write_text(json.dumps(sys.argv[2:]), encoding='utf-8')\n",
                encoding="utf-8",
            )
            with (
                patch.object(companion.tempfile, "gettempdir", return_value=temporary),
                patch.object(companion.subprocess, "Popen") as popen,
            ):
                companion._launch_windows_self_replace(
                    current_executable=Path(sys.executable),
                    downloaded_executable=Path(temporary) / "download.exe",
                    relaunch_arguments=[str(capture_script), str(output_path), *arguments],
                )
            updater = Path(popen.call_args.args[0][-1]).read_text(encoding="utf-8-sig")
            assignment = next(line for line in updater.splitlines() if line.startswith("$arguments = "))
            launch_script = script_dir / "test restart.ps1"
            launch_script.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                + f"$target = {companion._powershell_single_quoted(sys.executable)}\n"
                + assignment + "\n"
                + "Start-Process -FilePath $target -ArgumentList $arguments -Wait -NoNewWindow\n",
                encoding="utf-8-sig",
            )
            subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(launch_script)],
                check=True, timeout=20, capture_output=True,
            )
            self.assertEqual(json.loads(output_path.read_text(encoding="utf-8")), arguments)

    @unittest.skipUnless(os.name == "nt", "Requires the Windows TCP/process APIs")
    def test_windows_second_launch_replaces_running_source_instance(self):
        self.assert_windows_instance_handoff([sys.executable, str(Path(companion.__file__).resolve())])

    @unittest.skipUnless(
        os.name == "nt" and os.environ.get("MIHON_AI_TEST_COMPANION_EXE"),
        "Requires Windows and MIHON_AI_TEST_COMPANION_EXE pointing to the built EXE",
    )
    def test_windows_second_launch_replaces_running_frozen_instance(self):
        executable = Path(os.environ["MIHON_AI_TEST_COMPANION_EXE"]).resolve()
        self.assertTrue(executable.is_file(), f"Companion EXE was not built: {executable}")
        self.assert_windows_instance_handoff([str(executable)], frozen=True)

    def assert_windows_instance_handoff(self, command_prefix, frozen=False):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        processes = []
        listener_pids = []
        with TemporaryDirectory() as temporary, ExitStack() as outputs:
            config = Path(temporary) / "config.json"
            config.write_text(json.dumps({
                "mode": "mock_copy", "host": "127.0.0.1", "port": port,
                "prefer_discrete_gpu": False,
            }))
            command = [*command_prefix, "--config", str(config), "--skip-self-update"]
            try:
                for launch_index in range(2):
                    log_path = Path(temporary) / f"launch-{launch_index}.log"
                    output = outputs.enter_context(log_path.open("wb"))
                    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
                    processes.append(process)
                    deadline = time.monotonic() + (90 if frozen else 15)
                    while time.monotonic() < deadline:
                        if process.poll() is not None:
                            self.fail(f"New companion exited with {process.returncode}: {log_path.read_text(errors='replace')}")
                        listener_pid = companion._find_listening_process_id(port, "127.0.0.1")
                        if listener_pid is not None and listener_pid not in listener_pids:
                            if frozen:
                                # The launched PID is the bootloader parent; the
                                # child from the same EXE owns the HTTP listener.
                                executable = companion._windows_process_executable(listener_pid)
                                correct_process = (
                                    listener_pid != process.pid and executable is not None
                                    and Path(executable) == Path(command_prefix[0])
                                )
                            else:
                                correct_process = listener_pid == process.pid
                            if correct_process and self.companion_is_healthy(port):
                                listener_pids.append(listener_pid)
                                break
                        time.sleep(0.1)
                    else:
                        self.fail(f"New companion did not acquire its listening port: {log_path.read_text(errors='replace')}")
                # Closing only the listener is insufficient if the old onefile
                # bootloader/console keeps running. Wait for its cleanup too.
                processes[0].wait(timeout=15)
                self.assertIsNone(companion._windows_process_executable(listener_pids[0]))
                self.assertIsNone(processes[1].poll())
                self.assertEqual(companion._find_listening_process_id(port, "127.0.0.1"), listener_pids[1])
                self.assertTrue(self.companion_is_healthy(port))
            finally:
                for process in reversed(processes):
                    if process.poll() is None:
                        companion._run_hidden_windows_command(["taskkill", "/PID", str(process.pid), "/T", "/F"])
                    process.wait(timeout=15)

    @staticmethod
    def companion_is_healthy(port):
        connection = companion.HTTPConnection("127.0.0.1", port, timeout=1)
        try:
            connection.request("GET", "/health")
            response = connection.getresponse()
            payload = json.loads(response.read())
            return response.status == 200 and payload.get("ok") is True and payload.get("mode") == "mock_copy"
        except (OSError, companion.HTTPException, ValueError):
            return False
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
