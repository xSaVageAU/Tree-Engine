package savage.tree_engine.web;

import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.MainConfig;

import java.security.SecureRandom;

/**
 * Manages authentication tokens for the web server.
 * Generates cryptographically secure tokens and validates incoming requests.
 */
public class AuthenticationManager {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits
    
    /**
     * Initialize authentication system.
     * Generates a new token if one doesn't exist.
     */
    public static void initialize() {
        MainConfig config = MainConfig.get();
        
        if (config.auth_token == null || config.auth_token.isEmpty()) {
            String newToken = generateToken();
            config.auth_token = newToken;
            MainConfig.save();
            TreeEngine.LOGGER.info("Generated new authentication token");
        }
        
        if (config.auth_enabled) {
            TreeEngine.LOGGER.info("=".repeat(60));
            TreeEngine.LOGGER.info("WEB EDITOR AUTHENTICATION TOKEN:");
            TreeEngine.LOGGER.info(config.auth_token);
            TreeEngine.LOGGER.info("=".repeat(60));
            TreeEngine.LOGGER.info("Copy this token to access the web editor");
        } else {
            TreeEngine.LOGGER.warn("Authentication is DISABLED - web editor is unprotected!");
        }
    }
    
    /**
     * Generate a cryptographically secure random token.
     * @return Hex-encoded token string
     */
    public static String generateToken() {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(tokenBytes);
        return bytesToHex(tokenBytes);
    }
    
    /**
     * Validate an incoming token against the stored token.
     * @param token Token to validate
     * @return true if token is valid, false otherwise
     */
    public static boolean validateToken(String token) {
        MainConfig config = MainConfig.get();
        
        // If auth is disabled, allow all requests
        if (!config.auth_enabled) {
            return true;
        }
        
        // Null or empty tokens are invalid
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        // Constant-time comparison to prevent timing attacks
        return constantTimeEquals(token, config.auth_token);
    }
    
    /**
     * Check if authentication is enabled.
     * @return true if authentication is enabled
     */
    public static boolean isAuthEnabled() {
        return MainConfig.get().auth_enabled;
    }
    
    /**
     * Regenerate the authentication token.
     * @return New token
     */
    public static String regenerateToken() {
        String newToken = generateToken();
        MainConfig config = MainConfig.get();
        config.auth_token = newToken;
        MainConfig.save();
        
        TreeEngine.LOGGER.info("Regenerated authentication token: {}", newToken);
        return newToken;
    }
    
    /**
     * Convert byte array to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Constant-time string comparison to prevent timing attacks.
     * This ensures that the comparison takes the same amount of time
     * regardless of where the strings differ.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        
        if (a.length() != b.length()) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        
        return result == 0;
    }
}
