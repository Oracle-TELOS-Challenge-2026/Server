#!/bin/bash
set -euo pipefail

app_username="${DB_USERNAME:?DB_USERNAME is required}"
app_password="${DB_PASSWORD:?DB_PASSWORD is required}"

# SQL에 삽입하는 식별자를 제한하고 비밀번호의 문자열 경계를 보존한다.
if [[ ! "$app_username" =~ ^[A-Z][A-Z0-9_]{0,29}$ ]]; then
    echo 'DB_USERNAME must be an uppercase Oracle identifier (1-30 characters).' >&2
    exit 1
fi
if [[ "$app_password" == *'"'* || "$app_password" == *$'\n'* || "$app_password" == *$'\r'* ]]; then
    echo 'DB_PASSWORD must not contain double quotes or line breaks.' >&2
    exit 1
fi
sql_password="${app_password//\'/\'\'}"

# Lite 이미지는 USERS가 없으며 startup 스크립트는 재시작 때도 실행된다.
# 기존 계정의 비밀번호는 변경하지 않는다. .env 변경만으로 기존 계정을 회전시키지 않는다.
sqlplus -s / as sysdba <<SQL
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET DEFINE OFF
SET VERIFY OFF

ALTER SESSION SET CONTAINER = FREEPDB1;

DECLARE
    existing_count PLS_INTEGER;
BEGIN
    SELECT COUNT(*) INTO existing_count
    FROM DBA_TABLESPACES WHERE TABLESPACE_NAME = 'USERS';

    IF existing_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLESPACE USERS '
            || 'DATAFILE ''/opt/oracle/oradata/FREE/FREEPDB1/users01.dbf'' '
            || 'SIZE 100M AUTOEXTEND ON NEXT 10M MAXSIZE 1G';
    END IF;

    SELECT COUNT(*) INTO existing_count
    FROM DBA_USERS WHERE USERNAME = '${app_username}';

    IF existing_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE USER ${app_username} '
            || 'IDENTIFIED BY "${sql_password}" '
            || 'DEFAULT TABLESPACE USERS QUOTA 1G ON USERS';
    END IF;
END;
/

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO ${app_username};
EXIT
SQL
