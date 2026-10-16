package com.bintime.user.service;

import com.bintime.user.repository.UserAccountRepository;
import com.bintime.user.repository.UserAccountRepository.Account;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@Profile("!api-docs")
public class KakaoAccountService {
  private final UserAccountRepository accounts;

  public KakaoAccountService(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  public Account login(String subject, String nickname) {
    // 단일 INSERT는 자동 커밋한다. UNIQUE 충돌 후 같은 실패 트랜잭션에서 조회하지 않는다.
    return accounts.findByKakaoSubject(subject).orElseGet(() -> register(subject, nickname));
  }

  private Account register(String subject, String nickname) {
    String name = nickname == null || nickname.isBlank() ? "빈틈고객" : nickname.strip();
    name =
        name.substring(
            0, name.offsetByCodePoints(0, Math.min(30, name.codePointCount(0, name.length()))));
    try {
      return accounts.insert(UUID.randomUUID().toString(), subject, name);
    } catch (DuplicateKeyException duplicate) {
      // 같은 카카오 계정의 동시 첫 로그인은 DB의 UNIQUE 제약으로 한 사용자에 합친다.
      return accounts.findByKakaoSubject(subject).orElseThrow(() -> duplicate);
    }
  }
}
