#!/usr/bin/env python3
"""Local Oracle verification, in newly created test schemas only; never uses DB_USERNAME.
Requires a running local Oracle container and its OS-authenticated SYSDBA account.
The unique schemas remain for inspection; their names are printed, credentials are not.
"""
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
CONTAINER = os.environ.get('STORE_ORACLE_CONTAINER', 'bintime-dev-oracle-1')
PORT = os.environ.get('STORE_ORACLE_PORT', '1521')


def sql(script):
    result = subprocess.run(
        ['docker', 'exec', '-i', CONTAINER, 'sqlplus', '-s', '/', 'as', 'sysdba'],
        input='SET DEFINE OFF\nSET ECHO OFF\nSET VERIFY OFF\nWHENEVER SQLERROR EXIT SQL.SQLCODE\nALTER SESSION SET CONTAINER=FREEPDB1;\n' + script + '\nEXIT\n',
        text=True, capture_output=True, check=False)
    if result.returncode:
        # Never print connection commands or generated passwords.
        errors = [line for line in result.stdout.splitlines() if line.startswith(('ORA-', 'SP2-', 'PLS-'))]
        raise RuntimeError('Oracle verification failed: ' + '; '.join(errors))


def create_schema():
    name = 'BT_STORE_' + secrets.token_hex(6).upper()
    password = 'BtStore' + secrets.token_hex(16)
    sql(f'CREATE USER {name} IDENTIFIED BY "{password}" DEFAULT TABLESPACE USERS QUOTA 200M ON USERS;\nGRANT CREATE SESSION,CREATE TABLE TO {name};')
    return name, password


def load(name, script):
    # CREATE TABLE and unqualified references resolve in this isolated test schema.
    sql(f'ALTER SESSION SET CURRENT_SCHEMA={name};\n' + script)


def tests(name, password):
    env = dict(os.environ, STORE_TEST_DB_URL=f'jdbc:oracle:thin:@//localhost:{PORT}/FREEPDB1',
               STORE_TEST_DB_USER=name, STORE_TEST_DB_PASSWORD=password)
    subprocess.run(['./gradlew', 'test', '--tests', 'com.bintime.store.StoreApiTests', '--rerun-tasks', '--console=plain'], cwd=ROOT, env=env, check=True)


def main():
    baseline = subprocess.check_output(['git', 'show', 'HEAD:infra/oracle/local-setup/01ddl.sql'], cwd=ROOT, text=True)
    # The baseline must precede this implementation; otherwise use an explicit original SQL file.
    if 'store_idempotency' in baseline:
        if not os.environ.get('STORE_BASELINE_SQL'):
            raise RuntimeError('Set STORE_BASELINE_SQL to the original pre-store API SQL file')
        baseline = Path(os.environ['STORE_BASELINE_SQL']).read_text()
    migrated, password = create_schema()
    load(migrated, baseline)
    load(migrated, '''
INSERT INTO user_accounts(user_id,display_name,role_code) VALUES('legacy-owner','기존 사장님','OWNER');
INSERT INTO user_accounts(user_id,display_name,role_code) VALUES('legacy-user','기존 고객','CUSTOMER');
INSERT INTO store_shops(store_id,owner_user_id,name,category_code,address,latitude,longitude) VALUES('legacy-shop','legacy-owner','기존 매장','NAIL','서울',37.5,127);
INSERT INTO store_services(service_id,store_id,name,duration_minutes,base_price) VALUES('legacy-service','legacy-shop','기존 시술',60,50000);
INSERT INTO store_slots(slot_id,store_id,service_id,start_at,end_at,available_from,available_until,original_price,payable_price,status_code) VALUES('legacy-slot','legacy-shop','legacy-service',SYSTIMESTAMP+INTERVAL '1' DAY,SYSTIMESTAMP+INTERVAL '1' DAY+INTERVAL '1' HOUR,SYSTIMESTAMP+INTERVAL '1' DAY,SYSTIMESTAMP+INTERVAL '1' DAY+INTERVAL '1' HOUR,50000,50000,'CLOSED');
INSERT INTO store_reservations(reservation_id,user_id,store_id,slot_id,service_name,start_at,end_at,original_price,payable_price,status_code) SELECT 'legacy-reservation','legacy-user',store_id,slot_id,'예약 시술명',start_at,end_at,original_price,payable_price,'CANCELED' FROM store_slots WHERE slot_id='legacy-slot';
INSERT INTO store_slots(slot_id,store_id,service_id,start_at,end_at,available_from,available_until,original_price,payable_price,status_code,source_type,source_reservation_id,publication_version) SELECT 'legacy-vacancy',store_id,service_id,start_at,end_at,available_from,available_until,50000,35000,'AVAILABLE','CANCELLATION','legacy-reservation',1 FROM store_slots WHERE slot_id='legacy-slot';
COMMIT;
''')
    load(migrated, (ROOT / 'infra/oracle/migrations/02-store-api.sql').read_text())
    load(migrated, '''
DECLARE n NUMBER;
BEGIN
 SELECT COUNT(*) INTO n FROM store_reservations WHERE reservation_id='legacy-reservation' AND service_id='legacy-service' AND service_name='예약 시술명' AND duration_minutes=60 AND customer_name='기존 고객' AND payable_price=50000;
 IF n<>1 THEN RAISE_APPLICATION_ERROR(-20001,'Legacy reservation snapshot not preserved'); END IF;
 SELECT COUNT(*) INTO n FROM store_slots WHERE slot_id='legacy-vacancy' AND original_service_id='legacy-service' AND vacancy_original_price=50000 AND payable_price=35000 AND publication_version=1;
 IF n<>1 THEN RAISE_APPLICATION_ERROR(-20002,'Legacy vacancy not preserved'); END IF;
END;
/
''')
    print('Migration and legacy snapshot preservation passed in ' + migrated, flush=True)
    load(migrated, (ROOT / "docs/sql/search-oracle.sql").read_text())
    tests(migrated, password)
    fresh, password = create_schema()
    load(fresh, (ROOT / 'infra/oracle/local-setup/01ddl.sql').read_text())
    print('Fresh schema created: ' + fresh, flush=True)
    load(fresh, (ROOT / "docs/sql/search-oracle.sql").read_text())
    tests(fresh, password)
    print('Fresh and migrated Oracle schemas: validation, API flow and contention tests passed.')


if __name__ == '__main__':
    main()
