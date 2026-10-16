package com.bintime.notification.subscription;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!api-docs")
public class SubscriptionSessionConfiguration {
  @Bean
  ServletListenerRegistrationBean<HttpSessionListener> subscriptionSessionListener(
      ObjectProvider<NotificationSubscriptions> subscriptions) {
    return new ServletListenerRegistrationBean<>(
        new HttpSessionListener() {
          @Override
          public void sessionDestroyed(HttpSessionEvent event) {
            subscriptions.getObject().releaseSession(event.getSession());
          }
        });
  }
}
