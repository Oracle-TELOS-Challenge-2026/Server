package com.bintime.user.adapter;

import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.service.KakaoAccountService;
import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** Spring의 OAuth2UserService 경계를 통해 카카오 응답을 내부 계정으로 변환한다. */
public class KakaoOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
  private final KakaoAccountService accounts;

  public KakaoOAuth2UserService(DefaultOAuth2UserService delegate, KakaoAccountService accounts) {
    this.delegate = delegate;
    this.accounts = accounts;
  }

  @Override
  public OAuth2User loadUser(OAuth2UserRequest request) {
    try {
      OAuth2User user = delegate.loadUser(request);
      String subject = String.valueOf(user.getAttributes().get("id"));
      if (!subject.matches("[1-9][0-9]{0,19}")) {
        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"));
      }
      Object properties = user.getAttributes().get("properties");
      Object nickname = properties instanceof Map<?, ?> profile ? profile.get("nickname") : null;
      var account = accounts.login(subject, nickname instanceof String name ? name : null);
      return new BintimePrincipal(account.userId(), account.role());
    } catch (OAuth2AuthenticationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      // DB 장애도 OAuth 실패 경로로 보낸다. 공급자 토큰·응답은 브라우저에 노출하지 않는다.
      throw new OAuth2AuthenticationException(new OAuth2Error("login_failed"), exception);
    }
  }
}
