package com.soarer.alert.config;

import com.soarer.alert.controller.websocket.DiagnosisControlWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * DiagnosisWebSocketConfig Spring 配置。
 */
@Configuration
@EnableWebSocket
public class DiagnosisWebSocketConfig implements WebSocketConfigurer {

    private final DiagnosisControlWebSocketHandler diagnosisControlWebSocketHandler;
    private final DiagnosisWebSocketAuthInterceptor authInterceptor;
    private final CorsProperties corsProperties;

    public DiagnosisWebSocketConfig(
            DiagnosisControlWebSocketHandler diagnosisControlWebSocketHandler,
            DiagnosisWebSocketAuthInterceptor authInterceptor,
            CorsProperties corsProperties
    ) {
        this.diagnosisControlWebSocketHandler = diagnosisControlWebSocketHandler;
        this.authInterceptor = authInterceptor;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(diagnosisControlWebSocketHandler, "/ws/diagnosis")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns(corsProperties.getAllowedOrigins().toArray(String[]::new));
    }
}
