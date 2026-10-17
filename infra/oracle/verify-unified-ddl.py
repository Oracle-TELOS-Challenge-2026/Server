#!/usr/bin/env python3
"""Compare fresh unified DDL with split DDL and the full historical migration chain.

Uses only newly generated BT_DDL_* schemas in the local Oracle container. No app
credentials or third-party Python packages are needed. Test schemas are removed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
CONTAINER = os.environ.get('STORE_ORACLE_CONTAINER', 'bintime-dev-oracle-1')
MIGRATIONS = ROOT / 'infra/oracle/migrations'
SETTINGS = '''SET DEFINE OFF
SET ECHO OFF
SET VERIFY OFF
SET FEEDBACK OFF
SET HEADING OFF
SET PAGESIZE 0
SET LINESIZE 32767
SET LONG 10000000
SET LONGCHUNKSIZE 32767
SET TRIMSPOOL ON
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE
'''


def sql(script, account=None):
    login = (f'CONNECT {account[0]}/"{account[1]}"@//localhost:1521/FREEPDB1\n'
             if account else 'CONNECT / AS SYSDBA\nALTER SESSION SET CONTAINER=FREEPDB1;\n')
    result = subprocess.run(
        ['docker', 'exec', '-i', CONTAINER, 'sqlplus', '-s', '/nolog'],
        input=SETTINGS + login + script + '\nEXIT\n', text=True, capture_output=True)
    errors = re.findall(r'^(?:ORA-|SP2-|PLS-).*', result.stdout + result.stderr, re.M)
    if result.returncode or errors:
        # Do not print SQL*Plus input: it contains temporary account passwords.
        raise RuntimeError('Oracle SQL failed: ' + '; '.join(errors))
    return result.stdout


def load(account, path):
    sql(path.read_text(), account)


def xml_rows(account, query):
    output = sql("SELECT DBMS_XMLGEN.GETXML('" + query.replace("'", "''") + "') FROM dual;", account)
    if '<ROWSET' not in output:
        return []
    start = output.index('<ROWSET')
    end = output.index('</ROWSET>') + len('</ROWSET>') if '</ROWSET>' in output else output.index('/>', start) + 2
    payload = output[start:end]
    try:
        rows = ET.fromstring(payload)
    except ET.ParseError:
        Path('/private/tmp/bintime-ddl-xml-debug.txt').write_text(payload)
        raise
    return [{field.tag: field.text or '' for field in row} for row in rows]


def canonical(value):
    # Compare SQL formatting outside string literals; preserve literal values.
    parts = re.split(r"('(?:[^']|'')*')", value or '')
    return ''.join(part if i % 2 else re.sub(r'\s+', '', part).upper()
                   for i, part in enumerate(parts))


def snapshot(account):
    tables = xml_rows(account, "SELECT table_name FROM user_tables WHERE table_name NOT LIKE 'BIN$%' ORDER BY table_name")
    columns = xml_rows(account, '''SELECT table_name,column_name,data_type,data_length,
        data_precision,data_scale,char_used,char_length,nullable,data_default
        FROM user_tab_columns ORDER BY table_name,column_name''')
    for row in columns:
        row['DATA_DEFAULT'] = canonical(row.get('DATA_DEFAULT'))
    constraints = xml_rows(account, '''SELECT table_name,constraint_name,constraint_type,
        search_condition_vc AS search_condition,r_constraint_name,delete_rule,status,deferrable,deferred,validated,generated
        FROM user_constraints WHERE table_name NOT LIKE 'BIN$%' ORDER BY table_name,constraint_name''')
    cons_cols = xml_rows(account, '''SELECT constraint_name,column_name,position
        FROM user_cons_columns ORDER BY constraint_name,position''')
    by_name = {row['CONSTRAINT_NAME']: row for row in constraints}
    def key_columns(name):
        return [r['COLUMN_NAME'] for r in cons_cols if r['CONSTRAINT_NAME'] == name]
    for row in constraints:
        name = row.pop('CONSTRAINT_NAME')
        row['COLUMNS'] = key_columns(name)
        if row['CONSTRAINT_TYPE'] == 'C':
            row['COLUMNS'].sort()
        row['SEARCH_CONDITION'] = canonical(row.get('SEARCH_CONDITION'))
        reference = row.pop('R_CONSTRAINT_NAME', None)
        if reference:
            row['REFERENCES'] = [by_name[reference]['TABLE_NAME'], key_columns(reference)]
        if row.pop('GENERATED') == 'USER NAME':
            row['NAME'] = name
    indexes = xml_rows(account, '''SELECT table_name,index_name,index_type,uniqueness,status
        FROM user_indexes WHERE table_name NOT LIKE 'BIN$%' ORDER BY table_name,index_name''')
    idx_cols = xml_rows(account, '''SELECT index_name,column_name,column_position,descend
        FROM user_ind_columns ORDER BY index_name,column_position''')
    expressions = xml_rows(account, '''SELECT index_name,column_position,column_expression
        FROM user_ind_expressions ORDER BY index_name,column_position''')
    for row in indexes:
        name = row.pop('INDEX_NAME')
        row['COLUMNS'] = []
        for col in idx_cols:
            if col['INDEX_NAME'] != name:
                continue
            expression = next((r['COLUMN_EXPRESSION'] for r in expressions
                               if r['INDEX_NAME'] == name and r['COLUMN_POSITION'] == col['COLUMN_POSITION']), None)
            row['COLUMNS'].append([canonical(expression) if expression else col['COLUMN_NAME'], col['DESCEND']])
        if not name.startswith('SYS_'):
            row['NAME'] = name
    locks = xml_rows(account, 'SELECT bucket_id FROM search_user_lock ORDER BY bucket_id')
    return {k: sorted(v, key=lambda r: json.dumps(r, sort_keys=True)) for k, v in
            dict(tables=tables, columns=columns, constraints=constraints, indexes=indexes, locks=locks).items()}


def compare(expected, actual, label):
    differences = {}
    for category in expected:
        left = {json.dumps(r, sort_keys=True, ensure_ascii=False) for r in expected[category]}
        right = {json.dumps(r, sort_keys=True, ensure_ascii=False) for r in actual[category]}
        if left != right:
            differences[category] = {'missing': sorted(left-right), 'extra': sorted(right-left)}
    print(label + ': ' + ('MATCH' if not differences else json.dumps(differences, ensure_ascii=False, indent=2)), flush=True)
    return differences


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--legacy-ddl', type=Path, help='Original pre-store DDL; defaults to git revision 23e7d84')
    parser.add_argument('--report', type=Path, default=Path('/private/tmp/bintime-unified-ddl-verification.json'))
    parser.add_argument('--run-store-tests', action='store_true')
    args = parser.parse_args()
    legacy = args.legacy_ddl.read_text() if args.legacy_ddl else subprocess.check_output(
        ['git', 'show', '23e7d84:infra/oracle/local-setup/01ddl.sql'], cwd=ROOT, text=True)
    migration_names = {
        '001-user-profile.sql', '002-notification-subscriptions.sql', '003-notification-delivery.sql',
        '02-store-api.sql', '03-user-vector-generation.sql', '04-search-v2-prepare.sql',
        '05-search-v2-finalize.sql', '06-store-search-catalog.sql', '07-store-event-delivery.sql', '004-chatting.sql',
        '08-chat-booking-proposals.sql', '009-notification-push-history.sql'}
    assert {p.name for p in MIGRATIONS.glob('*.sql')} == migration_names, 'Update verification for added/removed migrations'
    assert (ROOT / 'docs/sql/search-oracle.sql').read_bytes() == (ROOT / 'docs/sql/search-oracle-v2.sql').read_bytes(), 'Search v2 aliases differ'
    sources = [ROOT / 'infra/oracle/ddl.sql', ROOT / 'infra/oracle/local-setup/01ddl.sql',
               *sorted((ROOT / 'docs/sql').glob('*.sql')), *sorted(MIGRATIONS.glob('*.sql'))]
    source_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    accounts = []
    try:
        for _ in range(3):
            account = ('BT_DDL_' + secrets.token_hex(6).upper(), 'BtDdl' + secrets.token_hex(16))
            sql(f'CREATE USER {account[0]} IDENTIFIED BY "{account[1]}" DEFAULT TABLESPACE USERS QUOTA 200M ON USERS;\n'
                f'GRANT CREATE SESSION,CREATE TABLE TO {account[0]};')
            accounts.append(account)
        historical, split, unified = accounts
        sql(legacy, historical)
        load(historical, MIGRATIONS / '02-store-api.sql')
        # The v1 snapshot already includes 03; remove just that CREATE so 03 itself is exercised.
        v1 = (ROOT / 'docs/sql/search-oracle-v1.sql').read_text()
        v1, count = re.subn(r'create table search_vector_generation\([^;]+;', '', v1, flags=re.I)
        assert count == 1
        sql(v1 + '\nCOMMIT;', historical)
        for name in ['001-user-profile.sql', '03-user-vector-generation.sql',
                     '04-search-v2-prepare.sql', '05-search-v2-finalize.sql',
                     '06-store-search-catalog.sql', '07-store-event-delivery.sql',
                     '002-notification-subscriptions.sql', '003-notification-delivery.sql',
                     '009-notification-push-history.sql', '004-chatting.sql', '08-chat-booking-proposals.sql']:
            load(historical, MIGRATIONS / name)
        load(split, ROOT / 'infra/oracle/local-setup/01ddl.sql')
        load(split, ROOT / 'docs/sql/search-oracle.sql')
        for name in ['001-user-profile.sql', '06-store-search-catalog.sql',
                     '07-store-event-delivery.sql', '002-notification-subscriptions.sql',
                     '003-notification-delivery.sql', '009-notification-push-history.sql', '004-chatting.sql', '08-chat-booking-proposals.sql']:
            load(split, MIGRATIONS / name)
        load(unified, ROOT / 'infra/oracle/ddl.sql')
        load(unified, MIGRATIONS / '004-chatting.sql')
        load(unified, MIGRATIONS / '08-chat-booking-proposals.sql')
        snapshots = {key: snapshot(account) for key, account in zip(['historical', 'split', 'unified'], accounts)}
        # user_tab_columns includes physical byte sizes and BYTE/CHAR semantics, not column order.
        differences = compare(snapshots['historical'], snapshots['unified'], 'All migrations -> unified')
        split_diff = compare(snapshots['split'], snapshots['unified'], 'Current split install -> unified')
        # Known source drift: 02 creates this index; latest 01ddl omits it. Unified preserves it.
        expected_extra = {'indexes': {'missing': [], 'extra': [json.dumps({
            'TABLE_NAME': 'STORE_EVENTS', 'INDEX_TYPE': 'FUNCTION-BASED NORMAL', 'UNIQUENESS': 'NONUNIQUE',
            'STATUS': 'VALID', 'COLUMNS': [['STATUS_CODE', 'ASC'], ['SYS_EXTRACT_UTC("CREATED_AT")', 'ASC']],
            'NAME': 'IX_STORE_EVENT_QUEUE'}, sort_keys=True, ensure_ascii=False)]}}
        assert not differences, 'Unified schema differs from full migration chain'
        assert split_diff == expected_extra, 'Unexpected difference from current split installation'
        assert len(snapshots['unified']['tables']) == 29
        assert {int(row['BUCKET_ID']) for row in snapshots['unified']['locks']} == set(range(256))
        invalid = sql("SELECT COUNT(*) FROM user_objects WHERE status <> 'VALID';", unified).strip()
        assert invalid == '0', 'Invalid schema objects: ' + invalid
        # DDL must not contain business seed rows. Only lock buckets are initialized.
        for row in snapshots['unified']['tables']:
            name = row['TABLE_NAME']
            if name != 'SEARCH_USER_LOCK':
                assert sql(f'SELECT COUNT(*) FROM {name};', unified).strip() == '0', name
        args.report.write_text(json.dumps({'sources_sha256': source_hashes, 'snapshots': snapshots, 'historical_difference': differences,
                                          'split_difference': split_diff}, ensure_ascii=False, indent=2) + '\n')
        print('29 tables, 256 exact lock buckets, valid objects, no business seed rows. Report: ' + str(args.report), flush=True)
        if args.run_store_tests:
            # Existing StoreApiTests permits only its dedicated schema prefix.
            name = 'BT_STORE_' + secrets.token_hex(6).upper()
            account = (name, 'BtDdl' + secrets.token_hex(16))
            sql(f'CREATE USER {name} IDENTIFIED BY "{account[1]}" DEFAULT TABLESPACE USERS QUOTA 200M ON USERS;\n'
                f'GRANT CREATE SESSION,CREATE TABLE TO {name};')
            accounts.append(account)
            load(account, ROOT / 'infra/oracle/ddl.sql')
            env = dict(os.environ, STORE_TEST_DB_URL='jdbc:oracle:thin:@//localhost:' + os.environ.get('STORE_ORACLE_PORT', '1521') + '/FREEPDB1',
                       STORE_TEST_DB_USER=name, STORE_TEST_DB_PASSWORD=account[1])
            subprocess.run(['./gradlew', 'test', '--tests', 'com.bintime.store.StoreApiTests',
                            '--rerun-tasks', '--console=plain'], cwd=ROOT, env=env, check=True)
        print('Unified DDL verification passed.', flush=True)
    finally:
        # Only accounts created by this process, with generated names, can be removed.
        for name, _ in reversed(accounts):
            assert re.fullmatch(r'BT_(?:DDL|STORE)_[A-F0-9]{12}', name)
            sql(f'DROP USER {name} CASCADE;')


if __name__ == '__main__':
    main()
