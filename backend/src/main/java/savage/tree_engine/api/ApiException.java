package savage.tree_engine.api;

/**
 * A failure with an HTTP status already decided. Handlers throw these instead
 * of writing error responses inline, so every route reports failures the same
 * way and no handler can accidentally return 200 on an error path.
 *
 * {@code detail} carries the machine-useful text (a codec error, usually)
 * that the editor surfaces to the user; {@code message} stays short.
 */
public class ApiException extends RuntimeException {
	private final int status;
	private final String detail;

	public ApiException(int status, String message, String detail, Throwable cause) {
		super(message, cause);
		this.status = status;
		this.detail = detail;
	}

	public int status() {
		return status;
	}

	public String detail() {
		return detail;
	}

	public static ApiException badRequest(String message) {
		return new ApiException(400, message, null, null);
	}

	public static ApiException badRequest(String message, String detail) {
		return new ApiException(400, message, detail, null);
	}

	public static ApiException notFound(String message) {
		return new ApiException(404, message, null, null);
	}

	public static ApiException methodNotAllowed() {
		return new ApiException(405, "Method not allowed", null, null);
	}

	public static ApiException payloadTooLarge(String message) {
		return new ApiException(413, message, null, null);
	}

	public static ApiException internal(String message, Throwable cause) {
		return new ApiException(500, message, cause.getMessage(), cause);
	}
}
