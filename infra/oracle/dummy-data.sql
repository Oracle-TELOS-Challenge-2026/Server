-- BinTime hackathon demo fixture for infra/oracle/ddl.sql.
-- Run once in a freshly created, otherwise EMPTY application schema.
-- All IDs are synthetic demo_* IDs. No real Kakao IDs, FIDs or embeddings are fabricated.
-- Anchor: latitude 37.51335413975466, longitude 126.92649449142719.
-- First four shops are nearby; shops 05/06 are outside the central 1,500m radius.
-- NUMBER(*,6) columns round to six decimals; waitlist JSON preserves the anchor precision.
-- D = tomorrow in Asia/Seoul, captured once; D+1 is the day after tomorrow.
-- One transaction: preflight -> INSERTs -> COMMIT; any failure rolls back this fixture.
-- Stop app writers/workers while loading. Do not combine with pending unrelated DML.
-- SQL*Plus callers: SET DEFINE OFF / WHENEVER SQLERROR EXIT SQL.SQLCODE.
-- See docs/demo-data.md for scenario IDs, expected matches and runtime setup.

DECLARE
  v_now TIMESTAMP(6) WITH TIME ZONE := SYSTIMESTAMP AT TIME ZONE 'Asia/Seoul';
  v_day TIMESTAMP(6) WITH TIME ZONE;
  v_count NUMBER;
  v_conditions CLOB;
  v_created_ms NUMBER;
  v_end_ms NUMBER;

  FUNCTION epoch_ms(p_time TIMESTAMP WITH TIME ZONE) RETURN NUMBER IS
    delta INTERVAL DAY(9) TO SECOND(6);
  BEGIN
    delta := SYS_EXTRACT_UTC(p_time) - TIMESTAMP '1970-01-01 00:00:00';
    RETURN EXTRACT(DAY FROM delta) * 86400000 + EXTRACT(HOUR FROM delta) * 3600000
      + EXTRACT(MINUTE FROM delta) * 60000 + TRUNC(EXTRACT(SECOND FROM delta) * 1000);
  END;

  FUNCTION conditions(p_store VARCHAR2, p_service VARCHAR2,
      p_start TIMESTAMP WITH TIME ZONE, p_end TIMESTAMP WITH TIME ZONE,
      p_price NUMBER, p_lat NUMBER DEFAULT NULL, p_lon NUMBER DEFAULT NULL,
      p_radius NUMBER DEFAULT NULL) RETURN CLOB IS
    location_json VARCHAR2(200);
    result CLOB;
  BEGIN
    IF p_lat IS NULL THEN location_json := 'null';
    ELSE location_json := JSON_OBJECT('latitude' VALUE p_lat, 'longitude' VALUE p_lon); END IF;
    SELECT JSON_OBJECT(
      'storeId' VALUE p_store, 'category' VALUE 'NAIL', 'serviceId' VALUE p_service,
      'windowStart' VALUE TO_CHAR(p_start, 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM'),
      'windowEnd' VALUE TO_CHAR(p_end, 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM'),
      'location' VALUE location_json FORMAT JSON, 'radiusMeters' VALUE p_radius,
      'maxPrice' VALUE p_price, 'keywords' VALUE '["깔끔한 네일"]' FORMAT JSON
      NULL ON NULL RETURNING CLOB) INTO result FROM dual;
    RETURN result;
  END;
BEGIN
  -- TRUNC is applied after conversion to Seoul; session/server timezone cannot move D.
  v_day := FROM_TZ(CAST(TRUNC(CAST(v_now AS TIMESTAMP)) + 1 AS TIMESTAMP), 'Asia/Seoul');

  -- This fixture never resets an existing DB or overwrites previously loaded demo rows.

  SELECT COUNT(*) INTO v_count FROM user_accounts;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty user_accounts'); END IF;
  SELECT COUNT(*) INTO v_count FROM store_shops;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty store_shops'); END IF;
  SELECT COUNT(*) INTO v_count FROM store_services;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty store_services'); END IF;
  SELECT COUNT(*) INTO v_count FROM store_slots;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty store_slots'); END IF;
  SELECT COUNT(*) INTO v_count FROM store_reservations;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty store_reservations'); END IF;
  SELECT COUNT(*) INTO v_count FROM store_customer_locks;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty store_customer_locks'); END IF;
  SELECT COUNT(*) INTO v_count FROM store_idempotency;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty store_idempotency'); END IF;
  SELECT COUNT(*) INTO v_count FROM store_events;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty store_events'); END IF;
  SELECT COUNT(*) INTO v_count FROM search_waitlists;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty search_waitlists'); END IF;
  SELECT COUNT(*) INTO v_count FROM search_intent;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty search_intent'); END IF;
  SELECT COUNT(*) INTO v_count FROM search_idempotency;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty search_idempotency'); END IF;
  SELECT COUNT(*) INTO v_count FROM search_work;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty search_work'); END IF;
  SELECT COUNT(*) INTO v_count FROM search_offer;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty search_offer'); END IF;
  SELECT COUNT(*) INTO v_count FROM search_user_vector;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty search_user_vector'); END IF;
  SELECT COUNT(*) INTO v_count FROM search_store_vector;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty search_store_vector'); END IF;
  SELECT COUNT(*) INTO v_count FROM notification_alerts;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty notification_alerts'); END IF;
  SELECT COUNT(*) INTO v_count FROM notification_subscriptions;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty notification_subscriptions'); END IF;
  SELECT COUNT(*) INTO v_count FROM notification_subscription_sessions;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty notification_subscription_sessions'); END IF;
  SELECT COUNT(*) INTO v_count FROM notification_dedup;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty notification_dedup'); END IF;
  SELECT COUNT(*) INTO v_count FROM notification_events;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty notification_events'); END IF;
  SELECT COUNT(*) INTO v_count FROM notification_deliveries;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty notification_deliveries'); END IF;
  SELECT COUNT(*) INTO v_count FROM notification_delivery_clock;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty notification_delivery_clock'); END IF;
  SELECT COUNT(*) INTO v_count FROM chatting_rooms;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty chatting_rooms'); END IF;
  SELECT COUNT(*) INTO v_count FROM chatting_messages;
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20101, 'Demo requires empty chatting_messages'); END IF;
  SELECT COUNT(*) INTO v_count FROM search_user_lock WHERE bucket_id BETWEEN 0 AND 255;
  IF v_count <> 256 THEN RAISE_APPLICATION_ERROR(-20102, 'Run unified DDL: 256 lock buckets required'); END IF;

  -- 1. 6 owners + 14 customers. Kakao subjects remain NULL; these are not login sessions.

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_owner_01', '데모 사장님 1', 'OWNER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":[],"keywords":[],"maxPrice":null}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_owner_02', '데모 사장님 2', 'OWNER', 37.51500013975466, 126.92900049142719, v_now, 'MANUAL', v_now, '{"categories":[],"keywords":[],"maxPrice":null}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_owner_03', '데모 사장님 3', 'OWNER', 37.51150013975466, 126.9240004914272, v_now, 'MANUAL', v_now, '{"categories":[],"keywords":[],"maxPrice":null}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_owner_04', '데모 사장님 4', 'OWNER', 37.51800013975466, 126.9220004914272, v_now, 'MANUAL', v_now, '{"categories":[],"keywords":[],"maxPrice":null}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_owner_05', '데모 사장님 5', 'OWNER', 37.55000013975466, 127.06000049142719, v_now, 'MANUAL', v_now, '{"categories":[],"keywords":[],"maxPrice":null}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_owner_06', '데모 사장님 6', 'OWNER', 37.49500013975466, 127.03000049142719, v_now, 'MANUAL', v_now, '{"categories":[],"keywords":[],"maxPrice":null}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_01', '서윤', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","심플 디자인"],"maxPrice":45000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_02', '지우', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","포인트 아트"],"maxPrice":45000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_03', '하린', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","심플 디자인"],"maxPrice":45000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_04', '수빈', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","포인트 아트"],"maxPrice":45000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_05', '예린', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","심플 디자인"],"maxPrice":45000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_06', '다은', 'CUSTOMER', 37.55000013975466, 127.06000049142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","포인트 아트"],"maxPrice":45000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_07', '채원', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","심플 디자인"],"maxPrice":45000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_08', '민서', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","포인트 아트"],"maxPrice":45000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_09', '유진', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","심플 디자인"],"maxPrice":60000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_10', '나연', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","포인트 아트"],"maxPrice":60000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_11', '소연', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","심플 디자인"],"maxPrice":60000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_12', '지원', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","포인트 아트"],"maxPrice":60000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_13', '지민', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","심플 디자인"],"maxPrice":60000}', v_now - INTERVAL '7' DAY);

  INSERT INTO user_accounts (user_id, display_name, role_code, latitude, longitude, location_at, location_source, location_saved_at, preferences_json, created_at)
  VALUES ('demo_customer_14', '은서', 'CUSTOMER', 37.51335413975466, 126.92649449142719, v_now, 'MANUAL', v_now, '{"categories":["NAIL"],"keywords":["깔끔한 네일","포인트 아트"],"maxPrice":60000}', v_now - INTERVAL '7' DAY);

  -- 2. Six fictional salons. Coordinates represent a demo area, not verified businesses.

  INSERT INTO store_shops (store_id, owner_user_id, name, category_code, address, latitude, longitude, description, opening_hours_json, created_at)
  VALUES ('demo_shop_01', 'demo_owner_01', '빈틈 데모 여의도 네일', 'NAIL', '가상 시연 매장 1 · 실제 방문 주소 아님', 37.51335413975466, 126.92649449142719, '깔끔한 오피스 네일과 심플 젤네일을 전문으로 하는 가상 매장입니다.', '[{"dayOfWeek":"MON","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"TUE","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"WED","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"THU","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"FRI","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SAT","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SUN","opensAt":"09:00","closesAt":"20:00","closed":false}]', v_now - INTERVAL '7' DAY);

  INSERT INTO store_shops (store_id, owner_user_id, name, category_code, address, latitude, longitude, description, opening_hours_json, created_at)
  VALUES ('demo_shop_02', 'demo_owner_02', '빈틈 데모 샛강 네일', 'NAIL', '가상 시연 매장 2 · 실제 방문 주소 아님', 37.51500013975466, 126.92900049142719, '차분한 누드톤과 빠른 기본 케어를 제공하는 가상 매장입니다.', '[{"dayOfWeek":"MON","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"TUE","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"WED","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"THU","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"FRI","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SAT","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SUN","opensAt":"09:00","closesAt":"20:00","closed":false}]', v_now - INTERVAL '7' DAY);

  INSERT INTO store_shops (store_id, owner_user_id, name, category_code, address, latitude, longitude, description, opening_hours_json, created_at)
  VALUES ('demo_shop_03', 'demo_owner_03', '빈틈 데모 윤슬 네일', 'NAIL', '가상 시연 매장 3 · 실제 방문 주소 아님', 37.51150013975466, 126.9240004914272, '섬세한 포인트 아트와 컬러 디자인 중심의 가상 매장입니다.', '[{"dayOfWeek":"MON","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"TUE","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"WED","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"THU","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"FRI","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SAT","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SUN","opensAt":"09:00","closesAt":"20:00","closed":false}]', v_now - INTERVAL '7' DAY);

  INSERT INTO store_shops (store_id, owner_user_id, name, category_code, address, latitude, longitude, description, opening_hours_json, created_at)
  VALUES ('demo_shop_04', 'demo_owner_04', '빈틈 데모 포인트 네일', 'NAIL', '가상 시연 매장 4 · 실제 방문 주소 아님', 37.51800013975466, 126.9220004914272, '화사한 컬러와 계절 아트를 제공하는 가상 매장입니다.', '[{"dayOfWeek":"MON","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"TUE","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"WED","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"THU","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"FRI","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SAT","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SUN","opensAt":"09:00","closesAt":"20:00","closed":false}]', v_now - INTERVAL '7' DAY);

  INSERT INTO store_shops (store_id, owner_user_id, name, category_code, address, latitude, longitude, description, opening_hours_json, created_at)
  VALUES ('demo_shop_05', 'demo_owner_05', '빈틈 데모 성수 네일', 'NAIL', '가상 시연 매장 5 · 실제 방문 주소 아님', 37.55000013975466, 127.06000049142719, '거리 필터 검증을 위한 성수 권역의 가상 매장입니다.', '[{"dayOfWeek":"MON","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"TUE","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"WED","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"THU","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"FRI","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SAT","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SUN","opensAt":"09:00","closesAt":"20:00","closed":false}]', v_now - INTERVAL '7' DAY);

  INSERT INTO store_shops (store_id, owner_user_id, name, category_code, address, latitude, longitude, description, opening_hours_json, created_at)
  VALUES ('demo_shop_06', 'demo_owner_06', '빈틈 데모 강남 네일', 'NAIL', '가상 시연 매장 6 · 실제 방문 주소 아님', 37.49500013975466, 127.03000049142719, '거리 필터와 비활성 시술 검증을 위한 강남 권역의 가상 매장입니다.', '[{"dayOfWeek":"MON","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"TUE","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"WED","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"THU","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"FRI","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SAT","opensAt":"09:00","closesAt":"20:00","closed":false},{"dayOfWeek":"SUN","opensAt":"09:00","closesAt":"20:00","closed":false}]', v_now - INTERVAL '7' DAY);

  -- 3. 18 services. Ratings stay at the DDL default zero; only actual fixture prices are used.

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_01_care', 'demo_shop_01', '기본 네일 케어', 30, 30000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_01_gel', 'demo_shop_01', '심플 젤네일', 60, 60000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_01_art', 'demo_shop_01', '포인트 아트 네일', 90, 90000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_02_care', 'demo_shop_02', '기본 네일 케어', 30, 30000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_02_gel', 'demo_shop_02', '심플 젤네일', 60, 60000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_02_art', 'demo_shop_02', '포인트 아트 네일', 90, 90000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_03_care', 'demo_shop_03', '기본 네일 케어', 30, 30000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_03_gel', 'demo_shop_03', '심플 젤네일', 60, 60000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_03_art', 'demo_shop_03', '포인트 아트 네일', 90, 90000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_04_care', 'demo_shop_04', '기본 네일 케어', 30, 30000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_04_gel', 'demo_shop_04', '심플 젤네일', 60, 60000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_04_art', 'demo_shop_04', '포인트 아트 네일', 90, 90000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_05_care', 'demo_shop_05', '기본 네일 케어', 30, 30000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_05_gel', 'demo_shop_05', '심플 젤네일', 60, 60000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_05_art', 'demo_shop_05', '포인트 아트 네일', 90, 90000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_06_care', 'demo_shop_06', '기본 네일 케어', 30, 30000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_06_gel', 'demo_shop_06', '심플 젤네일', 60, 60000, 'Y');

  INSERT INTO store_services (service_id, store_id, name, duration_minutes, base_price, active_yn)
  VALUES ('demo_service_06_art', 'demo_shop_06', '포인트 아트 네일', 90, 90000, 'N');

  -- 4. Original regular slots precede reservations (cyclic source FK is respected).

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_01', 'demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 60000, 60000, 'BOOKED', 2, v_day + NUMTODSINTERVAL(-2040, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_02', 'demo_shop_01', 'demo_service_01_care', v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(690, 'MINUTE'), v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(690, 'MINUTE'), 30000, 30000, 'BOOKED', 2, v_day + NUMTODSINTERVAL(-2220, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_03', 'demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(780, 'MINUTE'), v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(780, 'MINUTE'), v_day + NUMTODSINTERVAL(840, 'MINUTE'), 60000, 60000, 'BOOKED', 2, v_day + NUMTODSINTERVAL(-2100, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_04', 'demo_shop_02', 'demo_service_02_care', v_day + NUMTODSINTERVAL(600, 'MINUTE'), v_day + NUMTODSINTERVAL(630, 'MINUTE'), v_day + NUMTODSINTERVAL(600, 'MINUTE'), v_day + NUMTODSINTERVAL(630, 'MINUTE'), 30000, 30000, 'BOOKED', 2, v_day + NUMTODSINTERVAL(-2280, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_05', 'demo_shop_03', 'demo_service_03_art', v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(750, 'MINUTE'), v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(750, 'MINUTE'), 90000, 90000, 'BOOKED', 2, v_day + NUMTODSINTERVAL(-2220, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_06', 'demo_shop_04', 'demo_service_04_gel', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 60000, 60000, 'BOOKED', 2, v_day + NUMTODSINTERVAL(-1920, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_07', 'demo_shop_05', 'demo_service_05_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 60000, 60000, 'BOOKED', 2, v_day + NUMTODSINTERVAL(-2040, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_08', 'demo_shop_06', 'demo_service_06_care', v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(690, 'MINUTE'), v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(690, 'MINUTE'), 30000, 30000, 'BOOKED', 2, v_day + NUMTODSINTERVAL(-2220, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_09', 'demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 60000, 60000, 'CLOSED', 3, v_day + NUMTODSINTERVAL(-1920, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_10', 'demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), 60000, 60000, 'CLOSED', 3, v_day + NUMTODSINTERVAL(-1980, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_11', 'demo_shop_03', 'demo_service_03_gel', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 60000, 60000, 'CLOSED', 3, v_day + NUMTODSINTERVAL(-1920, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_12', 'demo_shop_04', 'demo_service_04_care', v_day + NUMTODSINTERVAL(-2220, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), v_day + NUMTODSINTERVAL(-2220, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), 30000, 30000, 'CLOSED', 3, v_day + NUMTODSINTERVAL(-5100, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_13', 'demo_shop_01', 'demo_service_01_care', v_day + NUMTODSINTERVAL(-3660, 'MINUTE'), v_day + NUMTODSINTERVAL(-3630, 'MINUTE'), v_day + NUMTODSINTERVAL(-3660, 'MINUTE'), v_day + NUMTODSINTERVAL(-3630, 'MINUTE'), 30000, 30000, 'BOOKED', 3, v_day + NUMTODSINTERVAL(-6540, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_14', 'demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(-3480, 'MINUTE'), v_day + NUMTODSINTERVAL(-3420, 'MINUTE'), v_day + NUMTODSINTERVAL(-3480, 'MINUTE'), v_day + NUMTODSINTERVAL(-3420, 'MINUTE'), 60000, 60000, 'BOOKED', 3, v_day + NUMTODSINTERVAL(-6360, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_15', 'demo_shop_03', 'demo_service_03_art', v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), 90000, 90000, 'BOOKED', 3, v_day + NUMTODSINTERVAL(-5160, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_reserved_16', 'demo_shop_04', 'demo_service_04_gel', v_day + NUMTODSINTERVAL(-1980, 'MINUTE'), v_day + NUMTODSINTERVAL(-1920, 'MINUTE'), v_day + NUMTODSINTERVAL(-1980, 'MINUTE'), v_day + NUMTODSINTERVAL(-1920, 'MINUTE'), 60000, 60000, 'BOOKED', 3, v_day + NUMTODSINTERVAL(-4860, 'MINUTE'));

  -- 5. Eight confirmed, four canceled, two completed and two no-show reservations.

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_01', 'demo_customer_14', 'demo_shop_01', 'demo_slot_reserved_01', NULL, 'demo_service_01_gel', '심플 젤네일', 60, '은서', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 60000, 60000, 'CONFIRMED', 1, NULL, NULL, v_day + NUMTODSINTERVAL(-2040, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_02', 'demo_customer_04', 'demo_shop_01', 'demo_slot_reserved_02', 'demo_wait_19', 'demo_service_01_care', '기본 네일 케어', 30, '수빈', v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(690, 'MINUTE'), 30000, 30000, 'CONFIRMED', 1, NULL, NULL, v_day + NUMTODSINTERVAL(-2220, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_03', 'demo_customer_05', 'demo_shop_02', 'demo_slot_reserved_03', 'demo_wait_20', 'demo_service_02_gel', '심플 젤네일', 60, '예린', v_day + NUMTODSINTERVAL(780, 'MINUTE'), v_day + NUMTODSINTERVAL(840, 'MINUTE'), 60000, 60000, 'CONFIRMED', 1, NULL, NULL, v_day + NUMTODSINTERVAL(-2100, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_04', 'demo_customer_06', 'demo_shop_02', 'demo_slot_reserved_04', NULL, 'demo_service_02_care', '기본 네일 케어', 30, '다은', v_day + NUMTODSINTERVAL(600, 'MINUTE'), v_day + NUMTODSINTERVAL(630, 'MINUTE'), 30000, 30000, 'CONFIRMED', 1, NULL, NULL, v_day + NUMTODSINTERVAL(-2280, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_05', 'demo_customer_07', 'demo_shop_03', 'demo_slot_reserved_05', NULL, 'demo_service_03_art', '포인트 아트 네일', 90, '채원', v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(750, 'MINUTE'), 90000, 90000, 'CONFIRMED', 1, NULL, NULL, v_day + NUMTODSINTERVAL(-2220, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_06', 'demo_customer_08', 'demo_shop_04', 'demo_slot_reserved_06', NULL, 'demo_service_04_gel', '심플 젤네일', 60, '민서', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 60000, 60000, 'CONFIRMED', 1, NULL, NULL, v_day + NUMTODSINTERVAL(-1920, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_07', 'demo_customer_09', 'demo_shop_05', 'demo_slot_reserved_07', NULL, 'demo_service_05_gel', '심플 젤네일', 60, '유진', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 60000, 60000, 'CONFIRMED', 1, NULL, NULL, v_day + NUMTODSINTERVAL(-2040, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_08', 'demo_customer_10', 'demo_shop_06', 'demo_slot_reserved_08', NULL, 'demo_service_06_care', '기본 네일 케어', 30, '나연', v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(690, 'MINUTE'), 30000, 30000, 'CONFIRMED', 1, NULL, NULL, v_day + NUMTODSINTERVAL(-2220, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_09', 'demo_customer_11', 'demo_shop_01', 'demo_slot_reserved_09', NULL, 'demo_service_01_gel', '심플 젤네일', 60, '소연', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 60000, 60000, 'CANCELED', 2, 'CUSTOMER_REQUEST', v_now - INTERVAL '1' HOUR, v_day + NUMTODSINTERVAL(-1920, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_10', 'demo_customer_12', 'demo_shop_02', 'demo_slot_reserved_10', NULL, 'demo_service_02_gel', '심플 젤네일', 60, '지원', v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), 60000, 60000, 'CANCELED', 2, 'CUSTOMER_REQUEST', v_now - INTERVAL '1' HOUR, v_day + NUMTODSINTERVAL(-1980, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_11', 'demo_customer_13', 'demo_shop_03', 'demo_slot_reserved_11', NULL, 'demo_service_03_gel', '심플 젤네일', 60, '지민', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 60000, 60000, 'CANCELED', 2, 'CUSTOMER_REQUEST', v_now - INTERVAL '1' HOUR, v_day + NUMTODSINTERVAL(-1920, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_12', 'demo_customer_14', 'demo_shop_04', 'demo_slot_reserved_12', NULL, 'demo_service_04_care', '기본 네일 케어', 30, '은서', v_day + NUMTODSINTERVAL(-2220, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), 30000, 30000, 'CANCELED', 2, 'CUSTOMER_REQUEST', v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-5100, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_13', 'demo_customer_01', 'demo_shop_01', 'demo_slot_reserved_13', NULL, 'demo_service_01_care', '기본 네일 케어', 30, '서윤', v_day + NUMTODSINTERVAL(-3660, 'MINUTE'), v_day + NUMTODSINTERVAL(-3630, 'MINUTE'), 30000, 30000, 'COMPLETED', 2, NULL, NULL, v_day + NUMTODSINTERVAL(-6540, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_14', 'demo_customer_02', 'demo_shop_02', 'demo_slot_reserved_14', NULL, 'demo_service_02_gel', '심플 젤네일', 60, '지우', v_day + NUMTODSINTERVAL(-3480, 'MINUTE'), v_day + NUMTODSINTERVAL(-3420, 'MINUTE'), 60000, 60000, 'COMPLETED', 2, NULL, NULL, v_day + NUMTODSINTERVAL(-6360, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_15', 'demo_customer_03', 'demo_shop_03', 'demo_slot_reserved_15', NULL, 'demo_service_03_art', '포인트 아트 네일', 90, '하린', v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), 90000, 90000, 'NO_SHOW', 2, NULL, NULL, v_day + NUMTODSINTERVAL(-5160, 'MINUTE'));

  INSERT INTO store_reservations (reservation_id, user_id, store_id, slot_id, waitlist_id, service_id, service_name, duration_minutes, customer_name, start_at, end_at, original_price, payable_price, status_code, version_no, cancel_reason_code, canceled_at, created_at)
  VALUES ('demo_reservation_16', 'demo_customer_04', 'demo_shop_04', 'demo_slot_reserved_16', NULL, 'demo_service_04_gel', '심플 젤네일', 60, '수빈', v_day + NUMTODSINTERVAL(-1980, 'MINUTE'), v_day + NUMTODSINTERVAL(-1920, 'MINUTE'), 60000, 60000, 'NO_SHOW', 2, NULL, NULL, v_day + NUMTODSINTERVAL(-4860, 'MINUTE'));

  -- 6. Six source-linked vacancies: draft, published, withdrawn, three expired.

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, source_type, source_reservation_id, original_service_id, vacancy_original_price, publication_version, version_no, published_at, discount_type, discount_value, created_at)
  VALUES ('demo_vac_draft', 'demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 60000, 60000, 'DRAFT', 'CANCELLATION', 'demo_reservation_09', 'demo_service_01_gel', 60000, NULL, 1, NULL, NULL, NULL, v_now - INTERVAL '1' HOUR);

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, source_type, source_reservation_id, original_service_id, vacancy_original_price, publication_version, version_no, published_at, discount_type, discount_value, created_at)
  VALUES ('demo_vac_open', 'demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), 60000, 42000, 'AVAILABLE', 'CANCELLATION', 'demo_reservation_10', 'demo_service_02_gel', 60000, 1, 2, v_now - INTERVAL '30' MINUTE, 'PERCENT', 30, v_now - INTERVAL '1' HOUR);

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, source_type, source_reservation_id, original_service_id, vacancy_original_price, publication_version, version_no, published_at, discount_type, discount_value, created_at)
  VALUES ('demo_vac_withdrawn', 'demo_shop_03', 'demo_service_03_gel', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 60000, 48000, 'CLOSED', 'CANCELLATION', 'demo_reservation_11', 'demo_service_03_gel', 60000, 1, 3, v_now - INTERVAL '30' MINUTE, 'PERCENT', 20, v_now - INTERVAL '1' HOUR);

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, source_type, source_reservation_id, original_service_id, vacancy_original_price, publication_version, version_no, published_at, discount_type, discount_value, created_at)
  VALUES ('demo_vac_expired_cancel', 'demo_shop_04', 'demo_service_04_care', v_day + NUMTODSINTERVAL(-2220, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), v_day + NUMTODSINTERVAL(-2220, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), 30000, 30000, 'EXPIRED', 'CANCELLATION', 'demo_reservation_12', 'demo_service_04_care', 30000, NULL, 2, NULL, NULL, NULL, v_day + NUMTODSINTERVAL(-2280, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, source_type, source_reservation_id, original_service_id, vacancy_original_price, publication_version, version_no, published_at, discount_type, discount_value, created_at)
  VALUES ('demo_vac_expired_noshow_1', 'demo_shop_03', 'demo_service_03_art', v_day + NUMTODSINTERVAL(-2270, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), v_day + NUMTODSINTERVAL(-2270, 'MINUTE'), v_day + NUMTODSINTERVAL(-2190, 'MINUTE'), 90000, 90000, 'EXPIRED', 'NO_SHOW', 'demo_reservation_15', 'demo_service_03_art', 90000, NULL, 2, NULL, NULL, NULL, v_day + NUMTODSINTERVAL(-2270, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, source_type, source_reservation_id, original_service_id, vacancy_original_price, publication_version, version_no, published_at, discount_type, discount_value, created_at)
  VALUES ('demo_vac_expired_noshow_2', 'demo_shop_04', 'demo_service_04_gel', v_day + NUMTODSINTERVAL(-1970, 'MINUTE'), v_day + NUMTODSINTERVAL(-1920, 'MINUTE'), v_day + NUMTODSINTERVAL(-1970, 'MINUTE'), v_day + NUMTODSINTERVAL(-1920, 'MINUTE'), 60000, 60000, 'EXPIRED', 'NO_SHOW', 'demo_reservation_16', 'demo_service_04_gel', 60000, NULL, 2, NULL, NULL, NULL, v_day + NUMTODSINTERVAL(-1970, 'MINUTE'));

  -- 7. Eighteen browse slots: 16 future + 2 expired. Shop 06 art is inactive.

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_01_care', 'demo_shop_01', 'demo_service_01_care', v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2070, 'MINUTE'), v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2070, 'MINUTE'), 30000, 30000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-840, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_01_gel', 'demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), 60000, 60000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-720, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_01_art', 'demo_shop_01', 'demo_service_01_art', v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), 90000, 90000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-540, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_02_care', 'demo_shop_02', 'demo_service_02_care', v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2070, 'MINUTE'), v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2070, 'MINUTE'), 30000, 30000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-840, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_02_gel', 'demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), 60000, 60000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-720, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_02_art', 'demo_shop_02', 'demo_service_02_art', v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), 90000, 90000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-540, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_03_care', 'demo_shop_03', 'demo_service_03_care', v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2070, 'MINUTE'), v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2070, 'MINUTE'), 30000, 30000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-840, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_03_gel', 'demo_shop_03', 'demo_service_03_gel', v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), 60000, 60000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-720, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_03_art', 'demo_shop_03', 'demo_service_03_art', v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), 90000, 90000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-540, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_04_care', 'demo_shop_04', 'demo_service_04_care', v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2070, 'MINUTE'), v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2070, 'MINUTE'), 30000, 30000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-840, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_04_gel', 'demo_shop_04', 'demo_service_04_gel', v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), 60000, 60000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-720, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_04_art', 'demo_shop_04', 'demo_service_04_art', v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), 90000, 90000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-540, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_05_care', 'demo_shop_05', 'demo_service_05_care', v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2250, 'MINUTE'), v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2250, 'MINUTE'), 30000, 30000, 'EXPIRED', 2, v_day + NUMTODSINTERVAL(-5160, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_05_gel', 'demo_shop_05', 'demo_service_05_gel', v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), 60000, 60000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-720, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_05_art', 'demo_shop_05', 'demo_service_05_art', v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), 90000, 90000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-540, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_06_care', 'demo_shop_06', 'demo_service_06_care', v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2250, 'MINUTE'), v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2250, 'MINUTE'), 30000, 30000, 'EXPIRED', 2, v_day + NUMTODSINTERVAL(-5160, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_06_gel', 'demo_shop_06', 'demo_service_06_gel', v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), v_day + NUMTODSINTERVAL(2160, 'MINUTE'), v_day + NUMTODSINTERVAL(2220, 'MINUTE'), 60000, 60000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-720, 'MINUTE'));

  INSERT INTO store_slots (slot_id, store_id, service_id, start_at, end_at, available_from, available_until, original_price, payable_price, status_code, version_no, created_at)
  VALUES ('demo_slot_browse_06_art', 'demo_shop_06', 'demo_service_06_art', v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), v_day + NUMTODSINTERVAL(2340, 'MINUTE'), v_day + NUMTODSINTERVAL(2430, 'MINUTE'), 90000, 90000, 'AVAILABLE', 1, v_day + NUMTODSINTERVAL(-540, 'MINUTE'));

  -- 8. Current waitlists: consent is a synthetic fixture, not consent from real people.
  -- 01/02/03: eligible for main vacancy after 30% discount.
  -- 04/05/06/07/08: reject by time/price/distance/service/specified shop respectively.
  -- 09..12: other scenarios. 13/14: eligible conditions, no runtime push subscription.

  v_conditions := conditions('demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(900, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_01', 'demo_customer_01', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions(NULL, NULL, v_day + NUMTODSINTERVAL(780, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), 45000, 37.51335413975466, 126.92649449142719, 1500);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(960, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_02', 'demo_customer_02', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), 50000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(960, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_03', 'demo_customer_03', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(1020, 'MINUTE'), v_day + NUMTODSINTERVAL(1080, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(1080, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_04', 'demo_customer_04', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 40000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(900, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_05', 'demo_customer_05', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions(NULL, NULL, v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 45000, 37.55000013975466, 127.06000049142719, 500);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(900, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_06', 'demo_customer_06', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_care', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(900, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_07', 'demo_customer_07', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(900, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_08', 'demo_customer_08', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(960, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_09', 'demo_customer_09', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_03', 'demo_service_03_gel', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 50000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(1020, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_10', 'demo_customer_10', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(960, 'MINUTE'), v_day + NUMTODSINTERVAL(1020, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(1020, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_11', 'demo_customer_11', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_04', 'demo_service_04_care', v_day + NUMTODSINTERVAL(2040, 'MINUTE'), v_day + NUMTODSINTERVAL(2100, 'MINUTE'), 30000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(2100, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_12', 'demo_customer_12', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(900, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_13', 'demo_customer_13', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(900, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_14', 'demo_customer_14', 'MANUAL', v_conditions, 'ACTIVE', 1, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_gel', v_day + NUMTODSINTERVAL(840, 'MINUTE'), v_day + NUMTODSINTERVAL(900, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(900, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_15', 'demo_customer_01', 'MANUAL', v_conditions, 'CANCELED', 2, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(900, 'MINUTE'), v_day + NUMTODSINTERVAL(960, 'MINUTE'), 45000);
  v_created_ms := epoch_ms(v_now - INTERVAL '2' HOUR);
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(960, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_16', 'demo_customer_02', 'MANUAL', v_conditions, 'CANCELED', 2, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_care', v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2220, 'MINUTE'), 30000);
  v_created_ms := epoch_ms(v_day + NUMTODSINTERVAL(-3780, 'MINUTE'));
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(-2220, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_17', 'demo_customer_03', 'MANUAL', v_conditions, 'EXPIRED', 2, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_02', 'demo_service_02_care', v_day + NUMTODSINTERVAL(-2280, 'MINUTE'), v_day + NUMTODSINTERVAL(-2220, 'MINUTE'), 30000);
  v_created_ms := epoch_ms(v_day + NUMTODSINTERVAL(-3780, 'MINUTE'));
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(-2220, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_18', 'demo_customer_04', 'MANUAL', v_conditions, 'EXPIRED', 2, v_created_ms, v_created_ms, 1, v_end_ms, NULL);

  v_conditions := conditions('demo_shop_01', 'demo_service_01_care', v_day + NUMTODSINTERVAL(660, 'MINUTE'), v_day + NUMTODSINTERVAL(690, 'MINUTE'), 30000);
  v_created_ms := epoch_ms(v_day + NUMTODSINTERVAL(-3660, 'MINUTE'));
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(690, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_19', 'demo_customer_04', 'MANUAL', v_conditions, 'FULFILLED', 2, v_created_ms, v_created_ms, 1, v_end_ms, 'demo_reservation_02');

  v_conditions := conditions('demo_shop_02', 'demo_service_02_gel', v_day + NUMTODSINTERVAL(780, 'MINUTE'), v_day + NUMTODSINTERVAL(840, 'MINUTE'), 60000);
  v_created_ms := epoch_ms(v_day + NUMTODSINTERVAL(-3540, 'MINUTE'));
  v_end_ms := epoch_ms(v_day + NUMTODSINTERVAL(840, 'MINUTE'));
  INSERT INTO search_intent (id, user_id, source, conditions_json, status, resource_version, created_at, consent_at, consent_version, window_end, reservation_id)
  VALUES ('demo_wait_20', 'demo_customer_05', 'MANUAL', v_conditions, 'FULFILLED', 2, v_created_ms, v_created_ms, 1, v_end_ms, 'demo_reservation_03');

  -- All queues, notifications, subscriptions, vectors, idempotency and chat stay empty.
  -- Direct INSERT does not emit events: use the documented API flow/rebuild after loading.
  COMMIT;
EXCEPTION
  WHEN OTHERS THEN
    ROLLBACK;
    RAISE;
END;
/
