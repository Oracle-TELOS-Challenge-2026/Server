#!/usr/bin/env python3
"""Start the actual application against a fresh, isolated local Oracle QA schema."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18081)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('--port must be 1024..65535')
    # Build before provisioning so a compilation failure does not leave an unused schema.
    subprocess.run(['./gradlew', 'bootJar', '--console=plain'], cwd=ROOT, check=True)
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('store_oracle_verify', ROOT / 'infra/oracle/verify-store.py')
    oracle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(oracle)
    name, password = oracle.create_schema()
    oracle.load(name, (ROOT / 'infra/oracle/local-setup/01ddl.sql').read_text())
    oracle.load(name, (ROOT / 'docs/sql/search-oracle.sql').read_text())
    oracle.load(name, (ROOT / 'qa/store/users.sql').read_text())
    connection = dict(DB_URL=f'jdbc:oracle:thin:@//localhost:{oracle.PORT}/FREEPDB1',
                      DB_USERNAME=name, DB_PASSWORD=password)
    # mkstemp creates a 0600 file. Print its path, not the generated password.
    fd, path = tempfile.mkstemp(prefix='bintime-store-qa-', suffix='.env')
    with os.fdopen(fd, 'w') as file:
        file.write(''.join(f'{key}={value}\n' for key, value in connection.items()))
    print(f'\nQA baseUrl: http://localhost:{args.port}', flush=True)
    print(f'Oracle QA schema: {name} (existing application schema unchanged)', flush=True)
    print(f'QA DB connection file (private): {path}', flush=True)
    print('Import qa/store/Store-QA.postman_collection.json, then run requests in order.', flush=True)
    print('Ctrl+C stops this server. The QA schema remains for inspection.\n', flush=True)
    env = dict(os.environ, **connection)
    # The application-local profile reads Redis settings from the existing local .env.
    os.chdir(ROOT)
    os.execvpe('java', ['java', '-jar', str(ROOT / 'build/libs/bintime.jar'),
                      '--spring.profiles.active=local', '--server.address=127.0.0.1',
                      f'--server.port={args.port}', '--bintime.auth.local-enabled=true',
                      '--bintime.auth.production-enabled=false', '--search.scheduling-enabled=false'], env)


if __name__ == '__main__':
    main()
