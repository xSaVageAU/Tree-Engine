package savage.tree_engine.web;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Utility class for validating and sanitizing user inputs.
 * Prevents injection attacks, path traversal, and malformed data.
 */
public class InputValidator {
    
    // Tree IDs must be alphanumeric with underscores only
    private static final Pattern VALID_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_]+$");
    
    // Maximum JSON payload size (10MB)
    private static final int MAX_JSON_SIZE_BYTES = 10 * 1024 * 1024;
    
    /**
     * Validates a tree ID to ensure it contains only safe characters.
     * Prevents path traversal and injection attacks.
     * 
     * @param id The tree ID to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidTreeId(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        
        // Check length (reasonable limit)
        if (id.length() > 100) {
            return false;
        }
        
        // Must match alphanumeric + underscore pattern
        return VALID_IDENTIFIER.matcher(id).matches();
    }
    
    /**
     * Validates a generic identifier (tree ID, replacer ID, etc.)
     * 
     * @param identifier The identifier to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidIdentifier(String identifier) {
        return isValidTreeId(identifier);
    }
    
    /**
     * Validates JSON payload size to prevent memory exhaustion.
     * 
     * @param json The JSON string to validate
     * @return true if size is acceptable, false if too large
     */
    public static boolean isValidJsonSize(String json) {
        if (json == null) {
            return false;
        }
        
        int sizeBytes = json.getBytes(StandardCharsets.UTF_8).length;
        return sizeBytes <= MAX_JSON_SIZE_BYTES;
    }
    
    /**
     * Sanitizes a file name by removing dangerous characters.
     * Prevents path traversal and other file system attacks.
     * 
     * @param fileName The file name to sanitize
     * @return Sanitized file name, or null if invalid
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        
        // Remove path separators and dangerous characters
        String sanitized = fileName.replace("..", "")
                                   .replace("/", "")
                                   .replace("\\", "")
                                   .replace("\0", "")
                                   .replace("\n", "")
                                   .replace("\r", "");
        
        // Must not be empty after sanitization
        if (sanitized.isEmpty()) {
            return null;
        }
        
        // Must not start with a dot (hidden files)
        if (sanitized.startsWith(".")) {
            return null;
        }
        
        return sanitized;
    }
    
    /**
     * Validates that a string doesn't contain path traversal sequences.
     * 
     * @param input The input to check
     * @return true if safe, false if contains traversal sequences
     */
    public static boolean containsPathTraversal(String input) {
        if (input == null) {
            return false;
        }
        
        // Check for common path traversal patterns
        return input.contains("..") ||
               input.contains("./") ||
               input.contains(".\\") ||
               input.contains("%2e%2e") ||
               input.contains("%252e") ||
               input.contains("..%2f") ||
               input.contains("..%5c");
    }
    
    /**
     * Gets the maximum allowed JSON size in bytes.
     * 
     * @return Maximum JSON size
     */
    public static int getMaxJsonSizeBytes() {
        return MAX_JSON_SIZE_BYTES;
    }
}
