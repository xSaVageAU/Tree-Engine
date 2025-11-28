package savage.tree_engine.web;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for validating file paths and preventing path traversal attacks.
 * Ensures all file operations stay within allowed directories.
 */
public class PathValidator {
    
    /**
     * Validates that a requested path stays within the allowed base directory.
     * Prevents path traversal attacks including URL-encoded variants.
     * 
     * @param requestedPath The path requested by the user
     * @param allowedBaseDir The base directory that must contain the path
     * @return true if the path is safe, false if it attempts traversal
     */
    public static boolean isPathSafe(Path requestedPath, Path allowedBaseDir) {
        try {
            // Normalize and resolve to absolute paths
            Path normalizedRequested = requestedPath.normalize().toAbsolutePath();
            Path normalizedBase = allowedBaseDir.normalize().toAbsolutePath();
            
            // Check if the requested path starts with the base directory
            return normalizedRequested.startsWith(normalizedBase);
        } catch (Exception e) {
            // If any error occurs during path resolution, consider it unsafe
            return false;
        }
    }
    
    /**
     * Validates that a requested path stays within the allowed base directory.
     * String variant that converts to Path objects.
     * 
     * @param requestedPath The path requested by the user (as string)
     * @param allowedBaseDir The base directory that must contain the path
     * @return true if the path is safe, false if it attempts traversal
     */
    public static boolean isPathSafe(String requestedPath, Path allowedBaseDir) {
        try {
            Path path = Paths.get(requestedPath);
            return isPathSafe(path, allowedBaseDir);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Checks if a string contains path traversal sequences.
     * Detects both standard and URL-encoded variants.
     * 
     * @param input The input string to check
     * @return true if traversal detected, false if safe
     */
    public static boolean containsTraversalSequence(String input) {
        if (input == null) {
            return false;
        }
        
        String lower = input.toLowerCase();
        
        // Check for standard traversal
        if (lower.contains("..")) {
            return true;
        }
        
        // Check for URL-encoded traversal variants
        // %2e = .
        // %2f = /
        // %5c = \
        if (lower.contains("%2e%2e") ||      // ..
            lower.contains("%252e") ||        // Double-encoded .
            lower.contains("..%2f") ||        // ../
            lower.contains("..%5c") ||        // ..\
            lower.contains("%2e%2e%2f") ||    // ../
            lower.contains("%2e%2e%5c")) {    // ..\
            return true;
        }
        
        // Check for mixed encoding
        if (lower.contains(".%2e") ||
            lower.contains("%2e.")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Sanitizes a file name by removing path components.
     * Only keeps the actual filename, removes any directory traversal.
     * 
     * @param fileName The file name to sanitize
     * @return Sanitized filename, or null if invalid
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        
        // Check for traversal sequences
        if (containsTraversalSequence(fileName)) {
            return null;
        }
        
        // Remove any path separators - keep only the filename
        String sanitized = fileName;
        
        // Handle both Unix and Windows separators
        int lastSlash = Math.max(
            sanitized.lastIndexOf('/'),
            sanitized.lastIndexOf('\\')
        );
        
        if (lastSlash >= 0) {
            sanitized = sanitized.substring(lastSlash + 1);
        }
        
        // Remove null bytes and other dangerous characters
        sanitized = sanitized.replace("\0", "")
                           .replace("\n", "")
                           .replace("\r", "");
        
        // Must not be empty after sanitization
        if (sanitized.isEmpty()) {
            return null;
        }
        
        // Must not be a hidden file (starting with .)
        if (sanitized.startsWith(".")) {
            return null;
        }
        
        return sanitized;
    }
    
    /**
     * Validates and resolves a safe path within a base directory.
     * Returns the resolved path if safe, throws exception if unsafe.
     * 
     * @param fileName The file name to resolve
     * @param baseDir The base directory
     * @return The safely resolved path
     * @throws SecurityException if path traversal is detected
     */
    public static Path resolveSafePath(String fileName, Path baseDir) throws SecurityException {
        // Sanitize the filename first
        String sanitized = sanitizeFileName(fileName);
        if (sanitized == null) {
            throw new SecurityException("Invalid file name: " + fileName);
        }
        
        // Resolve against base directory
        Path resolved = baseDir.resolve(sanitized);
        
        // Verify it stays within base directory
        if (!isPathSafe(resolved, baseDir)) {
            throw new SecurityException("Path traversal detected: " + fileName);
        }
        
        return resolved;
    }

    /**
     * Validates and resolves a safe RELATIVE path within a base directory.
     * Allows subdirectories (e.g. "css/main.css") but prevents traversal (e.g. "../config.json").
     * 
     * @param relativePath The relative path to resolve
     * @param baseDir The base directory
     * @return The safely resolved path
     * @throws SecurityException if path traversal is detected
     */
    public static Path resolveSafeRelativePath(String relativePath, Path baseDir) throws SecurityException {
        if (relativePath == null || relativePath.isEmpty()) {
            throw new SecurityException("Empty path");
        }

        // Check for traversal sequences in the raw string
        if (containsTraversalSequence(relativePath)) {
            throw new SecurityException("Path traversal detected: " + relativePath);
        }
        
        // Resolve against base directory
        // We do NOT sanitize slashes here because we want to allow subdirectories
        Path resolved = baseDir.resolve(relativePath);
        
        // Verify it stays within base directory
        if (!isPathSafe(resolved, baseDir)) {
            throw new SecurityException("Path traversal detected (escaped base dir): " + relativePath);
        }
        
        return resolved;
    }
}
