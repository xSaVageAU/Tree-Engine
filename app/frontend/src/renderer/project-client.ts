// Project persistence, via the Go side.
//
// The backend mod owns no files, so everything that reads or writes the
// user's project goes through Wails rather than HTTP. Keeping these wrappers
// in one module means components never import generated bindings directly and
// the session handling below has a single home.

import {
	CloseProject,
	DeleteReplacer,
	DeleteTree,
	GetPlacement,
	GetProjectDatapack,
	GetTree,
	ListReplacers,
	ListTrees,
	OpenProjectFolder,
	OpenRecentProject,
	SavePlacement,
	SaveReplacer,
	SaveTree,
} from '../../wailsjs/go/main/App'
import type { main } from '../../wailsjs/go/models'
import { createSession, type ModConnection } from './mod-client'

// --- trees ----------------------------------------------------------------

export async function listTrees(): Promise<string[]> {
	return ListTrees()
}

export async function getTree(id: string): Promise<string> {
	return GetTree(id)
}

export async function saveTree(id: string, featureJson: string): Promise<void> {
	await SaveTree(id, featureJson)
	invalidateSession()
}

export async function deleteTree(id: string): Promise<void> {
	await DeleteTree(id)
	invalidateSession()
}

export async function getPlacement(id: string): Promise<string> {
	return GetPlacement(id)
}

export async function savePlacement(id: string, placementJson: string): Promise<void> {
	await SavePlacement(id, placementJson)
	invalidateSession()
}

// --- replacers ------------------------------------------------------------

// A replacer makes a vanilla tree generate one of yours instead. It is written
// as a real datapack file (a random_selector shadowing the vanilla id), so it
// works in game and shows up in chunk previews rather than being a runtime
// trick that vanishes on restart.
export interface ReplacerEntry {
	feature: string
	// 0-1. The last entry is the fallback and its chance is ignored.
	chance: number
}

export interface Replacer {
	vanillaId: string
	entries: ReplacerEntry[]
	// How the editor presented this pool. Purely a UI memory - the generated
	// datapack is a random_selector either way.
	mode?: 'weighted' | 'simple'
}

export async function listReplacers(): Promise<Replacer[]> {
	return (await ListReplacers()) as unknown as Replacer[]
}

export async function saveReplacer(replacer: Replacer): Promise<void> {
	await SaveReplacer(replacer as never)
	invalidateSession()
}

export async function deleteReplacer(vanillaId: string): Promise<void> {
	await DeleteReplacer(vanillaId)
	invalidateSession()
}

// --- opening and closing projects ----------------------------------------

// Project switching goes through here rather than calling the bindings
// directly, so dropping the cached session cannot be forgotten at one of the
// several places a project can change. A stale session would silently preview
// the previous project's datapack.

export async function openProjectFolder(): Promise<main.ProjectInfo> {
	resetSession()
	return OpenProjectFolder()
}

export async function openRecentProject(path: string): Promise<main.ProjectInfo> {
	resetSession()
	return OpenRecentProject(path)
}

export async function closeProject(): Promise<void> {
	resetSession()
	return CloseProject()
}

// --- sessions -------------------------------------------------------------

let cachedSessionId: string | null = null
let stale = true
let inFlight: Promise<string | null> | null = null

// Marks the cached session as out of date. Every write above calls this, so
// the next preview rebuilds instead of resolving against the old datapack.
export function invalidateSession(): void {
	stale = true
}

// Forgets the session entirely - call when the open project changes, so a
// preview can never be served against the previous project's datapack.
export function resetSession(): void {
	cachedSessionId = null
	stale = true
	inFlight = null
}

// Returns a session id to preview against, or null when the project has
// nothing to upload.
//
// Null is a normal answer, not a failure. A project with no saved trees has no
// files under data/, and a preview of an inline feature that only references
// vanilla needs no session at all - the backend falls back to the server's own
// registries. Treating the empty project as an error is what previously made
// previews stop working entirely until any file was saved.
//
// This deliberately does not re-read the project on every call. It used to,
// and since previews run on a 300ms debounce while typing, that meant a full
// disk read of the datapack per keystroke - enough to make preview timing
// visibly erratic. This app is the only writer, so invalidating on write is
// both cheaper and just as correct.
//
// Concurrent callers share one upload rather than racing to start several.
export async function ensureSession(conn: ModConnection): Promise<string | null> {
	if (!stale) {
		return cachedSessionId
	}
	if (inFlight) {
		return inFlight
	}

	inFlight = (async () => {
		const files = await GetProjectDatapack()
		if (Object.keys(files).length === 0) {
			cachedSessionId = null
			stale = false
			return null
		}
		const session = await createSession(conn, files)
		cachedSessionId = session.sessionId
		stale = false
		return session.sessionId
	})()

	try {
		return await inFlight
	} finally {
		inFlight = null
	}
}
