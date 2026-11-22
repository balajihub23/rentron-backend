package com.jcbbooking.websocket;

import com.jcbbooking.security.CustomUserDetailsService;
import com.jcbbooking.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final org.springframework.context.ApplicationContext applicationContext;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Pure WebSocket endpoints for native browser WebSocket clients
        registry.addEndpoint("/ws-rentron")
                .setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws-rentron/websocket")
                .setAllowedOriginPatterns("*");

        // SockJS endpoint for legacy fallback clients
        registry.addEndpoint("/ws-rentron-sockjs")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    List<String> authHeaders = accessor.getNativeHeader("Authorization");
                    String token = null;
                    if (authHeaders != null && !authHeaders.isEmpty()) {
                        String header = authHeaders.get(0);
                        if (header.startsWith("Bearer ")) {
                            token = header.substring(7);
                        } else {
                            token = header;
                        }
                    }
                    if (token == null) {
                        List<String> tokenHeaders = accessor.getNativeHeader("token");
                        if (tokenHeaders != null && !tokenHeaders.isEmpty()) {
                            token = tokenHeaders.get(0);
                        }
                    }

                    if (token != null && jwtTokenProvider.validateToken(token)) {
                        try {
                            String username = jwtTokenProvider.getUsernameFromJwt(token);
                            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                            UsernamePasswordAuthenticationToken auth =
                                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                            accessor.setUser(auth);
                            log.info("WebSocket STOMP connection authenticated successfully for user: {}", username);
                        } catch (Exception e) {
                            log.warn("Failed to set authenticated user on STOMP accessor: {}", e.getMessage());
                        }
                    } else {
                        log.debug("STOMP CONNECT without valid JWT token (anonymous session)");
                    }
                } else if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    String dest = accessor.getDestination();
                    if (dest != null && (dest.startsWith("/topic/booking-offers/") || dest.startsWith("/topic/user-events/"))) {
                        String targetId = dest.substring(dest.lastIndexOf('/') + 1);
                        java.security.Principal principal = accessor.getUser();
                        if (principal instanceof UsernamePasswordAuthenticationToken auth && auth.getPrincipal() instanceof com.jcbbooking.security.CustomUserDetails cud) {
                            boolean isAdmin = cud.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                            boolean isAllowed = isAdmin || cud.getId().toString().equals(targetId);

                            if (!isAllowed && dest.startsWith("/topic/booking-offers/")) {
                                try {
                                    Long targetDriverId = Long.parseLong(targetId);
                                    com.jcbbooking.repository.DriverRepository driverRepo =
                                            applicationContext.getBean(com.jcbbooking.repository.DriverRepository.class);
                                    isAllowed = driverRepo.findByUserId(cud.getId())
                                            .map(d -> d.getId().equals(targetDriverId))
                                            .orElse(false);
                                } catch (Exception e) {
                                    log.debug("Error checking driver permission for topic {}: {}", dest, e.getMessage());
                                }
                            }

                            if (!isAllowed) {
                                log.warn("AccessDenied: User {} tried to subscribe to unauthorized topic: {}", cud.getId(), dest);
                                throw new org.springframework.security.access.AccessDeniedException("Unauthorized subscription to topic " + dest);
                            }
                        } else {
                            log.warn("AccessDenied: Anonymous connection tried to subscribe to private topic: {}", dest);
                            throw new org.springframework.security.access.AccessDeniedException("Authentication required to subscribe to " + dest);
                        }
                    }
                }
                return message;
            }
        });
    }
}
