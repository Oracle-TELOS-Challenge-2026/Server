package com.bintime.search.config;

import com.bintime.search.service.SearchFailure;
import com.bintime.user.contract.SearchUserAccess;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Component
public class SearchHttpGuard implements WebMvcConfigurer, HandlerInterceptor {
  private final SearchUserAccess users;
  private final String token;

  public SearchHttpGuard(SearchUserAccess users, @Value("${search.internal-token:}") String token) {
    this.users = users;
    this.token = token;
  }

  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(this)
        .addPathPatterns("/api/v1/search/**", "/api/v1/internal/search/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest r, HttpServletResponse s, Object handler) {
    if (r.getRequestURI().startsWith("/api/v1/internal/search/")) {
      String supplied = r.getHeader("X-Internal-Token");
      if (token.isBlank()
          || supplied == null
          || !MessageDigest.isEqual(
              token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
        throw new SearchFailure(HttpStatus.FORBIDDEN, "INTERNAL_AUTH_REQUIRED");
    } else if (!r.getRequestURI().equals("/api/v1/search/stores")) {
      r.setAttribute(
          "search.user",
          users.customer(r, r.getHeader("X-CSRF-Token"), !r.getMethod().equals("GET")));
    }
    return true;
  }
}
