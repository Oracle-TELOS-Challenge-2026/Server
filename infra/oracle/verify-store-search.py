#!/usr/bin/env python3
"""Run store outbox -> search -> real local ES tests in a new isolated Oracle schema.

No .env credentials or application schema are used. OpenAI and notification delivery
are excluded. The test index is deleted by the test; the Oracle schema is retained
for inspection, matching verify-store.py.
"""
import os
from pathlib import Path
import runpy
import subprocess
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]


def main():
    es = os.environ.get('SEARCH_ELASTICSEARCH_URL', 'http://localhost:9200')
    if urlparse(es).hostname not in ('localhost', '127.0.0.1'):
        raise RuntimeError('A disposable local Elasticsearch endpoint is required')
    helpers = runpy.run_path(str(ROOT / 'infra/oracle/verify-store.py'))
    schema, password = helpers['create_schema']()
    helpers['load'](schema, (ROOT / 'infra/oracle/local-setup/01ddl.sql').read_text())
    helpers['load'](schema, (ROOT / 'docs/sql/search-oracle.sql').read_text())
    print('Isolated Oracle schema: ' + schema, flush=True)
    env = dict(os.environ, FLOW_ORACLE_URL=f"jdbc:oracle:thin:@//localhost:{helpers['PORT']}/FREEPDB1",
               FLOW_ORACLE_USER=schema, FLOW_ORACLE_PASSWORD=password,
               SEARCH_ELASTICSEARCH_URL=es)
    subprocess.run(['./gradlew', 'test', '--tests', 'com.bintime.search.StoreEventFlowTests',
                    '--rerun-tasks', '--no-daemon', '--console=plain'], cwd=ROOT, env=env, check=True)
    print('Real Oracle + Elasticsearch store event flow passed. Application DB untouched.', flush=True)


if __name__ == '__main__':
    main()
