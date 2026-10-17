#!/usr/bin/env python3
"""Exercise deploy.sh with fake tools; never contacts Docker, SSH or the real DB."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = (ROOT / 'deploy.sh').read_text()

with tempfile.TemporaryDirectory(prefix='bintime-deploy-test-') as temporary:
    root = Path(temporary)
    project = root / 'server'
    project.mkdir()
    (project / 'deploy.sh').write_text(SCRIPT)
    mock = root / 'mock'
    mock.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
tool = Path(sys.argv[0]).name
args = sys.argv[1:]
if tool == 'sudo':
    tool, args = args[1], args[2:]
with open(os.environ['DEPLOY_TEST_LOG'], 'a') as log:
    log.write(json.dumps([tool, *args]) + '\\n')
failure = os.environ.get('DEPLOY_TEST_FAILURE', '')
if tool == 'docker' and args == ['info'] and failure == 'docker':
    sys.exit(1)
if tool == 'git' and args == ['rev-parse', 'HEAD']:
    print('a' * 40)
if tool == 'git' and args == ['diff', '--name-only', '--diff-filter=U'] and failure == 'merge':
    print('conflicted.java')
if tool == 'ssh' and failure == 'ssh':
    sys.exit(1)
if tool == 'docker' and args[:2] == ['image', 'inspect']:
    print('sha256:expected')
if tool == 'docker' and args[0] == 'inspect':
    print(('wrong' if failure == 'image' else 'sha256:expected') if '{{.Image}}' in args
          else ('wrong' if failure == 'revision' else 'a' * 40))
if tool == 'docker' and 'ps' in args:
    print('test-app-id')
if tool == 'docker' and '--environment' in args:
    print('FCM_CREDENTIALS_FILE=/test/firebase.json')
''')
    mock.chmod(0o755)
    for tool in ['docker', 'git', 'ssh', 'scp', 'curl', 'sudo']:
        (root / tool).symlink_to(mock)
    (project / 'gradlew').symlink_to(mock)
    env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
               DEPLOY_TEST_LOG=str(root / 'calls.jsonl'))

    for failure in ['', 'docker', 'merge', 'ssh']:
        Path(env['DEPLOY_TEST_LOG']).write_text('')
        result = subprocess.run(['bash', str(project / 'deploy.sh')], cwd=root,
                                env=dict(env, DEPLOY_TEST_FAILURE=failure), capture_output=True)
        calls = [json.loads(line) for line in Path(env['DEPLOY_TEST_LOG']).read_text().splitlines()]
        assert (result.returncode == 0) == (failure == ''), failure
        if failure:
            assert not any(call[0] == 'gradlew' for call in calls), failure
            assert not any(call[:2] == ['docker', 'build'] for call in calls), failure
            continue
        build = next(call for call in calls if call[:2] == ['docker', 'build'])
        assert build[build.index('--platform') + 1] == 'linux/arm64'
        assert build[build.index('--label') + 1] == 'org.opencontainers.image.revision=' + 'a' * 40
        assert calls.index(next(call for call in calls if call[0] == 'gradlew')) < calls.index(build)
        assert any(call[:2] == ['docker', 'save'] for call in calls)
        assert any(call[0] == 'ssh' and call[-1] == 'a' * 40 for call in calls)
        assert not any(call[:2] in [['docker', 'push'], ['git', 'push']] for call in calls)
        transfers = [arg for call in calls if call[0] == 'scp' for arg in call[1:]]
        assert not any(Path(arg).name == '.env' or arg.endswith('.json') for arg in transfers)

    remote = SCRIPT.split("<<'REMOTE'\n", 1)[1].split('\nREMOTE', 1)[0]
    subprocess.run(['bash', '-n'], input=remote.encode(), check=True)
    (root / 'bintime').mkdir()
    for failure in ['', 'image', 'revision']:
        Path(env['DEPLOY_TEST_LOG']).write_text('')
        result = subprocess.run(['bash', '-s', '--', 'a' * 40], input=remote.encode(),
                                env=dict(env, HOME=str(root), DEPLOY_TEST_FAILURE=failure),
                                capture_output=True)
        calls = [json.loads(line) for line in Path(env['DEPLOY_TEST_LOG']).read_text().splitlines()]
        assert (result.returncode == 0) == (failure == ''), (failure, result.stdout, result.stderr, calls)
        assert any('compose.fcm.yaml' in call for call in calls)
        if failure:
            assert not any(call[0] == 'curl' for call in calls)

print('deploy.sh: build/transfer/FCM checks and Docker/merge/SSH/image/revision failure checks passed')
