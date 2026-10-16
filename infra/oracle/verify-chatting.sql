-- Run as the application user AFTER 004-chatting.sql. All fixture rows are rolled back.
SET DEFINE OFF
SET SERVEROUTPUT ON
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

DECLARE
  suffix VARCHAR2(32) := LOWER(RAWTOHEX(SYS_GUID()));
  customer VARCHAR2(64) := 'chat_check_customer_' || suffix;
  owner_id VARCHAR2(64) := 'chat_check_owner_' || suffix;
  shop VARCHAR2(64) := 'chat_check_shop_' || suffix;
  room VARCHAR2(64) := 'chat_check_room_' || suffix;
  first_message VARCHAR2(64) := 'chat_check_msg1_' || suffix;
  second_message VARCHAR2(64) := 'chat_check_msg2_' || suffix;
  count_rows NUMBER;

  PROCEDURE rejected(statement VARCHAR2, expected_code NUMBER) IS
  BEGIN
    BEGIN
      EXECUTE IMMEDIATE statement;
    EXCEPTION WHEN OTHERS THEN
      IF SQLCODE=expected_code THEN RETURN; END IF;
      RAISE;
    END;
    RAISE_APPLICATION_ERROR(-20090, 'Expected constraint rejection was not raised');
  END;
BEGIN
  SAVEPOINT chatting_schema_check;
  SELECT COUNT(*) INTO count_rows FROM user_tables WHERE table_name IN (
    'CHATTING_ROOMS','CHATTING_MESSAGES','CHATTING_READ_POSITIONS','CHATTING_IDEMPOTENCY');
  IF count_rows<>4 THEN RAISE_APPLICATION_ERROR(-20091, 'Four chatting tables are required'); END IF;

  -- Check cross-row rules too, so this script can validate a colleague's dummy data.
  SELECT COUNT(*) INTO count_rows FROM chatting_rooms r
    WHERE r.last_sequence<>(SELECT COALESCE(MAX(m.message_sequence),0) FROM chatting_messages m WHERE m.room_id=r.room_id)
       OR (r.last_sequence=0 AND r.last_message_at IS NOT NULL)
       OR (r.last_sequence>0 AND (r.last_message_at IS NULL OR r.last_message_at<>(
         SELECT m.created_at FROM chatting_messages m WHERE m.room_id=r.room_id AND m.message_sequence=r.last_sequence)));
  IF count_rows>0 THEN RAISE_APPLICATION_ERROR(-20094, 'Room last message or sequence is inconsistent'); END IF;
  SELECT COUNT(*) INTO count_rows FROM chatting_messages m
    JOIN chatting_rooms r ON r.room_id=m.room_id JOIN store_shops s ON s.store_id=r.store_id
    WHERE m.sender_id NOT IN (r.customer_id,s.owner_user_id) OR DBMS_LOB.GETLENGTH(m.content_text) NOT BETWEEN 1 AND 2000;
  IF count_rows>0 THEN RAISE_APPLICATION_ERROR(-20095, 'Message sender or content is invalid'); END IF;
  SELECT COUNT(*) INTO count_rows FROM chatting_read_positions p
    JOIN chatting_rooms r ON r.room_id=p.room_id JOIN store_shops s ON s.store_id=r.store_id
    WHERE p.user_id NOT IN (r.customer_id,s.owner_user_id) OR p.last_read_sequence>r.last_sequence;
  IF count_rows>0 THEN RAISE_APPLICATION_ERROR(-20096, 'Read cursor or participant is invalid'); END IF;

  INSERT INTO user_accounts(user_id,display_name,role_code) VALUES(customer,'채팅 검증 고객','CUSTOMER');
  INSERT INTO user_accounts(user_id,display_name,role_code) VALUES(owner_id,'채팅 검증 사장님','OWNER');
  INSERT INTO store_shops(store_id,owner_user_id,name,category_code,address,latitude,longitude)
    VALUES(shop,owner_id,'채팅 검증 매장','NAIL','검증용',37.5,127);
  INSERT INTO chatting_rooms(room_id,customer_id,store_id) VALUES(room,customer,shop);
  INSERT INTO chatting_messages(message_id,room_id,sender_id,message_sequence,client_message_id,content_text)
    VALUES(first_message,room,customer,1,'first','예약 문의입니다.');
  INSERT INTO chatting_messages(message_id,room_id,sender_id,message_sequence,client_message_id,content_text)
    VALUES(second_message,room,owner_id,2,'second','네, 가능합니다.');
  UPDATE chatting_rooms SET last_sequence=2,
    last_message_at=(SELECT created_at FROM chatting_messages WHERE message_id=second_message)
    WHERE room_id=room;
  INSERT INTO chatting_read_positions(room_id,user_id,last_read_sequence,read_at)
    VALUES(room,customer,2,SYSTIMESTAMP);
  INSERT INTO chatting_read_positions(room_id,user_id) VALUES(room,owner_id);
  INSERT INTO chatting_idempotency(actor_id,operation,key_value,fingerprint,result_json,http_status,expires_at)
    VALUES(customer,'POST:/api/v1/chatting/rooms','check',RPAD('a',64,'a'),'{"id":"check"}',201,
      SYSTIMESTAMP+INTERVAL '1' DAY);

  rejected('INSERT INTO chatting_rooms(customer_id,store_id) VALUES('''||customer||''','''||shop||''')',-1);
  rejected('INSERT INTO chatting_messages(room_id,sender_id,message_sequence,client_message_id,content_text)
    VALUES('''||room||''','''||customer||''',1,''different'',''duplicate sequence'')',-1);
  rejected('INSERT INTO chatting_messages(room_id,sender_id,message_sequence,client_message_id,content_text)
    VALUES('''||room||''','''||customer||''',3,''first'',''duplicate client ID'')',-1);
  rejected('INSERT INTO chatting_messages(room_id,sender_id,message_sequence,client_message_id,content_text)
    VALUES('''||room||''',''missing_'||suffix||''',3,''third'',''missing sender'')',-2291);
  rejected('UPDATE chatting_rooms SET status_code=''CLOSED'' WHERE room_id='''||room||'''',-2290);
  rejected('UPDATE chatting_rooms SET last_sequence=9007199254740992 WHERE room_id='''||room||'''',-2290);
  rejected('UPDATE chatting_read_positions SET last_read_sequence=-1
    WHERE room_id='''||room||''' AND user_id='''||owner_id||'''',-2290);
  rejected('UPDATE chatting_read_positions SET last_read_sequence=1,read_at=NULL
    WHERE room_id='''||room||''' AND user_id='''||owner_id||'''',-2290);
  rejected('UPDATE chatting_idempotency SET result_json=''not json'' WHERE actor_id='''||customer||'''',-2290);
  rejected('INSERT INTO chatting_idempotency SELECT * FROM chatting_idempotency
    WHERE actor_id='''||customer||'''',-1);

  SELECT COUNT(*) INTO count_rows FROM chatting_rooms
    WHERE room_id=room AND status_code='OPEN' AND last_sequence=2 AND last_message_at IS NOT NULL;
  IF count_rows<>1 THEN RAISE_APPLICATION_ERROR(-20092, 'Room defaults or last message failed'); END IF;
  SELECT COUNT(*) INTO count_rows FROM chatting_read_positions
    WHERE room_id=room AND user_id=owner_id AND last_read_sequence=0 AND read_at IS NULL;
  IF count_rows<>1 THEN RAISE_APPLICATION_ERROR(-20093, 'Unread cursor defaults failed'); END IF;

  ROLLBACK TO chatting_schema_check;
  DBMS_OUTPUT.PUT_LINE('Chatting schema v1: defaults, FKs, deduplication, sequence, read cursor and JSON checks passed; fixtures rolled back.');
EXCEPTION WHEN OTHERS THEN
  ROLLBACK TO chatting_schema_check;
  RAISE;
END;
/
