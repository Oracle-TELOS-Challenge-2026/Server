-- Run only after 04-search-v2-prepare.sql, with all writers stopped and v2 code ready.
-- Backup first. Oracle DDL commits implicitly; never run on a partial migration blindly.
-- Legacy SEARCH_WAITLISTS belongs to the existing reservation flow and is retained.
ALTER TABLE search_user_lock ADD CONSTRAINT search_lock_range CHECK (bucket_id BETWEEN 0 AND 255);
ALTER TABLE search_intent ADD CONSTRAINT search_intent_status CHECK (
 status='ACTIVE' OR status='CANCELED' OR status='EXPIRED' OR status='FULFILLED');
ALTER TABLE search_intent ADD CONSTRAINT search_intent_version CHECK (
 resource_version>=1 AND consent_version>=1);
ALTER TABLE search_work ADD CONSTRAINT search_work_status CHECK (
 status='QUEUED' OR status='RUNNING' OR status='SUCCEEDED' OR status='FAILED');
ALTER TABLE search_work ADD CONSTRAINT search_work_attempts CHECK (attempts>=0);
ALTER TABLE search_work ADD CONSTRAINT search_work_publication CHECK (
 publication_version IS NULL OR publication_version>=1);

-- Deliberately no PURGE: local Oracle recycle bin can provide an additional recovery path.
DROP TABLE search_consent;
DROP TABLE search_vector;
DROP TABLE search_preference_revision;
DROP TABLE search_vector_generation;
DROP TABLE search_match_job;
DROP TABLE search_store_projection;
