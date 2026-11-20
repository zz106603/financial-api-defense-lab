"""Isolated, assertion-based security mutation evaluations. Python 3.11+, JDK21, Docker."""
import argparse
import hashlib
import json
import os
import shutil
import signal
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT_FILES = ('build.gradle', 'settings.gradle', 'gradlew', 'gradlew.bat')
TEST_CLASS = 'lab.financialapi.account.AccountQueryApiIntegrationTest'
DENIED = '타인_계좌와_위임_범위_밖_조회는_금융정보_없이_404다'
REVOKED = '회수_커밋_후_같은_JWT로_시작한_새_계좌_조회는_거부한다'
FIELDS = '고객_본인_계좌는_허용된_네_필드만_반환한다'
METHODS = (DENIED, REVOKED, FIELDS)
MAPPER = 'src/main/resources/mappers/account/AccountMapper.xml'
A1 = '30000000-0000-4000-8000-000000000001'
A2 = '30000000-0000-4000-8000-000000000002'
B1 = '30000000-0000-4000-8000-000000000003'
MISSING = '30000000-0000-4000-8000-000000000099'
DENIED_NAMES = tuple(
    f'[{index}] sub = "{subject}", type = "{kind}", account = "{account}", suffix = "{suffix}"'
    for index, (subject, kind, account, suffix) in enumerate((
        ('customer-a', 'CUSTOMER', B1, ''), ('customer-a', 'CUSTOMER', B1, '/transactions'),
        ('customer-b', 'CUSTOMER', A2, ''), ('customer-b', 'CUSTOMER', A2, '/transactions'),
        ('partner-x', 'PARTNER', A2, ''), ('partner-x', 'PARTNER', A1, '/transactions'),
        ('partner-y', 'PARTNER', A1, ''), ('partner-y', 'PARTNER', A1, '/transactions'),
        ('customer-a', 'CUSTOMER', MISSING, ''), ('customer-a', 'CUSTOMER', MISSING, '/transactions'),
    ), 1))
EXPECTED_TESTS = {(TEST_CLASS, name) for name in (*DENIED_NAMES, REVOKED + '()', FIELDS + '()')}


@dataclass(frozen=True)
class Case:
    id: str
    policy: str
    path: str
    before: str
    after: str
    test_method: str
    markers: tuple[str, ...]
    expected_names: tuple[str, ...]


CASES = (
    Case('EVAL-01', 'SEC-03', MAPPER, 't.customer_id = #{principalId}',
         'TRUE /* EVAL-01: ownership omitted */', DENIED,
         ('Status expected:<404> but was:<200>',), (DENIED_NAMES[0], DENIED_NAMES[2])),
    Case('EVAL-02', 'SEC-05', MAPPER, 'g.account_id = t.id AND g.partner_id = #{principalId}',
         'g.partner_id = #{principalId} /* EVAL-02: account scope omitted */', DENIED,
         ('Status expected:<404> but was:<200>',), (DENIED_NAMES[4],)),
    Case('EVAL-03', 'SEC-07', MAPPER, "AND g.permission = 'ACCOUNT_READ' AND g.revoked_at IS NULL",
         "AND g.permission = 'ACCOUNT_READ' /* EVAL-03: revocation omitted */", REVOKED,
         ('Status expected:<404> but was:<200>',), (REVOKED + '()',)),
    Case('EVAL-04', 'SEC-09', 'src/main/java/lab/financialapi/account/AccountResponse.java',
         'BigDecimal balance) { }',
         'BigDecimal balance) {\n'
         '    public String getInternalNote() {\n'
         '        return "eval-only";\n'
         '    }\n}', FIELDS,
         ('a collection with size <4>', 'collection size was <5>'), (FIELDS + '()',)),
)


def source_files(root):
    for name in ROOT_FILES:
        yield root / name
    for folder in ('src', 'gradle'):
        base = root / folder
        if base.is_symlink() or (base.exists() and getattr(base.lstat(), 'st_file_attributes', 0) & 0x400):
            raise ValueError('Source directory links/reparse points are not copied')
        for current, directories, files in os.walk(root / folder, followlinks=False):
            for name in directories + files:
                path = Path(current) / name
                if path.is_symlink() or getattr(path.lstat(), 'st_file_attributes', 0) & 0x400:
                    raise ValueError('Source links/reparse points are not copied')
            for name in sorted(files):
                yield Path(current) / name


def fingerprint(root):
    digest = hashlib.sha256()
    for path in sorted(source_files(root)):
        if path.is_symlink():
            raise ValueError('Source links are not copied')
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b'\0' + path.read_bytes() + b'\0')
    return digest.hexdigest()


def snapshot(root, destination):
    destination.mkdir(parents=True, exist_ok=False)
    for source in source_files(root):
        if source.is_symlink() or getattr(source.lstat(), 'st_file_attributes', 0) & 0x400:
            raise ValueError('Source links/reparse points are not copied')
        target = destination / source.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def mutate(root, case):
    path = root / case.path
    text = path.read_text(encoding='utf-8')
    start, end = 0, len(text)
    if case.path == MAPPER:
        start = text.index('<select id="readAccount"')
        end = text.index('</select>', start)
    region = text[start:end]
    if region.count(case.before) != 1:
        raise ValueError(f'{case.id}: expected exactly one mutation anchor')
    text = text[:start] + region.replace(case.before, case.after, 1) + text[end:]
    path.write_text(text, encoding='utf-8')


def read_results(directory):
    results = {}
    try:
        for path in sorted(directory.glob('TEST-*.xml')):
            for test in ET.parse(path).getroot().iter('testcase'):
                key = (test.attrib['classname'], test.attrib['name'])
                if key in results:
                    raise ValueError('Duplicate testcase evidence')
                row = {'status': 'PASS', 'type': '', 'message': ''}
                for tag in ('failure', 'error', 'skipped'):
                    node = test.find(tag)
                    if node is not None:
                        row = {'status': tag.upper(), 'type': node.get('type', ''),
                               'message': node.get('message', '')}
                        break
                results[key] = row
    except (ET.ParseError, KeyError) as error:
        raise ValueError('Malformed JUnit evidence') from error
    return results


def judge(exit_code, results, case=None, baseline_keys=None):
    if not results or any(row['status'] in ('ERROR', 'SKIPPED') for row in results.values()):
        return 'INVALID', 'Missing/error/skipped testcase evidence'
    if baseline_keys is not None and set(results) != baseline_keys:
        return 'INVALID', 'Executed testcase set differs from baseline'
    failed = [(key, row) for key, row in results.items() if row['status'] != 'PASS']
    if case is None:
        return ('PASS', 'Clean control passed') if exit_code == 0 and not failed else (
            'BASELINE_FAILED', 'Unmodified control did not pass')
    if exit_code == 0:
        return ('SURVIVED', 'No security assertion failed') if not failed else (
            'INVALID', 'Exit code and XML disagree')
    if not failed:
        return 'INVALID', 'Build/environment failure without expected assertion'
    for (classname, name), row in failed:
        if (classname != TEST_CLASS or name not in case.expected_names
                or row['status'] != 'FAILURE'
                or row['type'] not in ('java.lang.AssertionError', 'org.opentest4j.AssertionFailedError')
                or not all(marker in row['message'] for marker in case.markers)):
            return 'INVALID', 'Failure is not the expected security assertion'
    if {key[1] for key, row in failed} != set(case.expected_names):
        return 'INVALID', 'Required security invocation failures are missing'
    return 'DETECTED', 'Expected security assertion failed'


def summarize(runs):
    statuses = [row['status'] for row in runs[1:]]
    detected = statuses.count('DETECTED')
    return {
        'planned_mutants': len(CASES), 'detected': detected,
        'survived': statuses.count('SURVIVED'), 'invalid': statuses.count('INVALID'),
        'timeouts': statuses.count('TIMEOUT'),
        'not_run': len(CASES) - len(statuses), 'detection_rate': detected / len(CASES),
        'baseline_tests': runs[0].get('tests', 0),
        'baseline_false_positives': 0 if runs[0]['status'] == 'PASS' else None,
        'agent_identification_accuracy': None, 'agent_repair_iterations': None,
        'agent_tokens': None,
    }


def run_gradle(work, log, timeout):
    wrapper = work / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper), 'test', '--no-daemon', '--no-build-cache', '--no-configuration-cache', '--stacktrace',
               '-Dorg.gradle.jvmargs=-Xmx512m -Djava.net.preferIPv4Stack=true']
    for method in METHODS:
        command += ['--tests', TEST_CLASS + '.' + method]
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(('SPRING_', 'JWT_', 'AUDIT_', 'DETECTION_', 'POSTGRES_'))}
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        environment.pop(name, None)
    environment['JAVA_TOOL_OPTIONS'] = '-Djava.net.preferIPv4Stack=true'
    with log.open('wb') as output:
        process = subprocess.Popen(command, cwd=work, env=environment, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=os.name != 'nt',
                                   creationflags=0x08000000 if os.name == 'nt' else 0)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            if os.name == 'nt':
                subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=15)
            raise TimeoutError('Gradle process tree timed out')


def write_reports(directory, report):
    (directory / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    summary = report['summary']
    lines = ['# Security Evals', '', f"Source: `{report['source_fingerprint']}`",
             f"Git revision: `{report['git_revision']}`", '',
             '| Run | Policy | Result | Tests | Seconds | Evidence |', '|---|---|---|---:|---:|---|']
    for row in report['runs']:
        lines.append(f"| {row['id']} | {row.get('policy') or '-'} | {row['status']} | "
                     f"{row.get('tests', 0)} | {row['seconds']:.3f} | {row['reason']} |")
    lines += ['', f"Detected: {summary['detected']}/{summary['planned_mutants']}; "
              f"rate={summary['detection_rate']:.1%}; baseline false positives="
              f"{summary['baseline_false_positives']}",
              f"Survived={summary['survived']}; invalid={summary['invalid']}; "
              f"timeouts={summary['timeouts']}; not run={summary['not_run']}", '',
              'JUnit failure evidence, logs and source mutation metadata are in report.json and run folders.',
              'Agent identification/repair metrics and tokens: not measured (null).',
              'This measures four known injected defects, not general vulnerability detection accuracy.']
    (directory / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout-seconds', type=int, default=300, help='Per Gradle run timeout')
    options = parser.parse_args(argv)
    if options.timeout_seconds <= 0:
        parser.error('timeout must be positive')
    root = Path(__file__).resolve().parents[1]
    output = root / 'build/security-evals' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    output.mkdir(parents=True, exist_ok=False)
    original = fingerprint(root)
    revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True,
                              text=True, check=True).stdout.strip()
    report = {'risk': 'High', 'scope': 'automated-test-detection',
              'git_revision': revision, 'source_fingerprint': original,
              'started_at': datetime.now(timezone.utc).isoformat(), 'runs': []}
    baseline_keys = None
    started = time.monotonic()
    for case in (None, *CASES):
        name = case.id if case else 'BASELINE'
        run_output = output / name
        run_output.mkdir()
        row = {'id': name, 'policy': case.policy if case else None}
        begin = time.monotonic()
        print(f'{name}: isolated PostgreSQL tests starting', flush=True)
        try:
            with tempfile.TemporaryDirectory(prefix='eval-work-', dir=output) as temporary:
                work = Path(temporary) / 'project'
                snapshot(root, work)
                if case:
                    mutate(work, case)
                    row['mutation'] = {'path': case.path, 'before': case.before, 'after': case.after,
                                       'expected_tests': list(case.expected_names)}
                exit_code = run_gradle(work, run_output / 'gradle.log', options.timeout_seconds)
                xml = work / 'build/test-results/test'
                if xml.exists():
                    shutil.copytree(xml, run_output / 'junit', ignore=shutil.ignore_patterns('binary'))
                results = read_results(xml)
                status, reason = judge(exit_code, results, case, baseline_keys)
                if case is None and status == 'PASS':
                    if set(results) != EXPECTED_TESTS:
                        status, reason = 'INVALID', 'Clean control must execute the 12 specified test invocations'
                    else:
                        baseline_keys = set(results)
                row.update(status=status, reason=reason, exit_code=exit_code, tests=len(results),
                           failures=[{'class': key[0], 'test': key[1], **value}
                                     for key, value in results.items() if value['status'] != 'PASS'])
        except (OSError, ValueError, TimeoutError) as error:
            row.update(status='TIMEOUT' if isinstance(error, TimeoutError) else 'INVALID',
                       reason=type(error).__name__ + ': ' + str(error))
        row['seconds'] = time.monotonic() - begin
        report['runs'].append(row)
        print(f"{name}: {row['status']} ({row['seconds']:.1f}s)", flush=True)
        if case is None and row['status'] != 'PASS':
            break
    report['source_unchanged'] = fingerprint(root) == original
    report['elapsed_seconds'] = time.monotonic() - started
    report['summary'] = summarize(report['runs'])
    write_reports(output, report)
    print(f'Reports: {output / "report.md"}', flush=True)
    return 0 if (report['source_unchanged'] and report['runs'][0]['status'] == 'PASS'
                 and report['summary']['detected'] == len(CASES)) else 1


if __name__ == '__main__':
    raise SystemExit(main())
