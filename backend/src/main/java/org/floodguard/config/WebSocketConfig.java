package org.floodguard.config;

import lombok.RequiredArgsConstructor;
import org.floodguard.websocket.RiskSocket;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
  private final RiskSocket socket;

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(socket, "/ws/risk")
        .setAllowedOriginPatterns("http://localhost:5173", "http://localhost:8080");
  }
}
