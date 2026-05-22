package ch.uzh.ifi.hase.soprafs26.config;

import ch.uzh.ifi.hase.soprafs26.config.settings.ServerSettingsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long BROKER_HEARTBEAT_INTERVAL_MS = 20_000L;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final ServerSettingsProperties serverSettings;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor,
                           ServerSettingsProperties serverSettings) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.serverSettings = serverSettings;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Clients subscribe to topics here (server -> client)
        // queue is for private communication
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(websocketBrokerTaskScheduler())
                .setHeartbeatValue(new long[]{BROKER_HEARTBEAT_INTERVAL_MS, BROKER_HEARTBEAT_INTERVAL_MS});
        
        // Prefix for messages sent FROM client TO server
        registry.setApplicationDestinationPrefixes("/app");
        // for per-user messages
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-stomp")
                .setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    // register the interceptor for client -> server communication
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(serverSettings.getWebsocketInboundCorePoolSize())
                .maxPoolSize(serverSettings.getWebsocketInboundMaxPoolSize())
                .queueCapacity(serverSettings.getWebsocketInboundQueueCapacity());
        registration.interceptors(stompAuthChannelInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(serverSettings.getWebsocketOutboundCorePoolSize())
                .maxPoolSize(serverSettings.getWebsocketOutboundMaxPoolSize())
                .queueCapacity(serverSettings.getWebsocketOutboundQueueCapacity());
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setSendTimeLimit(serverSettings.getWebsocketSendTimeLimitMs())
                .setSendBufferSizeLimit(serverSettings.getWebsocketSendBufferSizeLimitBytes())
                .setMessageSizeLimit(serverSettings.getWebsocketMessageSizeLimitBytes());
    }

    @Bean(name = "websocketBrokerTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler websocketBrokerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, serverSettings.getWebsocketOutboundCorePoolSize()));
        scheduler.setThreadNamePrefix("ws-broker-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}

