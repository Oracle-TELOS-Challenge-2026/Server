-- Apply once to an existing schema which already has search_vector/search_work.
-- New schemas: this table is included in docs/sql/search-oracle.sql.
CREATE TABLE search_vector_generation (
 user_id VARCHAR2(64) PRIMARY KEY,
 lease_owner VARCHAR2(64) NOT NULL,
 lease_until NUMBER(19) NOT NULL
);
