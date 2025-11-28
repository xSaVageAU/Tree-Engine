package savage.tree_engine.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import savage.tree_engine.TreeEngine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP handler wrapper that enforces authentication.
 * Checks for valid authentication token before delegating to the wrapped handler.
 */
public class AuthFilter implements HttpHandler {
    private final HttpHandler wrappedHandler;
    
    public AuthFilter(HttpHandler handler) {
        this.wrappedHandler = handler;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Skip auth check if authentication is disabled
        if (!AuthenticationManager.isAuthEnabled()) {
            wrappedHandler.handle(exchange);
            return;
        }
        
        // Extract token from Authorization header
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = extractToken(authHeader);
        
        // Validate token
        if (AuthenticationManager.validateToken(token)) {
            // Token is valid, proceed to wrapped handler
            wrappedHandler.handle(exchange);
        } else {
            // Token is invalid or missing, return 401 Unauthorized
            TreeEngine.LOGGER.warn("Unauthorized API request to {} from {}", 
                exchange.getRequestURI().getPath(), 
                exchange.getRemoteAddress());
            sendUnauthorized(exchange);
        }
    }
    
    /**
     * Extract token from Authorization header.
     * Supports both "Bearer <token>" and plain "<token>" formats.
     */
    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isEmpty()) {
            return null;
        }
        
        // Support "Bearer <token>" format
        if (authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        
        // Support plain token format
        return authHeader.trim();
    }
    
    /**
     * Send 401 Unauthorized response with JSON error message.
     */
    private void sendUnauthorized(HttpExchange exchange) throws IOException {
        String errorJson = "{\"error\": \"Unauthorized\", \"message\": \"Valid authentication token required\"}";
        byte[] bytes = errorJson.getBytes(StandardCharsets.UTF_8);
        
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        exchange.sendResponseHeaders(401, bytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
