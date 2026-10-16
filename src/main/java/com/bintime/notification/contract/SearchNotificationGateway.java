package com.bintime.notification.contract;

import com.bintime.notification.dto.DispatchNotificationRequest;

/** N-06 접수. user+slot 중복 방지, 최종 동의/발송 제한 및 대기 버전 확인은 수신자 책임. */
public interface SearchNotificationGateway {
  void dispatch(DispatchNotificationRequest request);
}
