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
        // The standalone launcher's frontend (a Wails webview, a distinct
        // origin from this server) calls /api/* directly, so every response -
        // including preflight - needs CORS headers. "*" is safe here: this
        // server only ever binds to 127.0.0.1 and every request still needs
        // the auth token, so an open origin doesn't widen access.
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        // Preflight requests never carry the Authorization header (browsers
        // deliberately omit custom headers on the preflight itself), so this
        // must be answered before any auth check or it always fails.
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

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
