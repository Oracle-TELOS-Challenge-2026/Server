package com.bintime.store.adapter;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.store.contract.StoreReservationPort;
import com.bintime.store.dto.CreateReservationRequest;
import com.bintime.store.service.StoreApplicationService;
import com.bintime.user.service.UserAccountAccess;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Lazy
@Transactional
public class StoreReservationAdapter implements StoreReservationPort {
  private final StoreApplicationService stores;
  private final UserAccountAccess users;

  public StoreReservationAdapter(StoreApplicationService stores, UserAccountAccess users) {
    this.stores = stores;
    this.users = users;
  }

  @Override
  public ConfirmedReservation reserve(Command command) {
    require(command != null, 400, "INVALID_INPUT");
    require(command.userId() != null && !command.userId().isBlank(), 401, "UNAUTHENTICATED");
    // 호출자가 이름·역할을 주입하지 못하도록 현재 계정을 조회한다. HTTP 요청 컨텍스트는 필요 없다.
    var actor = users.get(command.userId());
    var reservation =
        stores.reserve(
            actor,
            command.idempotencyKey(),
            new CreateReservationRequest(
                command.slotId(),
                command.expectedPrice(),
                command.expectedSlotVersion(),
                command.waitlistId()));
    return confirmed(reservation);
  }

  @Override
  public void validateProposal(ProposalCommand command) {
    stores.validateProposal(command);
  }

  @Override
  public ConfirmedReservation reserveProposal(ProposalCommand command) {
    return confirmed(stores.reserveProposal(command));
  }

  private ConfirmedReservation confirmed(com.bintime.store.dto.ReservationResponse reservation) {
    return new ConfirmedReservation(
        reservation.id(),
        reservation.store().id(),
        reservation.service().id(),
        reservation.slotId(),
        reservation.status(),
        reservation.startAt(),
        reservation.endAt(),
        reservation.price().original(),
        reservation.price().payable(),
        reservation.waitlistId(),
        reservation.version());
  }
}
