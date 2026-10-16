-- Search v2: NEW EMPTY schema only. Existing v1 schemas use migration 04 and 05.
-- Plain Oracle SQL for IDE/JDBC. Run as the application schema, never PUBLIC.
-- Times are epoch milliseconds. COMMIT is explicit for lock seed rows.

-- 1. Serialize per-user changes, including the very first waitlist registration.
CREATE TABLE search_user_lock (
 bucket_id NUMBER(10) PRIMARY KEY,
 CONSTRAINT search_lock_range CHECK (bucket_id BETWEEN 0 AND 255)
);

-- 2. Only the current conditions and their consent are retained.
CREATE TABLE search_intent (
 id VARCHAR2(64) PRIMARY KEY,
 user_id VARCHAR2(64) NOT NULL,
 source VARCHAR2(32) NOT NULL,
 conditions_json CLOB NOT NULL,
 status VARCHAR2(16) NOT NULL,
 resource_version NUMBER(19) NOT NULL,
 created_at NUMBER(19) NOT NULL,
 consent_at NUMBER(19) NOT NULL,
 consent_version NUMBER(19) NOT NULL,
 window_end NUMBER(19) NOT NULL,
 reservation_id VARCHAR2(64),
 CONSTRAINT search_intent_status CHECK (status='ACTIVE' OR status='CANCELED' OR status='EXPIRED' OR status='FULFILLED'),
 CONSTRAINT search_intent_version CHECK (resource_version >= 1 AND consent_version >= 1),
 CONSTRAINT search_intent_consent CHECK (
   status <> 'ACTIVE' OR (consent_at > 0 AND consent_version = resource_version))
);
CREATE INDEX search_intent_user ON search_intent(user_id,status);
CREATE INDEX search_intent_expiry ON search_intent(status,window_end);

-- 3. Original HTTP response replay is distinct from background task deduplication.
CREATE TABLE search_idempotency (
 user_id VARCHAR2(64) NOT NULL,
 operation VARCHAR2(16) NOT NULL,
 idem_key VARCHAR2(128) NOT NULL,
 request_hash VARCHAR2(64) NOT NULL,
 response_json CLOB NOT NULL,
 expires_at NUMBER(19) NOT NULL,
 PRIMARY KEY(user_id,operation,idem_key)
);

-- 4. Durable outbox/worker queue, including S-08 event identity and public job ID.
-- SLOT rows must be retained while event deduplication is required.
-- payload for INDEX/STORE_VECTOR must carry a committed source snapshot or
-- a reference that can retrieve the current store document (no Oracle document mirror).
CREATE TABLE search_work (
 id VARCHAR2(64) PRIMARY KEY,
 kind VARCHAR2(16) NOT NULL,
 payload CLOB NOT NULL,
 status VARCHAR2(16) NOT NULL,
 attempts NUMBER(10) NOT NULL,
 available_at NUMBER(19) NOT NULL,
 lease_owner VARCHAR2(64),
 last_error VARCHAR2(256),
 event_id VARCHAR2(64),
 job_id VARCHAR2(64),
 slot_id VARCHAR2(64),
 publication_version NUMBER(19),
 CONSTRAINT search_work_event_uq UNIQUE(event_id),
 CONSTRAINT search_work_job_uq UNIQUE(job_id),
 CONSTRAINT search_work_status CHECK (status='QUEUED' OR status='RUNNING' OR status='SUCCEEDED' OR status='FAILED'),
 CONSTRAINT search_work_attempts CHECK (attempts >= 0),
 CONSTRAINT search_work_publication CHECK (publication_version IS NULL OR publication_version >= 1),
 CONSTRAINT search_work_slot CHECK (
   (kind = 'SLOT' AND event_id IS NOT NULL AND job_id IS NOT NULL AND slot_id IS NOT NULL)
   OR (kind <> 'SLOT' AND event_id IS NULL AND job_id IS NULL
       AND slot_id IS NULL AND publication_version IS NULL))
);
CREATE INDEX search_work_ready ON search_work(status,available_at);

-- 5. A proposal is neither a slot hold nor a confirmed reservation.
CREATE TABLE search_offer (
 intent_id VARCHAR2(64) NOT NULL,
 intent_version NUMBER(19) NOT NULL,
 slot_id VARCHAR2(64) NOT NULL,
 publication_version NUMBER(19),
 PRIMARY KEY(intent_id,intent_version,slot_id)
);

-- 6. Keep this row even when preferences are empty: revision and lease survive.
-- revision = current preference revision; source_version = cached vector revision.
-- On empty input, clear cache columns as a group; do not delete the state row.
CREATE TABLE search_user_vector (
 user_id VARCHAR2(64) PRIMARY KEY,
 revision NUMBER(19) DEFAULT 0 NOT NULL,
 source_version NUMBER(19),
 input_hash VARCHAR2(64),
 model VARCHAR2(64),
 dimensions NUMBER(10),
 vector_json CLOB,
 lease_owner VARCHAR2(64),
 lease_until NUMBER(19),
 CONSTRAINT search_uv_revision CHECK (revision >= 0),
 CONSTRAINT search_uv_lease CHECK (
   (lease_owner IS NULL AND lease_until IS NULL)
   OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
 CONSTRAINT search_uv_cache CHECK (
   (source_version IS NULL AND input_hash IS NULL AND model IS NULL
    AND dimensions IS NULL AND vector_json IS NULL)
   OR (source_version IS NOT NULL AND source_version >= 0 AND input_hash IS NOT NULL
       AND model IS NOT NULL AND dimensions IS NOT NULL
       AND dimensions = 1536 AND vector_json IS NOT NULL))
);

-- 7. Cache the embedding only. Store owns shop/service/slot source data;
-- Elasticsearch owns the derived search document.
CREATE TABLE search_store_vector (
 store_id VARCHAR2(64) PRIMARY KEY,
 source_version NUMBER(19) NOT NULL,
 input_hash VARCHAR2(64) NOT NULL,
 model VARCHAR2(64) NOT NULL,
 dimensions NUMBER(10) NOT NULL,
 vector_json CLOB NOT NULL,
 CONSTRAINT search_sv_version CHECK (source_version >= 0),
 CONSTRAINT search_sv_dimensions CHECK (dimensions = 1536)
);

INSERT INTO search_user_lock(bucket_id) VALUES(0);
INSERT INTO search_user_lock(bucket_id) VALUES(1);
INSERT INTO search_user_lock(bucket_id) VALUES(2);
INSERT INTO search_user_lock(bucket_id) VALUES(3);
INSERT INTO search_user_lock(bucket_id) VALUES(4);
INSERT INTO search_user_lock(bucket_id) VALUES(5);
INSERT INTO search_user_lock(bucket_id) VALUES(6);
INSERT INTO search_user_lock(bucket_id) VALUES(7);
INSERT INTO search_user_lock(bucket_id) VALUES(8);
INSERT INTO search_user_lock(bucket_id) VALUES(9);
INSERT INTO search_user_lock(bucket_id) VALUES(10);
INSERT INTO search_user_lock(bucket_id) VALUES(11);
INSERT INTO search_user_lock(bucket_id) VALUES(12);
INSERT INTO search_user_lock(bucket_id) VALUES(13);
INSERT INTO search_user_lock(bucket_id) VALUES(14);
INSERT INTO search_user_lock(bucket_id) VALUES(15);
INSERT INTO search_user_lock(bucket_id) VALUES(16);
INSERT INTO search_user_lock(bucket_id) VALUES(17);
INSERT INTO search_user_lock(bucket_id) VALUES(18);
INSERT INTO search_user_lock(bucket_id) VALUES(19);
INSERT INTO search_user_lock(bucket_id) VALUES(20);
INSERT INTO search_user_lock(bucket_id) VALUES(21);
INSERT INTO search_user_lock(bucket_id) VALUES(22);
INSERT INTO search_user_lock(bucket_id) VALUES(23);
INSERT INTO search_user_lock(bucket_id) VALUES(24);
INSERT INTO search_user_lock(bucket_id) VALUES(25);
INSERT INTO search_user_lock(bucket_id) VALUES(26);
INSERT INTO search_user_lock(bucket_id) VALUES(27);
INSERT INTO search_user_lock(bucket_id) VALUES(28);
INSERT INTO search_user_lock(bucket_id) VALUES(29);
INSERT INTO search_user_lock(bucket_id) VALUES(30);
INSERT INTO search_user_lock(bucket_id) VALUES(31);
INSERT INTO search_user_lock(bucket_id) VALUES(32);
INSERT INTO search_user_lock(bucket_id) VALUES(33);
INSERT INTO search_user_lock(bucket_id) VALUES(34);
INSERT INTO search_user_lock(bucket_id) VALUES(35);
INSERT INTO search_user_lock(bucket_id) VALUES(36);
INSERT INTO search_user_lock(bucket_id) VALUES(37);
INSERT INTO search_user_lock(bucket_id) VALUES(38);
INSERT INTO search_user_lock(bucket_id) VALUES(39);
INSERT INTO search_user_lock(bucket_id) VALUES(40);
INSERT INTO search_user_lock(bucket_id) VALUES(41);
INSERT INTO search_user_lock(bucket_id) VALUES(42);
INSERT INTO search_user_lock(bucket_id) VALUES(43);
INSERT INTO search_user_lock(bucket_id) VALUES(44);
INSERT INTO search_user_lock(bucket_id) VALUES(45);
INSERT INTO search_user_lock(bucket_id) VALUES(46);
INSERT INTO search_user_lock(bucket_id) VALUES(47);
INSERT INTO search_user_lock(bucket_id) VALUES(48);
INSERT INTO search_user_lock(bucket_id) VALUES(49);
INSERT INTO search_user_lock(bucket_id) VALUES(50);
INSERT INTO search_user_lock(bucket_id) VALUES(51);
INSERT INTO search_user_lock(bucket_id) VALUES(52);
INSERT INTO search_user_lock(bucket_id) VALUES(53);
INSERT INTO search_user_lock(bucket_id) VALUES(54);
INSERT INTO search_user_lock(bucket_id) VALUES(55);
INSERT INTO search_user_lock(bucket_id) VALUES(56);
INSERT INTO search_user_lock(bucket_id) VALUES(57);
INSERT INTO search_user_lock(bucket_id) VALUES(58);
INSERT INTO search_user_lock(bucket_id) VALUES(59);
INSERT INTO search_user_lock(bucket_id) VALUES(60);
INSERT INTO search_user_lock(bucket_id) VALUES(61);
INSERT INTO search_user_lock(bucket_id) VALUES(62);
INSERT INTO search_user_lock(bucket_id) VALUES(63);
INSERT INTO search_user_lock(bucket_id) VALUES(64);
INSERT INTO search_user_lock(bucket_id) VALUES(65);
INSERT INTO search_user_lock(bucket_id) VALUES(66);
INSERT INTO search_user_lock(bucket_id) VALUES(67);
INSERT INTO search_user_lock(bucket_id) VALUES(68);
INSERT INTO search_user_lock(bucket_id) VALUES(69);
INSERT INTO search_user_lock(bucket_id) VALUES(70);
INSERT INTO search_user_lock(bucket_id) VALUES(71);
INSERT INTO search_user_lock(bucket_id) VALUES(72);
INSERT INTO search_user_lock(bucket_id) VALUES(73);
INSERT INTO search_user_lock(bucket_id) VALUES(74);
INSERT INTO search_user_lock(bucket_id) VALUES(75);
INSERT INTO search_user_lock(bucket_id) VALUES(76);
INSERT INTO search_user_lock(bucket_id) VALUES(77);
INSERT INTO search_user_lock(bucket_id) VALUES(78);
INSERT INTO search_user_lock(bucket_id) VALUES(79);
INSERT INTO search_user_lock(bucket_id) VALUES(80);
INSERT INTO search_user_lock(bucket_id) VALUES(81);
INSERT INTO search_user_lock(bucket_id) VALUES(82);
INSERT INTO search_user_lock(bucket_id) VALUES(83);
INSERT INTO search_user_lock(bucket_id) VALUES(84);
INSERT INTO search_user_lock(bucket_id) VALUES(85);
INSERT INTO search_user_lock(bucket_id) VALUES(86);
INSERT INTO search_user_lock(bucket_id) VALUES(87);
INSERT INTO search_user_lock(bucket_id) VALUES(88);
INSERT INTO search_user_lock(bucket_id) VALUES(89);
INSERT INTO search_user_lock(bucket_id) VALUES(90);
INSERT INTO search_user_lock(bucket_id) VALUES(91);
INSERT INTO search_user_lock(bucket_id) VALUES(92);
INSERT INTO search_user_lock(bucket_id) VALUES(93);
INSERT INTO search_user_lock(bucket_id) VALUES(94);
INSERT INTO search_user_lock(bucket_id) VALUES(95);
INSERT INTO search_user_lock(bucket_id) VALUES(96);
INSERT INTO search_user_lock(bucket_id) VALUES(97);
INSERT INTO search_user_lock(bucket_id) VALUES(98);
INSERT INTO search_user_lock(bucket_id) VALUES(99);
INSERT INTO search_user_lock(bucket_id) VALUES(100);
INSERT INTO search_user_lock(bucket_id) VALUES(101);
INSERT INTO search_user_lock(bucket_id) VALUES(102);
INSERT INTO search_user_lock(bucket_id) VALUES(103);
INSERT INTO search_user_lock(bucket_id) VALUES(104);
INSERT INTO search_user_lock(bucket_id) VALUES(105);
INSERT INTO search_user_lock(bucket_id) VALUES(106);
INSERT INTO search_user_lock(bucket_id) VALUES(107);
INSERT INTO search_user_lock(bucket_id) VALUES(108);
INSERT INTO search_user_lock(bucket_id) VALUES(109);
INSERT INTO search_user_lock(bucket_id) VALUES(110);
INSERT INTO search_user_lock(bucket_id) VALUES(111);
INSERT INTO search_user_lock(bucket_id) VALUES(112);
INSERT INTO search_user_lock(bucket_id) VALUES(113);
INSERT INTO search_user_lock(bucket_id) VALUES(114);
INSERT INTO search_user_lock(bucket_id) VALUES(115);
INSERT INTO search_user_lock(bucket_id) VALUES(116);
INSERT INTO search_user_lock(bucket_id) VALUES(117);
INSERT INTO search_user_lock(bucket_id) VALUES(118);
INSERT INTO search_user_lock(bucket_id) VALUES(119);
INSERT INTO search_user_lock(bucket_id) VALUES(120);
INSERT INTO search_user_lock(bucket_id) VALUES(121);
INSERT INTO search_user_lock(bucket_id) VALUES(122);
INSERT INTO search_user_lock(bucket_id) VALUES(123);
INSERT INTO search_user_lock(bucket_id) VALUES(124);
INSERT INTO search_user_lock(bucket_id) VALUES(125);
INSERT INTO search_user_lock(bucket_id) VALUES(126);
INSERT INTO search_user_lock(bucket_id) VALUES(127);
INSERT INTO search_user_lock(bucket_id) VALUES(128);
INSERT INTO search_user_lock(bucket_id) VALUES(129);
INSERT INTO search_user_lock(bucket_id) VALUES(130);
INSERT INTO search_user_lock(bucket_id) VALUES(131);
INSERT INTO search_user_lock(bucket_id) VALUES(132);
INSERT INTO search_user_lock(bucket_id) VALUES(133);
INSERT INTO search_user_lock(bucket_id) VALUES(134);
INSERT INTO search_user_lock(bucket_id) VALUES(135);
INSERT INTO search_user_lock(bucket_id) VALUES(136);
INSERT INTO search_user_lock(bucket_id) VALUES(137);
INSERT INTO search_user_lock(bucket_id) VALUES(138);
INSERT INTO search_user_lock(bucket_id) VALUES(139);
INSERT INTO search_user_lock(bucket_id) VALUES(140);
INSERT INTO search_user_lock(bucket_id) VALUES(141);
INSERT INTO search_user_lock(bucket_id) VALUES(142);
INSERT INTO search_user_lock(bucket_id) VALUES(143);
INSERT INTO search_user_lock(bucket_id) VALUES(144);
INSERT INTO search_user_lock(bucket_id) VALUES(145);
INSERT INTO search_user_lock(bucket_id) VALUES(146);
INSERT INTO search_user_lock(bucket_id) VALUES(147);
INSERT INTO search_user_lock(bucket_id) VALUES(148);
INSERT INTO search_user_lock(bucket_id) VALUES(149);
INSERT INTO search_user_lock(bucket_id) VALUES(150);
INSERT INTO search_user_lock(bucket_id) VALUES(151);
INSERT INTO search_user_lock(bucket_id) VALUES(152);
INSERT INTO search_user_lock(bucket_id) VALUES(153);
INSERT INTO search_user_lock(bucket_id) VALUES(154);
INSERT INTO search_user_lock(bucket_id) VALUES(155);
INSERT INTO search_user_lock(bucket_id) VALUES(156);
INSERT INTO search_user_lock(bucket_id) VALUES(157);
INSERT INTO search_user_lock(bucket_id) VALUES(158);
INSERT INTO search_user_lock(bucket_id) VALUES(159);
INSERT INTO search_user_lock(bucket_id) VALUES(160);
INSERT INTO search_user_lock(bucket_id) VALUES(161);
INSERT INTO search_user_lock(bucket_id) VALUES(162);
INSERT INTO search_user_lock(bucket_id) VALUES(163);
INSERT INTO search_user_lock(bucket_id) VALUES(164);
INSERT INTO search_user_lock(bucket_id) VALUES(165);
INSERT INTO search_user_lock(bucket_id) VALUES(166);
INSERT INTO search_user_lock(bucket_id) VALUES(167);
INSERT INTO search_user_lock(bucket_id) VALUES(168);
INSERT INTO search_user_lock(bucket_id) VALUES(169);
INSERT INTO search_user_lock(bucket_id) VALUES(170);
INSERT INTO search_user_lock(bucket_id) VALUES(171);
INSERT INTO search_user_lock(bucket_id) VALUES(172);
INSERT INTO search_user_lock(bucket_id) VALUES(173);
INSERT INTO search_user_lock(bucket_id) VALUES(174);
INSERT INTO search_user_lock(bucket_id) VALUES(175);
INSERT INTO search_user_lock(bucket_id) VALUES(176);
INSERT INTO search_user_lock(bucket_id) VALUES(177);
INSERT INTO search_user_lock(bucket_id) VALUES(178);
INSERT INTO search_user_lock(bucket_id) VALUES(179);
INSERT INTO search_user_lock(bucket_id) VALUES(180);
INSERT INTO search_user_lock(bucket_id) VALUES(181);
INSERT INTO search_user_lock(bucket_id) VALUES(182);
INSERT INTO search_user_lock(bucket_id) VALUES(183);
INSERT INTO search_user_lock(bucket_id) VALUES(184);
INSERT INTO search_user_lock(bucket_id) VALUES(185);
INSERT INTO search_user_lock(bucket_id) VALUES(186);
INSERT INTO search_user_lock(bucket_id) VALUES(187);
INSERT INTO search_user_lock(bucket_id) VALUES(188);
INSERT INTO search_user_lock(bucket_id) VALUES(189);
INSERT INTO search_user_lock(bucket_id) VALUES(190);
INSERT INTO search_user_lock(bucket_id) VALUES(191);
INSERT INTO search_user_lock(bucket_id) VALUES(192);
INSERT INTO search_user_lock(bucket_id) VALUES(193);
INSERT INTO search_user_lock(bucket_id) VALUES(194);
INSERT INTO search_user_lock(bucket_id) VALUES(195);
INSERT INTO search_user_lock(bucket_id) VALUES(196);
INSERT INTO search_user_lock(bucket_id) VALUES(197);
INSERT INTO search_user_lock(bucket_id) VALUES(198);
INSERT INTO search_user_lock(bucket_id) VALUES(199);
INSERT INTO search_user_lock(bucket_id) VALUES(200);
INSERT INTO search_user_lock(bucket_id) VALUES(201);
INSERT INTO search_user_lock(bucket_id) VALUES(202);
INSERT INTO search_user_lock(bucket_id) VALUES(203);
INSERT INTO search_user_lock(bucket_id) VALUES(204);
INSERT INTO search_user_lock(bucket_id) VALUES(205);
INSERT INTO search_user_lock(bucket_id) VALUES(206);
INSERT INTO search_user_lock(bucket_id) VALUES(207);
INSERT INTO search_user_lock(bucket_id) VALUES(208);
INSERT INTO search_user_lock(bucket_id) VALUES(209);
INSERT INTO search_user_lock(bucket_id) VALUES(210);
INSERT INTO search_user_lock(bucket_id) VALUES(211);
INSERT INTO search_user_lock(bucket_id) VALUES(212);
INSERT INTO search_user_lock(bucket_id) VALUES(213);
INSERT INTO search_user_lock(bucket_id) VALUES(214);
INSERT INTO search_user_lock(bucket_id) VALUES(215);
INSERT INTO search_user_lock(bucket_id) VALUES(216);
INSERT INTO search_user_lock(bucket_id) VALUES(217);
INSERT INTO search_user_lock(bucket_id) VALUES(218);
INSERT INTO search_user_lock(bucket_id) VALUES(219);
INSERT INTO search_user_lock(bucket_id) VALUES(220);
INSERT INTO search_user_lock(bucket_id) VALUES(221);
INSERT INTO search_user_lock(bucket_id) VALUES(222);
INSERT INTO search_user_lock(bucket_id) VALUES(223);
INSERT INTO search_user_lock(bucket_id) VALUES(224);
INSERT INTO search_user_lock(bucket_id) VALUES(225);
INSERT INTO search_user_lock(bucket_id) VALUES(226);
INSERT INTO search_user_lock(bucket_id) VALUES(227);
INSERT INTO search_user_lock(bucket_id) VALUES(228);
INSERT INTO search_user_lock(bucket_id) VALUES(229);
INSERT INTO search_user_lock(bucket_id) VALUES(230);
INSERT INTO search_user_lock(bucket_id) VALUES(231);
INSERT INTO search_user_lock(bucket_id) VALUES(232);
INSERT INTO search_user_lock(bucket_id) VALUES(233);
INSERT INTO search_user_lock(bucket_id) VALUES(234);
INSERT INTO search_user_lock(bucket_id) VALUES(235);
INSERT INTO search_user_lock(bucket_id) VALUES(236);
INSERT INTO search_user_lock(bucket_id) VALUES(237);
INSERT INTO search_user_lock(bucket_id) VALUES(238);
INSERT INTO search_user_lock(bucket_id) VALUES(239);
INSERT INTO search_user_lock(bucket_id) VALUES(240);
INSERT INTO search_user_lock(bucket_id) VALUES(241);
INSERT INTO search_user_lock(bucket_id) VALUES(242);
INSERT INTO search_user_lock(bucket_id) VALUES(243);
INSERT INTO search_user_lock(bucket_id) VALUES(244);
INSERT INTO search_user_lock(bucket_id) VALUES(245);
INSERT INTO search_user_lock(bucket_id) VALUES(246);
INSERT INTO search_user_lock(bucket_id) VALUES(247);
INSERT INTO search_user_lock(bucket_id) VALUES(248);
INSERT INTO search_user_lock(bucket_id) VALUES(249);
INSERT INTO search_user_lock(bucket_id) VALUES(250);
INSERT INTO search_user_lock(bucket_id) VALUES(251);
INSERT INTO search_user_lock(bucket_id) VALUES(252);
INSERT INTO search_user_lock(bucket_id) VALUES(253);
INSERT INTO search_user_lock(bucket_id) VALUES(254);
INSERT INTO search_user_lock(bucket_id) VALUES(255);
COMMIT;
