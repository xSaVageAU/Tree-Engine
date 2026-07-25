<script lang="ts" module>
	// Inline SVG icon set. Kept local (rather than an icon package) so the app
	// stays dependency-free and fully offline; every glyph is drawn on the same
	// 24x24 grid with a 1.75 stroke so they optically match at small sizes.
	// Icons are stroked with currentColor unless they opt into `solid`.
	const PATHS: Record<string, string> = {
		// Brand: a two-tier conifer.
		tree: '<path d="M12 2.5 6.4 11h11.2z"/><path d="M12 8 4.8 18.2h14.4z"/><path d="M12 18.2v3.3"/>',
		play: '<path d="M8 5.4 19 12 8 18.6z" fill="currentColor" stroke-linejoin="round"/>',
		stop: '<rect x="6.5" y="6.5" width="11" height="11" rx="2" fill="currentColor"/>',
		terminal: '<rect x="2.75" y="4.75" width="18.5" height="14.5" rx="2.5"/><path d="M7 9.5 10 12l-3 2.5"/><path d="M12.5 15h4.5"/>',
		folder:
			'<path d="M3 7.5A2.5 2.5 0 0 1 5.5 5h3.2a2 2 0 0 1 1.5.7l1 1.2h7.3A2.5 2.5 0 0 1 21 9.4v7.1a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16.5z"/>',
		sliders:
			'<path d="M4 7h8M16.5 7H20M4 12h3.5M12 12h8M4 17h8M16.5 17H20"/><circle cx="14.25" cy="7" r="2.25"/><circle cx="9.75" cy="12" r="2.25"/><circle cx="14.25" cy="17" r="2.25"/>',
		close: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
		plus: '<path d="M12 5.5v13M5.5 12h13"/>',
		import: '<path d="M12 3.5v10.5M12 14l3.75-3.75M12 14 8.25 10.25"/><path d="M4 16.5v2A2.5 2.5 0 0 0 6.5 21h11a2.5 2.5 0 0 0 2.5-2.5v-2"/>',
		shuffle:
			'<path d="M3 6.5h3.2a3 3 0 0 1 2.5 1.35l5.6 8.3A3 3 0 0 0 16.8 17.5H21"/><path d="M3 17.5h3.2a3 3 0 0 0 2.5-1.35l1.5-2.2"/><path d="M13.8 9.85l1-1.5A3 3 0 0 1 17.3 7H21"/><path d="M18.2 4.2 21 7l-2.8 2.8"/><path d="M18.2 14.7 21 17.5l-2.8 2.8"/>',
		search: '<circle cx="10.75" cy="10.75" r="6.25"/><path d="M15.4 15.4 20.5 20.5"/>',
		back: '<path d="M14.5 5.5 8 12l6.5 6.5"/>',
		orbit:
			'<ellipse cx="12" cy="12" rx="9.25" ry="4.25" transform="rotate(-32 12 12)"/><circle cx="12" cy="12" r="2.6" fill="currentColor" stroke="none"/>',
		grid: '<rect x="3.75" y="3.75" width="16.5" height="16.5" rx="2"/><path d="M3.75 9.25h16.5M3.75 14.75h16.5M9.25 3.75v16.5M14.75 3.75v16.5"/>',
		code: '<path d="M8.5 8 4 12l4.5 4"/><path d="M15.5 8 20 12l-4.5 4"/><path d="M13.6 4.75 10.4 19.25"/>',
		refresh: '<path d="M20.5 12a8.5 8.5 0 1 1-2.49-6.01"/><path d="M20.5 3.2v5.3h-5.3"/>',
		bolt: '<path d="M13.2 2.5 4.8 13.6h5.7l-.7 7.9 8.4-11.1h-5.7z" stroke-linejoin="round"/>',
		save: '<path d="M4.75 3.75h11.1L20.25 8.1v12.15a1 1 0 0 1-1 1H4.75a1 1 0 0 1-1-1V4.75a1 1 0 0 1 1-1z"/><path d="M7.75 3.75v5h7.5v-5"/><path d="M7.75 21.25v-6.5h8.5v6.5"/>',
		trash: '<path d="M3.75 6.75h16.5"/><path d="M9 6.75V5.25a1.5 1.5 0 0 1 1.5-1.5h3a1.5 1.5 0 0 1 1.5 1.5v1.5"/><path d="M6.25 6.75 7.1 19.4a1.9 1.9 0 0 0 1.9 1.85h6a1.9 1.9 0 0 0 1.9-1.85l.85-12.65"/><path d="M10.25 10.75v6M13.75 10.75v6"/>',
		check: '<path d="M4.75 12.5 9.75 17.5 19.25 6.5"/>',
		alert: '<path d="M12 3.5 21.5 20.25H2.5z" stroke-linejoin="round"/><path d="M12 9.75v4.5"/><path d="M12 17.4v.35"/>',
		spinner: '<path d="M12 3.25v4M12 16.75v4M20.75 12h-4M7.25 12h-4M18.19 5.81l-2.83 2.83M8.64 15.36l-2.83 2.83M18.19 18.19l-2.83-2.83M8.64 8.64 5.81 5.81" opacity="0.85"/>',
		info: '<circle cx="12" cy="12" r="8.75"/><path d="M12 11.25v5"/><path d="M12 7.9v.35"/>',
		layers: '<path d="M12 3 21 7.75 12 12.5 3 7.75z" stroke-linejoin="round"/><path d="M3 12.5 12 17.25 21 12.5"/><path d="M3 16.75 12 21.5 21 16.75"/>',
	}
</script>

<script lang="ts">
	let {
		name,
		size = 16,
		class: klass = '',
	}: { name: keyof typeof PATHS | string; size?: number; class?: string } = $props()
</script>

<svg
	class="icon {klass}"
	width={size}
	height={size}
	viewBox="0 0 24 24"
	fill="none"
	stroke="currentColor"
	stroke-width="1.75"
	stroke-linecap="round"
	stroke-linejoin="round"
	aria-hidden="true"
	focusable="false">{@html PATHS[name] ?? ''}</svg
>

<style>
	.icon {
		flex-shrink: 0;
		display: block;
	}
</style>
