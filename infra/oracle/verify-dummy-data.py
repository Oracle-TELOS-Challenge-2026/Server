#!/usr/bin/env python3
"""Load unified DDL and demo INSERTs in a disposable local Oracle schema only."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ddl_verifier', ROOT / 'infra/oracle/verify-unified-ddl.py')
ddl = importlib.util.module_from_spec(spec)
# Import the shared SQL runner without leaving generated files in the repository.
sys.dont_write_bytecode = True
spec.loader.exec_module(ddl)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-scenarios', action='store_true', help='Also exercise real store/search services with the seeded data')
    args = parser.parse_args()
    name = 'BT_DEMO_' + secrets.token_hex(6).upper()
    password = 'BtDemo' + secrets.token_hex(16)
    account = (name, password)
    ddl.sql(f'CREATE USER {name} IDENTIFIED BY "{password}" DEFAULT TABLESPACE USERS QUOTA 200M ON USERS;\n'
            f'GRANT CREATE SESSION,CREATE TABLE TO {name};')
    try:
        ddl.load(account, ROOT / 'infra/oracle/ddl.sql')
        fixture = (ROOT / 'infra/oracle/dummy-data.sql').read_text()
        # Fail after account/shop/service inserts: fixture rollback must remove all of them.
        invalid_fixture, replacements = re.subn(
            r"(VALUES \('demo_slot_reserved_01'[^\n]*?)60000, 60000",
            r"\g<1>60000, 60001", fixture, count=1)
        assert replacements == 1
        try:
            ddl.sql(invalid_fixture, account)
        except RuntimeError as error:
            if 'ORA-02290' not in str(error):
                raise
        else:
            raise AssertionError('Invalid price must reject the fixture')
        for row in ddl.xml_rows(account, 'SELECT table_name FROM user_tables'):
            table = row['TABLE_NAME']
            expected = '256' if table == 'SEARCH_USER_LOCK' else '0'
            assert ddl.sql(f'SELECT COUNT(*) FROM {table};', account).strip() == expected, table
        print('Mid-load constraint failure rolls back all fixture rows: PASS', flush=True)
        # Demonstrate that the fixture's Seoul day is independent of the caller timezone.
        ddl.sql("ALTER SESSION SET TIME_ZONE='-07:00';\n" + (ROOT / 'infra/oracle/dummy-data.sql').read_text(), account)
        ddl.load(account, ROOT / 'infra/oracle/verify-dummy-data.sql')
        print('Oracle INSERT, all 25 table counts, snapshots, overlap, discount, expiry and waitlist links: PASS', flush=True)
        try:
            ddl.load(account, ROOT / 'infra/oracle/dummy-data.sql')
        except RuntimeError as error:
            if 'ORA-20101' not in str(error):
                raise
        else:
            raise AssertionError('Second fixture load must reject a nonempty schema')
        ddl.load(account, ROOT / 'infra/oracle/verify-dummy-data.sql')
        print('Second load rejected without changing existing data: PASS', flush=True)
        if args.run_scenarios:
            env = dict(os.environ, DEMO_ORACLE_URL='jdbc:oracle:thin:@//localhost:' + os.environ.get('STORE_ORACLE_PORT', '1521') + '/FREEPDB1',
                       DEMO_ORACLE_USER=name, DEMO_ORACLE_PASSWORD=password)
            subprocess.run(['./gradlew', 'test', '--tests', 'com.bintime.store.DemoDataOracleTests',
                            '--rerun-tasks', '--console=plain'], cwd=ROOT, env=env, check=True)
        print('Demo data verification passed.', flush=True)
    finally:
        assert re.fullmatch(r'BT_DEMO_[A-F0-9]{12}', name)
        ddl.sql(f'DROP USER {name} CASCADE;')


if __name__ == '__main__':
    main()
