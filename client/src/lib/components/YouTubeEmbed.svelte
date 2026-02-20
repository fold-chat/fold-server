<script lang="ts">
	let { videoId }: { videoId: string } = $props();

	let playing = $state(false);

	function load() {
		playing = true;
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			load();
		}
	}
</script>

<div class="youtube-embed">
	{#if playing}
		<iframe
			src="https://www.youtube-nocookie.com/embed/{videoId}?autoplay=1"
			title="YouTube video"
			frameborder="0"
			allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
			allowfullscreen
		></iframe>
	{:else}
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div
			class="youtube-facade"
			onclick={load}
			onkeydown={handleKeydown}
			role="button"
			tabindex="0"
			aria-label="Play YouTube video"
		>
			<img
				class="youtube-thumb"
				src="https://img.youtube.com/vi/{videoId}/hqdefault.jpg"
				alt="YouTube video thumbnail"
				loading="lazy"
			/>
			<div class="youtube-play">
				<svg viewBox="0 0 68 48" width="68" height="48">
					<path class="play-bg" d="M66.52 7.74c-.78-2.93-2.49-5.41-5.42-6.19C55.79.13 34 0 34 0S12.21.13 6.9 1.55C3.97 2.33 2.27 4.81 1.48 7.74.06 13.05 0 24 0 24s.06 10.95 1.48 16.26c.78 2.93 2.49 5.41 5.42 6.19C12.21 47.87 34 48 34 48s21.79-.13 27.1-1.55c2.93-.78 4.64-3.26 5.42-6.19C67.94 34.95 68 24 68 24s-.06-10.95-1.48-16.26z" fill="#212121" fill-opacity="0.8"/>
					<path d="M45 24L27 14v20" fill="#fff"/>
				</svg>
			</div>
		</div>
	{/if}
</div>

<style>
	.youtube-embed {
		position: relative;
		width: 100%;
		max-width: 480px;
		aspect-ratio: 16 / 9;
		border-radius: 8px;
		overflow: hidden;
		margin-top: 0.5rem;
		background: #000;
	}

	.youtube-embed iframe {
		position: absolute;
		top: 0;
		left: 0;
		width: 100%;
		height: 100%;
		border: none;
	}

	.youtube-facade {
		position: relative;
		width: 100%;
		height: 100%;
		cursor: pointer;
	}

	.youtube-thumb {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}

	.youtube-play {
		position: absolute;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		transition: opacity 0.15s;
	}

	.youtube-facade:hover .youtube-play .play-bg {
		fill: #f00;
		fill-opacity: 1;
	}
</style>
