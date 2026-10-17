package com.bintime.store.contract;

import java.time.OffsetDateTime;

/** 다른 도메인이 사용하는 store 예약 생성·확정 계약. 호출자는 인증된 본인의 사용자 ID를 전달한다. */
public interface StoreReservationPort {
  /** 슬롯 정책에 따라 CONFIRMED 또는 PENDING을 반환한다. PENDING은 예약 확정·선점이 아니다. 재시도에는 같은 키와 요청을 사용한다. */
  ConfirmedReservation reserve(Command command);

  /** 제안 작성 시점의 유효성만 검사한다. 공급 슬롯을 만들거나 시간을 선점하지 않는다. */
  void validateProposal(ProposalCommand command);

  /** 서버에 저장된 제안 스냅샷으로 예약한다. 호출자 트랜잭션에 참여한다. */
  ConfirmedReservation reserveProposal(ProposalCommand command);

  record ProposalCommand(
      String userId,
      String ownerId,
      String idempotencyKey,
      String proposalId,
      String storeId,
      String serviceId,
      String serviceDescription,
      OffsetDateTime startAt,
      Integer durationMinutes,
      Long price,
      String waitlistId) {}

  /** 가격은 사용자가 확인한 KRW 정수 원, 버전은 최신 슬롯 조회 값이다. waitlistId는 선택이다. */
  record Command(
      String userId,
      String idempotencyKey,
      String slotId,
      Long expectedPrice,
      Long expectedSlotVersion,
      String waitlistId) {}

  /** 시각은 오프셋을 포함하며 시술 구간은 [startAt, endAt)이다. */
  record ConfirmedReservation(
      String reservationId,
      String storeId,
      String serviceId,
      String slotId,
      String status,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      long originalPrice,
      long payablePrice,
      String waitlistId,
      long version) {}
}
