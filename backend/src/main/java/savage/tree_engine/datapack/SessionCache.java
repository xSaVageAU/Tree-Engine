package savage.tree_engine.datapack;

import savage.tree_engine.api.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Holds compiled datapacks in memory, bounded by both count and age.
 *
 * A compiled registry set is large, so this deliberately keeps very few.
 * Eviction is not a correctness concern: a client whose session has been
 * evicted simply re-uploads the datapack and gets an identical id back,
 * because ids are content fingerprints.
 */
public final class SessionCache {
	private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);

	private final int maxSessions;
	private final LinkedHashMap<String, Session> sessions;

	public SessionCache(int maxSessions) {
		this.maxSessions = Math.max(1, maxSessions);
		// Access-ordered, so the least recently *used* session is evicted -
		// not merely the oldest one.
		this.sessions = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
				return size() > SessionCache.this.maxSessions;
			}
		};
	}

	public synchronized void put(Session session) {
		sessions.put(session.id(), session);
	}

	public synchronized boolean contains(String id) {
		return get(id) != null;
	}

	/** Returns the session, or null if it is absent or expired. */
	public synchronized Session get(String id) {
		Session session = sessions.get(id);
		if (session == null) {
			return null;
		}
		if (isExpired(session)) {
			sessions.remove(id);
			return null;
		}
		return session;
	}

	/** Like {@link #get} but reports the failure the client should see. */
	public synchronized Session require(String id) {
		if (id == null || id.isBlank()) {
			throw ApiException.badRequest("Missing sessionId");
		}
		Session session = get(id);
		if (session == null) {
			throw ApiException.notFound(
				"Unknown or expired session: " + id + " - re-send the datapack");
		}
		return session;
	}

	public synchronized boolean remove(String id) {
		return sessions.remove(id) != null;
	}

	public synchronized int size() {
		return sessions.size();
	}

	public synchronized void clear() {
		sessions.clear();
	}

	private static boolean isExpired(Session session) {
		return System.currentTimeMillis() - session.createdAtMillis() > TTL_MILLIS;
	}
}
