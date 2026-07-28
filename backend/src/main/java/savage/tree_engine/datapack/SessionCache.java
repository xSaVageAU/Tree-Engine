package savage.tree_engine.datapack;

import savage.tree_engine.api.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds compiled datapacks in memory, bounded by count.
 *
 * A compiled registry set is large, so this deliberately keeps very few.
 * Eviction is not a correctness concern: a client whose session has been
 * evicted simply re-uploads the datapack and gets an identical id back,
 * because ids are content fingerprints.
 *
 * There is deliberately no time-to-live. This backend is a child process of
 * the editor and dies with it, so a session only ever has one user, and that
 * user expects the app to still work after they come back from lunch. A clock
 * bound could only ever evict a session someone was still using; the count
 * bound is what actually protects memory, and it evicts the least recently
 * used session rather than whichever one happens to be oldest.
 */
public final class SessionCache {
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

	/** Returns the session, or null if it is absent. */
	public synchronized Session get(String id) {
		return sessions.get(id);
	}

	/** Like {@link #get} but reports the failure the client should see. */
	public synchronized Session require(String id) {
		if (id == null || id.isBlank()) {
			throw ApiException.badRequest("Missing sessionId");
		}
		Session session = get(id);
		if (session == null) {
			throw ApiException.notFound(
				"Unknown session: " + id + " - re-send the datapack");
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
}
