# 푸시 발송 이력

채팅(`CHAT_MESSAGE`)과 매칭(`VACANCY_MATCHED`) 알림의 생성 및 기기별 발송 이력을 `BINTIME_APP.NOTIFICATION_PUSH_HISTORY`에 누적한다. 기존 매칭 알림 목록·읽음 API와 채팅 응답은 변경하지 않는다. 화면의 토스트 표시나 운영체제 알림센터의 실제 수신을 확인하는 기록은 아니다.

## 적용

기존 로컬·OCI 앱 계정에 [009-notification-push-history.sql](../infra/oracle/migrations/009-notification-push-history.sql)을 **새 앱 배포 전에** 실행한다. 테이블 1개·인덱스 2개만 추가하며 재실행할 수 있다. 기존 데이터는 수정하지 않는다. 신규 `ddl.sql`·`local-setup/01ddl.sql`에는 이미 포함되어 있다. 앱 시작 검증에서 테이블·컬럼 누락을 검사한다.

새 버전에서 생성한 알림부터 기록한다. 과거 채팅 푸시의 결과는 복원하지 않는다. 이전 매칭 기록은 기존 `notification_alerts`·`notification_deliveries`에서 확인한다.

## 상태

| status | 의미 |
| --- | --- |
| `QUEUED` | 알림 생성 또는 재시도 대기. `subscription_id IS NULL`, `attempt_no=0`인 행은 알림 생성 기록 |
| `RUNNING` | 해당 기기 작업 시작. 이후 결과 행이 없다면 진행 중이거나 결과 미확인 |
| `ACCEPTED` | FCM 접수 성공. 기기 수신·화면 표시·읽음 완료를 의미하지 않음 |
| `FAILED` | 재시도 불가 또는 최대 시도 초과 |
| `SKIPPED` | 이미 읽음, 유효 구독 없음, 조건 변경, 만료, FCM 비활성화, 큐 용량 초과 등으로 발송 제외 |

`notification_id`는 채팅 메시지 ID 또는 매칭 알림 ID다. `user_id`는 수신자, `subscription_id`는 내부 기기 구독 ID다. 제목·본문 미리보기·이동 경로를 함께 보존한다. FID·기기 토큰·자격 증명·FCM 원문 응답은 복사하지 않는다. 사용자·구독 삭제 후에도 이력을 보존하므로 FK 및 연쇄 삭제는 사용하지 않는다. 자동 삭제 기간은 설정하지 않았다.

생성 이력은 메시지/매칭 알림과 같은 트랜잭션에서 저장하고 롤백도 함께 한다. 발송 시작 기록을 저장할 수 없으면 FCM 요청을 시작하지 않는다. 결과 저장만 실패한 경우 그 실패 때문에 접수된 푸시를 재발송하지 않는다. 이때 채팅의 `RUNNING`은 결과 미확인으로 남을 수 있고, 매칭의 최종 상태는 기존 `notification_deliveries`에서도 확인할 수 있다. FCM 요청과 DB는 하나의 트랜잭션이 아니므로 프로세스 중단·응답 유실 시 정확히 한 번 전달을 보장하지 않는다.

채팅의 미전송 큐는 기존처럼 메모리에 있다. 이력은 재시작 후에도 보존되지만 자동 재발송용 큐는 아니다. 재시작 직전의 `QUEUED`·`RUNNING`을 성공으로 해석하지 않는다. 매칭은 기존 DB 큐를 계속 사용한다. 푸시 대상으로 생성되지 않은 검색 후보, 중복 요청의 재생, SSE heartbeat·WebSocket 프레임·프런트 토스트는 별도 발송 이력으로 만들지 않는다.

## DataGrip 조회

```sql
SELECT notification_type, notification_id, user_id, subscription_id,
       attempt_no, status, error_code, created_at
FROM BINTIME_APP.NOTIFICATION_PUSH_HISTORY
ORDER BY created_at DESC
FETCH FIRST 100 ROWS ONLY;
```

특정 채팅 메시지는 `WHERE notification_type = 'CHAT_MESSAGE' AND notification_id = '메시지ID'`로 찾는다. 매칭은 `VACANCY_MATCHED`로 찾는다. `attempt_no`는 기존 기기별 재시도 번호를 사용하며, 매칭의 속도 제한·유효성 검사 제외는 재시도 예산을 소비하지 않는다. 같은 번호에 `RUNNING`·`QUEUED`·최종 결과가 여러 행으로 남을 수 있다.
