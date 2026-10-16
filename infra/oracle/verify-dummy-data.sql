-- Read-only fixture checks. Run immediately after dummy-data.sql, before demo API mutations.
DECLARE
  n NUMBER;
  PROCEDURE expect_count(p_table VARCHAR2, p_expected NUMBER) IS
    actual NUMBER;
  BEGIN
    EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM ' || p_table INTO actual;
    IF actual <> p_expected THEN
      RAISE_APPLICATION_ERROR(-20201, p_table || ': expected ' || p_expected || ', actual ' || actual);
    END IF;
  END;
  PROCEDURE require_zero(p_actual NUMBER, p_label VARCHAR2) IS
  BEGIN
    IF p_actual <> 0 THEN RAISE_APPLICATION_ERROR(-20202, p_label || ': ' || p_actual); END IF;
  END;
BEGIN
  expect_count('user_accounts', 20);
  expect_count('store_shops', 6);
  expect_count('store_services', 18);
  expect_count('store_slots', 40);
  expect_count('store_reservations', 16);
  expect_count('search_intent', 20);
  expect_count('search_user_lock', 256);
  expect_count('store_customer_locks', 0);
  expect_count('store_idempotency', 0);
  expect_count('store_events', 0);
  expect_count('search_waitlists', 0);
  expect_count('search_idempotency', 0);
  expect_count('search_work', 0);
  expect_count('search_offer', 0);
  expect_count('search_user_vector', 0);
  expect_count('search_store_vector', 0);
  expect_count('notification_alerts', 0);
  expect_count('notification_subscriptions', 0);
  expect_count('notification_subscription_sessions', 0);
  expect_count('notification_dedup', 0);
  expect_count('notification_events', 0);
  expect_count('notification_deliveries', 0);
  expect_count('notification_delivery_clock', 0);
  expect_count('chatting_rooms', 0);
  expect_count('chatting_messages', 0);

  SELECT ABS(COUNT(*)-6) INTO n FROM user_accounts WHERE role_code='OWNER';
  require_zero(n, 'Owner count');
  SELECT ABS(COUNT(*)-14) INTO n FROM user_accounts WHERE role_code='CUSTOMER';
  require_zero(n, 'Customer count');
  SELECT ABS(COUNT(*)-8) INTO n FROM store_reservations WHERE status_code='CONFIRMED';
  require_zero(n, 'Confirmed reservation count');
  SELECT ABS(COUNT(*)-4) INTO n FROM store_reservations WHERE status_code='CANCELED';
  require_zero(n, 'Canceled reservation count');
  SELECT ABS(COUNT(*)-2) INTO n FROM store_reservations WHERE status_code='COMPLETED';
  require_zero(n, 'Completed reservation count');
  SELECT ABS(COUNT(*)-2) INTO n FROM store_reservations WHERE status_code='NO_SHOW';
  require_zero(n, 'No-show reservation count');
  SELECT ABS(COUNT(*)-14) INTO n FROM search_intent WHERE status='ACTIVE';
  require_zero(n, 'Active waitlist count');
  SELECT COUNT(*) INTO n FROM user_accounts
    WHERE kakao_subject IS NOT NULL OR preferences_json IS NOT JSON (STRICT);
  require_zero(n, 'Synthetic Kakao ID or malformed preferences');
  SELECT COUNT(*) INTO n FROM store_services WHERE active_yn='N' AND service_id<>'demo_service_06_art';
  require_zero(n, 'Unexpected inactive service');
  SELECT ABS(COUNT(*)-1) INTO n FROM store_services WHERE service_id='demo_service_06_art' AND active_yn='N';
  require_zero(n, 'Inactive service fixture missing');

  -- These rules span rows/tables and cannot be checked by FK/CHECK alone.
  SELECT COUNT(*) INTO n FROM store_reservations a JOIN store_reservations b
    ON a.reservation_id<b.reservation_id AND a.start_at<b.end_at AND b.start_at<a.end_at
    AND (a.store_id=b.store_id OR a.user_id=b.user_id)
    WHERE a.status_code='CONFIRMED' AND b.status_code='CONFIRMED';
  require_zero(n, 'Overlapping confirmed reservations');
  SELECT COUNT(*) INTO n FROM store_slots a JOIN store_slots b
    ON a.store_id=b.store_id AND a.slot_id<b.slot_id AND a.start_at<b.end_at AND b.start_at<a.end_at
    WHERE a.status_code='AVAILABLE' AND b.status_code='AVAILABLE';
  require_zero(n, 'Overlapping public supply');
  SELECT COUNT(*) INTO n FROM store_slots s JOIN store_reservations r
    ON s.store_id=r.store_id AND s.start_at<r.end_at AND r.start_at<s.end_at
    WHERE s.status_code='AVAILABLE' AND r.status_code='CONFIRMED';
  require_zero(n, 'Public supply overlaps a confirmed booking');
  SELECT COUNT(*) INTO n FROM store_reservations r JOIN store_slots s ON r.slot_id=s.slot_id
    JOIN store_services v ON v.service_id=r.service_id
    JOIN user_accounts u ON u.user_id=r.user_id
    WHERE r.store_id<>s.store_id OR r.service_id<>s.service_id OR r.start_at<>s.start_at OR r.end_at<>s.end_at
      OR r.duration_minutes<>v.duration_minutes OR r.customer_name<>u.display_name
      OR r.original_price<>s.original_price OR r.payable_price<>s.payable_price
      OR r.created_at>=r.start_at
      OR (r.status_code='CANCELED' AND (r.canceled_at IS NULL OR r.canceled_at<r.created_at));
  require_zero(n, 'Reservation snapshots or chronology');
  SELECT COUNT(*) INTO n FROM store_slots s JOIN store_reservations r ON s.source_reservation_id=r.reservation_id
    WHERE r.status_code NOT IN ('CANCELED','NO_SHOW') OR s.store_id<>r.store_id
      OR s.original_service_id<>r.service_id OR s.vacancy_original_price<>r.original_price
      OR s.available_until<>r.end_at OR s.available_from<r.start_at OR s.created_at<r.created_at;
  require_zero(n, 'Vacancy source or snapshot mismatch');
  SELECT COUNT(*) INTO n FROM store_slots
    WHERE source_type<>'REGULAR' AND discount_type='PERCENT'
      AND payable_price<>FLOOR(original_price*(100-discount_value)/100);
  require_zero(n, 'Discount calculation');
  SELECT COUNT(*) INTO n FROM store_slots
    WHERE (status_code='AVAILABLE' AND start_at<=SYSTIMESTAMP)
      OR (status_code='EXPIRED' AND available_until>SYSTIMESTAMP);
  require_zero(n, 'Future supply or historical expiry');
  SELECT COUNT(*) INTO n FROM store_reservations
    WHERE status_code IN ('COMPLETED','NO_SHOW') AND end_at>SYSTIMESTAMP;
  require_zero(n, 'Historical reservation timing');
  SELECT COUNT(*) INTO n FROM search_intent
    WHERE conditions_json IS NOT JSON (STRICT)
      OR (status='ACTIVE' AND (window_end<=
        (CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE)-DATE '1970-01-01')*86400000
        OR consent_at<=0 OR consent_version<>resource_version));
  require_zero(n, 'Waitlist JSON, expiry or consent');
  SELECT COUNT(*) INTO n FROM search_intent i LEFT JOIN store_reservations r ON i.reservation_id=r.reservation_id
    WHERE i.status='FULFILLED' AND (r.reservation_id IS NULL OR r.user_id<>i.user_id OR r.waitlist_id<>i.id);
  require_zero(n, 'Fulfilled waitlist reservation link');
  SELECT COUNT(*) INTO n FROM store_reservations r LEFT JOIN search_intent i ON r.waitlist_id=i.id
    WHERE r.waitlist_id IS NOT NULL AND (i.id IS NULL OR i.reservation_id<>r.reservation_id OR i.status<>'FULFILLED');
  require_zero(n, 'Reservation waitlist back-link');
END;
/

SELECT role_code,COUNT(*) AS rows_in_fixture FROM user_accounts GROUP BY role_code;
SELECT status_code,COUNT(*) AS rows_in_fixture FROM store_reservations GROUP BY status_code;
SELECT status,COUNT(*) AS rows_in_fixture FROM search_intent GROUP BY status;
SELECT slot_id,TO_CHAR(start_at AT TIME ZONE 'Asia/Seoul','YYYY-MM-DD HH24:MI TZH:TZM') AS starts_at,
  original_price,payable_price,status_code,version_no
  FROM store_slots WHERE slot_id IN ('demo_slot_reserved_01','demo_vac_draft','demo_vac_open','demo_vac_withdrawn')
  ORDER BY slot_id;
