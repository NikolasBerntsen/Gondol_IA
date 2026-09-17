package com.gondolia.realtime;

import com.gondolia.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP sobre WebSocket nativo en {@code /ws} (sin SockJS) con broker simple en memoria (SPEC §7).
 * <p>
 * El handshake HTTP es público: la autenticación va en el frame CONNECT ({@link StompAuthChannelInterceptor}). Como el
 * token viaja en el frame y no en cookies, no hay riesgo de secuestro de sesión entre sitios; igualmente el handshake
 * solo acepta el mismo origen o los orígenes de CORS configurados ({@code APP_CORS_ALLOWED_ORIGINS}). En Docker nginx
 * quita el encabezado Origin de los pedidos del propio frontend.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final long HEARTBEAT_MILLIS = 10_000;

    private final StompAuthChannelInterceptor authChannelInterceptor;
    private final StompErrorHandler stompErrorHandler;
    private final StompSessionRegistry sessionRegistry;
    private final AppProperties properties;

    private TaskScheduler brokerTaskScheduler;

    /** Scheduler propio del broker de Spring para los heartbeats (no el de las tareas {@code @Scheduled}). */
    @Autowired
    public void setBrokerTaskScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.brokerTaskScheduler = scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(Destinations.ENDPOINT)
                .setAllowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new));
        registry.setErrorHandler(stompErrorHandler);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(brokerTaskScheduler);
        registry.setApplicationDestinationPrefixes(Destinations.APP_PREFIX);
        registry.setUserDestinationPrefix(Destinations.USER_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(64 * 1024)
                .setSendBufferSizeLimit(1024 * 1024)
                .setSendTimeLimit(20_000)
                .setTimeToFirstMessage(30_000)
                .addDecoratorFactory(sessionRegistry::decorate);
    }
}
