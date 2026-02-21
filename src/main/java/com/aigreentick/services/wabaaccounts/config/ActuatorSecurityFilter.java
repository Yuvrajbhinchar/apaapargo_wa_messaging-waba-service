package com.aigreentick.services.wabaaccounts.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;


@Component
@Order(1)
@Slf4j
public class ActuatorSecurityFilter extends OncePerRequestFilter {

    @Value("${server.port:8081}")
    private int applicationPort;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isActuatorPath(path)) {
            int requestPort = request.getServerPort();
            if (requestPort == applicationPort) {
                log.warn("Blocked actuator access attempt on application port: path={}, ip={}",
                        path, request.getRemoteAddr());
                response.setStatus(HttpStatus.NOT_FOUND.value());
                response.getWriter().write("{\"error\":\"Not Found\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isActuatorPath(String path) {
        return path != null && path.startsWith("/actuator");
    }
}