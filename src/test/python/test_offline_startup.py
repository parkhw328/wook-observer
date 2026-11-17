"""Startup failures must stay failures and diagnostics must be read-only and redact config."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class OfflineStartupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.bundle = self.work / 'bundle'
        shutil.copytree(ROOT / 'deployment/offline', self.bundle)
        (self.bundle / '.env').write_text('DATABASE_PASSWORD=PRIVATE_FIXTURE_PASSWORD\n')
        (self.bundle / 'images').mkdir()
        (self.bundle / 'images/images.list').write_text('wook-observer:1.12.1 ' + hashlib.sha256(b'fixture-runtime\n').hexdigest() + ' unused\n')
        self.calls = self.work / 'calls.jsonl'
        self.bin = self.work / 'bin'
        self.bin.mkdir()
        docker = self.bin / 'docker'
        docker.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args=sys.argv[1:]
with open(os.environ['OBSERVER_TEST_CALLS'],'a') as f:f.write(json.dumps(args)+'\\n')
if args[:2]==['context','show']:print('fixture-context')
elif args[:2]==['image','inspect']:
 print('linux/amd64' if args[-1]=='{{.Os}}/{{.Architecture}}' else 'fixture-runtime')
elif args and args[0]=='ps':print('previous-app-1 | project=previous-project | 127.0.0.1:43576->8080/tcp')
elif args and args[0]=='compose':
 verb=next((a for a in args if a in ['config','up','ps','port','version']), '')
 if verb=='config' and '--quiet' not in args:print(Path(os.environ['OBSERVER_TEST_CONFIG']).read_text())
 elif verb=='up' and '--help' in args:print('--wait --wait-timeout --pull')
 elif verb=='up':
  if os.environ.get('OBSERVER_TEST_FAILURE'):print('app: bind: address already in use',file=sys.stderr);sys.exit(57)
 elif verb=='port':print('127.0.0.1:43576')
elif args and args[0]=='run':print('DATABASE_PASSWORD=new-private-value\\nOBSERVER_ADMIN_PASSWORD=new-private-value')
''')
        docker.chmod(0o755)
        ss = self.bin / 'ss'
        ss.write_text('#!/bin/sh\nprintf "%s\\n" "State Recv-Q Send-Q Local Peer Process" "ESTAB 0 0 127.0.0.1:43576 192.0.2.1:443 users:((fixture,pid=42))"\n')
        ss.chmod(0o755)
        self.config = self.work / 'config.yaml'
        self.config.write_text('''name: selected-project
services:
  app:
    environment:
      DATABASE_PASSWORD: PRIVATE_FIXTURE_PASSWORD
      OBSERVER_ENCRYPTION_KEY: PRIVATE_FIXTURE_KEY
    ports:
      - mode: ingress
        host_ip: 127.0.0.1
        target: 8080
        published: "43576"
        protocol: tcp
  db:
    environment:
      POSTGRES_PASSWORD: PRIVATE_FIXTURE_PASSWORD
volumes:
  database:
    name: selected-project_database
''')
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'], OBSERVER_TEST_CALLS=str(self.calls), OBSERVER_TEST_CONFIG=str(self.config))
        self.env.pop('COMPOSE_PROJECT_NAME', None)

    def run_script(self, name, **extra):
        return subprocess.run(['sh', str(self.bundle / name)], env=dict(self.env, **extra), text=True, capture_output=True, timeout=15)

    def test_failed_start_preserves_exit_status_and_identifies_non_listening_sockets(self):
        result = self.run_script('start.sh', OBSERVER_TEST_FAILURE='1')
        self.assertEqual(result.returncode, 57, result.stderr)
        for value in ['selected-project', '43576', 'previous-app-1', 'previous-project', 'ESTAB', 'pid=42']:
            self.assertIn(value, result.stdout)
        self.assertNotIn('PRIVATE_FIXTURE', result.stdout + result.stderr)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertFalse(any(any(arg in {'down', 'rm', 'prune', 'kill', 'stop'} for arg in args) for args in calls))
        self.assertEqual(sum('up' in args and '--help' not in args for args in calls), 1)

    def test_diagnose_is_read_only_and_success_does_not_run_failure_diagnostics(self):
        result = self.run_script('diagnose.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('PRIVATE_FIXTURE', result.stdout + result.stderr)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertFalse(any(any(arg in {'up', 'run', 'down', 'rm', 'prune', 'kill', 'stop'} for arg in args) for args in calls))
        success = self.run_script('start.sh')
        self.assertEqual(success.returncode, 0, success.stderr)
        self.assertNotIn('previous-app-1', success.stdout)

    def test_summary_keeps_multiple_addresses_and_ignores_secrets(self):
        data = self.config.read_text().replace('  db:', '      - target: 8080\n        host_ip: "::1"\n        published: "43577"\n        protocol: tcp\n  db:')
        result = subprocess.run(['awk', '-f', str(self.bundle / 'lib/compose-summary.awk')], input=data, text=True, capture_output=True, check=True)
        self.assertEqual(result.stdout.splitlines(), ['PROJECT\tselected-project', 'PORT\t127.0.0.1\t43576\t8080\ttcp', 'PORT\t::1\t43577\t8080\ttcp'])

    def test_new_env_pins_project_and_existing_credentials_are_not_rewritten(self):
        original = (self.bundle / '.env').read_bytes()
        self.assertEqual(self.run_script('init-env.sh').returncode, 0)
        self.assertEqual((self.bundle / '.env').read_bytes(), original)
        for project in ['wook-observer-offline', 'existing-project']:
            (self.bundle / '.env').unlink()
            result = self.run_script('init-env.sh', **({'COMPOSE_PROJECT_NAME': project} if project != 'wook-observer-offline' else {}))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue((self.bundle / '.env').read_text().startswith('COMPOSE_PROJECT_NAME=' + project + '\n'))
            self.assertEqual((self.bundle / '.env').stat().st_mode & 0o777, 0o600)

    def test_source_installation_also_pins_the_project_without_overwriting_existing_env(self):
        source = self.work / 'source'
        (source / 'scripts').mkdir(parents=True)
        shutil.copy2(ROOT / 'scripts/init-env.sh', source / 'scripts/init-env.sh')
        command = ['sh', str(source / 'scripts/init-env.sh')]
        env = dict(self.env, COMPOSE_PROJECT_NAME='existing-source')
        subprocess.run(command, env=env, capture_output=True, check=True)
        saved = (source / '.env').read_bytes()
        self.assertTrue(saved.startswith(b'COMPOSE_PROJECT_NAME=existing-source\n'))
        self.assertEqual((source / '.env').stat().st_mode & 0o777, 0o600)
        self.assertNotEqual(subprocess.run(command, env=env, capture_output=True).returncode, 0)
        self.assertEqual((source / '.env').read_bytes(), saved)


if __name__ == '__main__':
    unittest.main()
