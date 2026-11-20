import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest.mock import Mock, patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import security_evals as ev


class EvalHarnessTest(unittest.TestCase):
    def setUp(self):
        self.case = ev.CASES[0]
        self.key = (ev.TEST_CLASS, self.case.expected_names[0])
        self.passed = {(ev.TEST_CLASS, name): {'status': 'PASS', 'type': '', 'message': ''}
                       for name in self.case.expected_names}
        self.failed = {(ev.TEST_CLASS, name): {
            'status': 'FAILURE', 'type': 'java.lang.AssertionError',
            'message': 'Status expected:<404> but was:<200>'} for name in self.case.expected_names}

    def test_clean_control_pass_and_failure_are_distinguished(self):
        self.assertEqual('PASS', ev.judge(0, self.passed)[0])
        self.assertEqual('BASELINE_FAILED', ev.judge(1, self.failed)[0])

    def test_only_expected_security_assertion_is_detected(self):
        self.assertEqual('DETECTED', ev.judge(1, self.failed, self.case, set(self.passed))[0])
        for field, value in [('type', 'org.springframework.jdbc.BadSqlGrammarException'),
                             ('message', 'Connection refused'), ('status', 'ERROR'),
                             ('status', 'SKIPPED')]:
            rows = dict(self.failed)
            rows[self.key] = dict(rows[self.key], **{field: value})
            self.assertEqual('INVALID', ev.judge(1, rows, self.case, set(self.passed))[0])

    def test_survived_is_not_detected_and_exitcode_must_agree(self):
        self.assertEqual('SURVIVED', ev.judge(0, self.passed, self.case, set(self.passed))[0])
        self.assertEqual('INVALID', ev.judge(0, self.failed, self.case, set(self.passed))[0])
        self.assertEqual('INVALID', ev.judge(1, self.passed, self.case, set(self.passed))[0])

    def test_no_results_wrong_test_or_changed_test_set_are_invalid(self):
        self.assertEqual('INVALID', ev.judge(1, {}, self.case, set(self.passed))[0])
        self.assertEqual('INVALID', ev.judge(1, self.failed, self.case, set())[0])
        wrong = {('OtherTest', self.key[1]): self.failed[self.key]}
        self.assertEqual('INVALID', ev.judge(1, wrong, self.case, set(wrong))[0])

    def test_xml_is_evidence_and_duplicate_or_malformed_xml_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'TEST-sample.xml'
            path.write_text('<testsuite><testcase classname="Example" name="one">'
                            '<failure type="java.lang.AssertionError" message="expected"/>'
                            '</testcase></testsuite>', encoding='utf-8')
            rows = ev.read_results(root)
            self.assertEqual('FAILURE', rows[('Example', 'one')]['status'])
            (root / 'TEST-duplicate.xml').write_bytes(path.read_bytes())
            with self.assertRaises(ValueError):
                ev.read_results(root)
            (root / 'TEST-duplicate.xml').unlink()
            path.write_text('<broken', encoding='utf-8')
            with self.assertRaises(ValueError):
                ev.read_results(root)

    def test_mutation_is_scoped_and_requires_exactly_one_match(self):
        case = self.case
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / case.path
            target.parent.mkdir(parents=True)
            content = '<select id="readAccount">' + case.before + '</select>' + case.before
            target.write_text(content, encoding='utf-8')
            ev.mutate(root, case)
            self.assertEqual(1, target.read_text(encoding='utf-8').count(case.before))
            with self.assertRaises(ValueError):
                ev.mutate(root, case)
            target.write_text('<select id="readAccount">' + case.before * 2 + '</select>', encoding='utf-8')
            with self.assertRaises(ValueError):
                ev.mutate(root, case)

    def test_snapshot_does_not_copy_env_keys_build_or_git(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'repo'
            root.mkdir()
            for name in ev.ROOT_FILES:
                (root / name).write_text('build configuration', encoding='utf-8')
            for name in ('src/main/file.java', 'gradle/wrapper/file.properties',
                         '.env', '.local-dev/private.pem', 'build/app.jar', '.git/config'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('synthetic', encoding='utf-8')
            copy = Path(directory) / 'copy'
            ev.snapshot(root, copy)
            self.assertTrue((copy / 'src/main/file.java').exists())
            for name in ('.env', '.local-dev', 'build', '.git'):
                self.assertFalse((copy / name).exists())
            self.assertEqual(ev.fingerprint(root), ev.fingerprint(copy))

    def test_report_counts_survivors_and_invalids_without_invented_agent_metrics(self):
        report = ev.summarize([{'status': 'PASS', 'tests': 12},
                               {'status': 'DETECTED'}, {'status': 'SURVIVED'},
                               {'status': 'INVALID'}, {'status': 'DETECTED'}])
        self.assertEqual(2, report['detected'])
        self.assertEqual(4, report['planned_mutants'])
        self.assertEqual(0.5, report['detection_rate'])
        self.assertEqual(0, report['baseline_false_positives'])
        self.assertIsNone(report['agent_tokens'])

    def test_gradle_parameterized_display_names_are_recognized(self):
        key = (ev.TEST_CLASS, '[1] sub = "customer-a", type = "CUSTOMER", account = "30000000-0000-4000-8000-000000000003", suffix = ""')
        rows = dict(self.failed)
        rows[key] = self.failed[self.key]
        self.assertEqual('DETECTED', ev.judge(1, rows, self.case, set(rows))[0])

    def test_timeout_is_counted_and_wrong_principal_assertion_is_not_detected(self):
        summary = ev.summarize([{'status': 'PASS', 'tests': 12}, {'status': 'TIMEOUT'}])
        self.assertEqual(1, summary['timeouts'])
        self.assertEqual(3, summary['not_run'])
        partner_case = ev.CASES[1]
        self.assertEqual('INVALID', ev.judge(1, self.failed, partner_case, set(self.failed))[0])
        rows = {(ev.TEST_CLASS, partner_case.expected_names[0]): self.failed[self.key]}
        self.assertEqual('INVALID', ev.judge(1, rows, self.case, set(rows))[0])

    def test_timeout_terminates_the_owned_process_tree(self):
        process = Mock(pid=12345)
        process.wait.side_effect = [ev.subprocess.TimeoutExpired('gradle', 1), 0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(ev.subprocess, 'Popen', return_value=process), \
                    patch.object(ev.subprocess, 'run') as taskkill, \
                    patch.object(ev.os, 'killpg', create=True) as killpg:
                with self.assertRaises(TimeoutError):
                    ev.run_gradle(root, root / 'log.txt', 1)
                if ev.os.name == 'nt':
                    self.assertEqual(['taskkill', '/PID', '12345', '/T', '/F'], taskkill.call_args.args[0])
                else:
                    killpg.assert_called_once_with(12345, ev.signal.SIGKILL)
                self.assertEqual(2, process.wait.call_count)

    def test_baseline_failure_stops_mutations_and_still_writes_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ev.ROOT_FILES:
                (root / name).write_text('test configuration', encoding='utf-8')
            (root / 'scripts').mkdir()
            with patch.object(ev, '__file__', str(root / 'scripts/security_evals.py')), \
                    patch.object(ev, 'run_gradle', return_value=1) as run, \
                    patch.object(ev, 'read_results', return_value=self.failed), \
                    patch.object(ev.subprocess, 'run', return_value=Mock(stdout='test-revision\n')):
                with redirect_stdout(StringIO()):
                    self.assertEqual(1, ev.main([]))
                self.assertEqual(1, run.call_count)
            reports = list((root / 'build/security-evals').glob('*/report.json'))
            self.assertEqual(1, len(reports))
            report = ev.json.loads(reports[0].read_text(encoding='utf-8'))
            self.assertEqual('BASELINE_FAILED', report['runs'][0]['status'])
            self.assertTrue(report['source_unchanged'])
            self.assertEqual(4, report['summary']['not_run'])


if __name__ == '__main__':
    unittest.main()
