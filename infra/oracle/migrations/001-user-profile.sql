-- Run as BINTIME_APP once the original user_accounts table exists.
-- Existing users and login defaults are preserved. Re-running this migration is safe.
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  column_count NUMBER;
  PROCEDURE add_column(column_name_in VARCHAR2, definition VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO column_count
    FROM user_tab_columns
    WHERE table_name = 'USER_ACCOUNTS' AND column_name = column_name_in;
    IF column_count = 0 THEN
      EXECUTE IMMEDIATE 'ALTER TABLE user_accounts ADD (' || definition || ')';
    END IF;
  END;
BEGIN
  add_column('VERSION_NO', 'version_no NUMBER(19,0) DEFAULT 1 NOT NULL');
  add_column('ACCURACY_METERS', 'accuracy_meters NUMBER(10,0)');
  add_column('LOCATION_SOURCE', 'location_source VARCHAR2(6 BYTE)');
  add_column('LOCATION_SAVED_AT', 'location_saved_at TIMESTAMP(6) WITH TIME ZONE');
END;
/

-- Previously stored coordinates have no device metadata; keep them as manual locations.
UPDATE user_accounts
SET location_source = COALESCE(location_source, 'MANUAL'),
    location_saved_at = COALESCE(location_saved_at, location_at)
WHERE location_at IS NOT NULL
  AND (location_source IS NULL OR location_saved_at IS NULL);
COMMIT;

DECLARE
  constraint_count NUMBER;
  PROCEDURE add_check(constraint_name_in VARCHAR2, check_expression VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO constraint_count
    FROM user_constraints
    WHERE table_name = 'USER_ACCOUNTS' AND constraint_name = constraint_name_in;
    IF constraint_count = 0 THEN
      EXECUTE IMMEDIATE 'ALTER TABLE user_accounts ADD CONSTRAINT ' || constraint_name_in
          || ' CHECK (' || check_expression || ')';
    END IF;
  END;
BEGIN
  add_check('CK_USER_VERSION', 'version_no >= 1');
  add_check('CK_USER_ACCURACY', 'accuracy_meters IS NULL OR accuracy_meters >= 0');
  add_check('CK_USER_LOCATION_DETAILS',
      '(latitude IS NULL AND longitude IS NULL AND location_at IS NULL '
      || 'AND accuracy_meters IS NULL AND location_source IS NULL AND location_saved_at IS NULL) '
      || 'OR (latitude IS NOT NULL AND longitude IS NOT NULL AND location_at IS NOT NULL '
      || 'AND location_source IS NOT NULL AND location_source IN (''DEVICE'', ''MANUAL'') '
      || 'AND location_saved_at IS NOT NULL)');
END;
/
