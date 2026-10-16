-- Isolated local QA schema only. Missing QA identities are inserted; existing accounts are not updated.
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE
MERGE INTO user_accounts target
USING (
 SELECT 'local_qa_store_owner' user_id, 'QA 사장님' display_name, 'OWNER' role_code FROM dual
 UNION ALL SELECT 'local_qa_store_other_owner', 'QA 다른 사장님', 'OWNER' FROM dual
 UNION ALL SELECT 'local_qa_store_customer_a', 'QA 고객 A', 'CUSTOMER' FROM dual
 UNION ALL SELECT 'local_qa_store_customer_b', 'QA 고객 B', 'CUSTOMER' FROM dual
) source ON (target.user_id=source.user_id)
WHEN NOT MATCHED THEN INSERT(user_id,display_name,role_code)
 VALUES(source.user_id,source.display_name,source.role_code);
COMMIT;
