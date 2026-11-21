package org.floodguard;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Component
class RiskSocket extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    @Override public void afterConnectionEstablished(WebSocketSession session) { sessions.add(session); }
    @Override public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) { sessions.remove(session); }
    void publish(String json) {
        for (WebSocketSession session : sessions) try {
            if (session.isOpen()) synchronized(session) { session.sendMessage(new TextMessage(json)); }
        } catch (IOException ex) { sessions.remove(session); }
    }
}

@Configuration
public class LiveUpdates implements WebSocketConfigurer {
    private final RiskSocket socket;
    LiveUpdates(RiskSocket socket) { this.socket = socket; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws/risk").setAllowedOriginPatterns("http://localhost:5173", "http://localhost:8080");
    }
}
