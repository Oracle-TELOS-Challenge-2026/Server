package com.bintime.user.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

final class KakaoAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
  static final String RETURN_TO = "bintime.oauth.returnTo";
  private final DefaultOAuth2AuthorizationRequestResolver delegate;
  private final KakaoLoginProperties properties;

  KakaoAuthorizationRequestResolver(
      ClientRegistrationRepository registrations, KakaoLoginProperties properties) {
    this.delegate =
        new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
    this.properties = properties;
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    if (!"GET".equals(request.getMethod())
        || !request.getServletPath().equals("/api/v1/auth/oauth/kakao/start")) {
      return null;
    }
    String returnTo = properties.returnPath(request.getParameter("returnTo"));
    request.getSession().setAttribute(RETURN_TO, returnTo);
    return delegate.resolve(request, "kakao");
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
    return resolve(request);
  }
}
