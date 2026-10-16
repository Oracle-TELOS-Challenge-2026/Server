-- Run as the application schema after the store API schema (02, if needed).
-- Existing data and the PATCH version_no are preserved. Safe to rerun.
-- Oracle DDL commits implicitly. Stop all application writers before applying.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM all_tab_columns
  WHERE owner=SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
    AND table_name='STORE_SHOPS' AND column_name='SEARCH_VERSION';
  IF n=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE store_shops ADD (search_version NUMBER(19,0) DEFAULT 1 NOT NULL)';
  END IF;
  SELECT COUNT(*) INTO n FROM all_constraints
  WHERE owner=SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
    AND table_name='STORE_SHOPS' AND constraint_name='CK_SHOP_SEARCH_VERSION';
  IF n=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE store_shops ADD CONSTRAINT ck_shop_search_version CHECK(search_version>=1)';
  END IF;
END;
/
