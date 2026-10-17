# 채팅·store·search 알림 연결 점검

점검 대상: 현재 로컬 작업 트리. OCI 배포 설정·DB와 실제 브라우저 수신은 직접 조회하지 않았다.

## 전송 경로 비교

| 항목 | 채팅 | store·search 빈자리 알림 |
| --- | --- | --- |
| 시작 | ChatService 메시지 저장 | 사장님의 빈자리 발행 또는 기존 대기 대상 일반 슬롯 생성 |
| 연결 | 커밋 이벤트 → ChatWebSocket | store_events → StoreEventDelivery → search_work SLOT/NOTIFY → SearchNotificationGateway → NotificationService |
| 실시간 화면 | WebSocket message.created | 영속 notification_events → SSE notification.created |
| 외부 푸시 | 이 저장소 채팅에는 FCM 호출 없음 | notification_deliveries → NotificationDeliveryWorker → FcmClient HTTP v1 |
| 구독 | 로그인·채팅방 WebSocket 구독 | 현재 로그인 세션에 연결된 활성 FID 구독 |
| 추가 판단 | 채팅방 참여 권한 | 현재 슬롯·동의·벡터 기준, WAITLIST 경로는 대기 필수 조건도 확인 |

일반 실행에서 SearchNotificationGateway는 실제 NotificationService다. Swagger 전용 api-docs 프로필에서만 대체 Bean을 사용한다. 채팅 수신 성공은 SSE 연결·FID 등록·FCM 활성화·search/store 소비자의 실행을 검증하지 않는다.

## 이벤트별 실제 동작

- 예약 취소·NO_SHOW는 남은 예약 시간으로 DRAFT 빈자리를 만든다. 해당 처리만으로 고객에게 맞춤 빈자리 알림을 보내지 않는다.
- 사장님의 POST /api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/publish가 AVAILABLE 상태·현재 발행 버전·PUBLISHED 이벤트를 같은 트랜잭션에 저장한다.
- StoreEventDelivery는 커밋된 이벤트를 처리하고 search 큐 접수와 PROCESSED 변경을 함께 커밋한다. 실패는 FAILED와 last_error를 남기고 재시도한다.
- SLOT 작업은 기존 ACTIVE 대기와 앱 전체 맞춤 알림 동의 사용자를 각각 탐색한다. 새 맞춤 경로에는 대기·프로필 위치가 필요 없다. 일반 REGULAR 슬롯은 맞춤 경로에서 제외한다.
- NOTIFY는 활성 기기, 슬롯의 현재 발행 버전, 자격 근거와 벡터 유사도(기본 0.5 이상)를 확인한다. 벡터 미준비는 생성 요청 후 보류한다.
- 알림 접수는 사용자+슬롯 중복을 막고 알림·기기별 발송 큐·SSE 이벤트를 함께 저장한다. FCM 제출 직전에 동의·슬롯·현재 구독·벡터를 다시 검증한다.
- search/stores 지도 조회 자체는 알림을 발행하지 않는다. 선호 저장은 VECTOR 작업을 만들고, 앱 전체 맞춤 동의가 있으면 PERSONALIZE로 기존 발행 빈자리도 탐색한다.
- 구현된 FCM 알림 유형은 VACANCY_MATCHED다. 예약 생성·예약 취소를 사장님에게 별도로 통지하는 업무 알림은 이 경로에 포함되지 않는다.

## 운영에서 확인해야 하는 차이

1. compose.yaml 단독 구성에는 FCM 환경 변수·서비스 계정 마운트가 없다. 기본 FCM_ENABLED=false이며 큐는 남지만 외부 제출은 하지 않는다. .env에 FCM_ENABLED만 쓰는 것으로 compose.yaml에 없는 환경 변수가 컨테이너에 전달되지는 않는다. FCM_CREDENTIALS_FILE을 설정하고 compose.fcm.yaml을 함께 사용해야 한다.
2. 정상 소비자 설정은 BINTIME_STORE_EVENT_DELIVERY_ENABLED=true, SEARCH_SCHEDULING_ENABLED=true, notification.scheduling-enabled=true다. 마지막 설정의 기본값은 true다. 기동 시 전체 재색인과 이벤트·매칭·푸시 소비자는 별개다.
3. 맞춤 동의 기본값은 false다. migration 008 적용 후 U-11로 동의를 활성화하고, 현재 로그인 세션에서 FID를 등록해야 한다. 현재는 SSE 알림 생성도 활성 FID 구독을 전제로 한다.
4. 구독은 현재 앱 인스턴스·메모리 로그인 세션에 묶인다. 재배포 후 DB active_yn='Y'만 확인해서는 충분하지 않으며 재로그인·FID 재등록이 필요하다. 로그아웃·세션 만료도 수신 대상에서 제외한다.
5. 외부 푸시 ACCEPTED는 FCM 접수다. 서비스 워커의 표시 처리·브라우저 권한·기기 수신은 별도 확인해야 한다. 클릭은 /customer/waitlist와 notificationId를 전달한다. 대기 없는 맞춤 알림의 target.waitlistIds=[]도 프런트에서 처리해야 한다.

컨테이너의 실제 값 확인(비밀 키·서비스 계정 내용은 출력하지 않음):

```bash
sudo docker compose -f compose.yaml exec -T app sh -c 'printenv | grep -E "^(SPRING_PROFILES_ACTIVE|SEARCH_INDEX_REBUILD|SEARCH_SCHEDULING_ENABLED|BINTIME_STORE_EVENT_DELIVERY_ENABLED|NOTIFICATION_SCHEDULING_ENABLED|FCM_ENABLED|FCM_PROJECT_ID|GOOGLE_APPLICATION_CREDENTIALS)="'
```

notification.scheduling-enabled의 환경 변수 미지정은 기본 true를 사용한다. 위 명령으로 application 인자나 별도 프로퍼티 파일의 덮어쓰기까지 확인되는 것은 아니다.

운영 DB에서 최근 처리 결과 확인:

```sql
SELECT event_id, event_type, slot_id, status_code, attempts, last_error
FROM store_events ORDER BY created_at DESC FETCH FIRST 20 ROWS ONLY;

SELECT id, kind, status, attempts, last_error
FROM search_work ORDER BY available_at DESC FETCH FIRST 30 ROWS ONLY;

SELECT notification_id, user_id, slot_id, push_status, push_error_code
FROM notification_alerts ORDER BY created_at DESC FETCH FIRST 20 ROWS ONLY;

SELECT d.notification_id, d.status, d.attempts, d.error_code
FROM notification_deliveries d
JOIN notification_alerts a ON a.notification_id=d.notification_id
ORDER BY a.created_at DESC FETCH FIRST 30 ROWS ONLY;
```

정상적인 흐름은 PUBLISHED 이벤트 PROCESSED → SLOT/NOTIFY SUCCEEDED → notification_alerts 생성 → 기기별 ACCEPTED다. search의 SUCCEEDED는 적격자가 없어서 종료한 경우도 포함하므로 발송 성공 증거가 아니다. 실제 발송은 notification_deliveries의 ACCEPTED 및 브라우저 수신으로 확인한다. 알림이 저장되지 않았다면 FCM보다 앞선 동의·기기·슬롯·벡터 판정을 확인한다.

## 추가로 확인한 경계

- 동일 사용자·슬롯에서 WAITLIST/PERSONALIZED가 겹치면 먼저 저장된 알림만 유지한다. 발송 직전에는 저장된 audience만 검증한다. 한쪽 자격을 해제했지만 다른 쪽 자격은 유지한 경우 다른 근거로 자동 전환하지 않아 발송이 생략될 수 있다. 두 자격의 합집합을 끝까지 보장하려면 자격 근거 병합 또는 재평가 보완이 필요하다.
- 맞춤 동의 활성화 시 기기가 없으면 기존 빈자리 탐색은 끝난다. 이후 FID 등록만으로 기존 빈자리를 다시 탐색하지 않는다. 시연은 FID 등록 → 동의 활성화 순서를 사용한다(이미 동의했다면 현재 버전으로 true를 다시 저장해 재탐색 가능).

## 검증 범위

ChatRealtimeIntegrationTests는 실제 Spring WebSocket의 저장·브로드캐스트·재연결 재생을 검증한다. StoreNotificationIntegrationTests는 실제 store outbox → search 영속 큐 → 알림 저장·전달 worker를 실행하고 외부 OpenAI·FCM만 대체한다. 이번 점검에는 NO_SHOW → DRAFT에서 무발송 → 남은 시간에 맞는 짧은 시술로 발행 → 대기 없는 사용자 FCM 호출 검증을 추가했다.

NotificationDeliveryTests는 중복·재시도·비활성 공급자·슬롯 철회/재발행·세션 해제·최종 제출 경계를 검증한다. FcmClientTests는 로컬 HTTP 서버로 요청 형식·인증·응답/오류 분류를 검증하며 실제 Firebase 수락을 재현한 것은 아니다. search/store 스케줄 타이머는 통합 테스트에서 비활성화하고 실제 소비자 메서드를 직접 호출하므로 운영 타이머 실행 여부는 위 설정과 큐 상태로 별도 확인한다.
