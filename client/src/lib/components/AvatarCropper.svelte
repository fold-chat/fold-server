<script lang="ts">
	let {
		imageFile,
		onCrop,
		onCancel
	}: {
		imageFile: File;
		onCrop: (croppedFile: File) => void;
		onCancel: () => void;
	} = $props();

	let canvasEl = $state<HTMLCanvasElement | null>(null);
	let imgEl = $state<HTMLImageElement | null>(null);
	let containerEl = $state<HTMLDivElement | null>(null);

	// Crop state
	let cropX = $state(0);
	let cropY = $state(0);
	let cropSize = $state(0);
	let dragging = $state(false);
	let dragStartX = $state(0);
	let dragStartY = $state(0);
	let dragStartCropX = $state(0);
	let dragStartCropY = $state(0);

	// Image dimensions in display space
	let imgNatW = $state(0);
	let imgNatH = $state(0);
	let displayW = $state(0);
	let displayH = $state(0);
	let offsetX = $state(0);
	let offsetY = $state(0);
	let scale = $state(1);

	const imageUrl = $derived(URL.createObjectURL(imageFile));

	function handleImageLoad(e: Event) {
		const img = e.target as HTMLImageElement;
		imgNatW = img.naturalWidth;
		imgNatH = img.naturalHeight;

		// Fit image into a 400x400 display area
		const maxDisplay = 400;
		const aspect = imgNatW / imgNatH;
		if (aspect >= 1) {
			displayW = maxDisplay;
			displayH = maxDisplay / aspect;
		} else {
			displayH = maxDisplay;
			displayW = maxDisplay * aspect;
		}
		scale = imgNatW / displayW;

		offsetX = (maxDisplay - displayW) / 2;
		offsetY = (maxDisplay - displayH) / 2;

		// Default crop: centered square, as large as possible
		const minDim = Math.min(displayW, displayH);
		cropSize = minDim;
		cropX = offsetX + (displayW - minDim) / 2;
		cropY = offsetY + (displayH - minDim) / 2;
	}

	function handleMouseDown(e: MouseEvent) {
		dragging = true;
		dragStartX = e.clientX;
		dragStartY = e.clientY;
		dragStartCropX = cropX;
		dragStartCropY = cropY;
	}

	function handleMouseMove(e: MouseEvent) {
		if (!dragging) return;
		const dx = e.clientX - dragStartX;
		const dy = e.clientY - dragStartY;

		// Clamp crop area within the image bounds
		let newX = dragStartCropX + dx;
		let newY = dragStartCropY + dy;

		newX = Math.max(offsetX, Math.min(newX, offsetX + displayW - cropSize));
		newY = Math.max(offsetY, Math.min(newY, offsetY + displayH - cropSize));

		cropX = newX;
		cropY = newY;
	}

	function handleMouseUp() {
		dragging = false;
	}

	function handleWheel(e: WheelEvent) {
		e.preventDefault();
		const minDim = Math.min(displayW, displayH);
		const delta = e.deltaY > 0 ? -10 : 10;
		let newSize = cropSize + delta;
		newSize = Math.max(50, Math.min(newSize, minDim));

		// Keep centered
		const centerX = cropX + cropSize / 2;
		const centerY = cropY + cropSize / 2;
		let newX = centerX - newSize / 2;
		let newY = centerY - newSize / 2;

		newX = Math.max(offsetX, Math.min(newX, offsetX + displayW - newSize));
		newY = Math.max(offsetY, Math.min(newY, offsetY + displayH - newSize));

		cropSize = newSize;
		cropX = newX;
		cropY = newY;
	}

	async function handleCrop() {
		const canvas = document.createElement('canvas');
		const outputSize = 256;
		canvas.width = outputSize;
		canvas.height = outputSize;
		const ctx = canvas.getContext('2d');
		if (!ctx) return;

		const img = new Image();
		img.src = imageUrl;
		await new Promise<void>((resolve) => {
			img.onload = () => resolve();
		});

		// Convert display crop coords to natural image coords
		const natCropX = (cropX - offsetX) * scale;
		const natCropY = (cropY - offsetY) * scale;
		const natCropSize = cropSize * scale;

		ctx.drawImage(img, natCropX, natCropY, natCropSize, natCropSize, 0, 0, outputSize, outputSize);

		canvas.toBlob((blob) => {
			if (blob) {
				const croppedFile = new File([blob], 'avatar.png', { type: 'image/png' });
				onCrop(croppedFile);
			}
		}, 'image/png');
	}
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="cropper-overlay">
	<div class="cropper-modal">
		<h3>Crop Avatar</h3>
		<p class="hint">Drag to move. Scroll to resize.</p>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div
			class="cropper-container"
			bind:this={containerEl}
			onmousemove={handleMouseMove}
			onmouseup={handleMouseUp}
			onmouseleave={handleMouseUp}
			onwheel={handleWheel}
		>
			<img
				src={imageUrl}
				alt="Preview"
				class="cropper-image"
				style="width: {displayW}px; height: {displayH}px; left: {offsetX}px; top: {offsetY}px;"
				bind:this={imgEl}
				onload={handleImageLoad}
				draggable="false"
			/>
			{#if cropSize > 0}
				<!-- Darkened overlay regions -->
				<div class="crop-darken" style="top: 0; left: 0; width: 400px; height: {cropY}px;"></div>
				<div class="crop-darken" style="top: {cropY + cropSize}px; left: 0; width: 400px; height: {400 - cropY - cropSize}px;"></div>
				<div class="crop-darken" style="top: {cropY}px; left: 0; width: {cropX}px; height: {cropSize}px;"></div>
				<div class="crop-darken" style="top: {cropY}px; left: {cropX + cropSize}px; width: {400 - cropX - cropSize}px; height: {cropSize}px;"></div>

				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<div
					class="crop-selection"
					style="left: {cropX}px; top: {cropY}px; width: {cropSize}px; height: {cropSize}px;"
					onmousedown={handleMouseDown}
				>
					<div class="crop-border"></div>
				</div>
			{/if}
		</div>
		<div class="cropper-actions">
			<button class="btn-cancel" onclick={onCancel}>Cancel</button>
			<button class="btn-crop" onclick={handleCrop}>Crop & Upload</button>
		</div>
	</div>
</div>

<style>
	.cropper-overlay {
		position: fixed;
		top: 0;
		left: 0;
		right: 0;
		bottom: 0;
		background: rgba(0, 0, 0, 0.7);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1000;
	}

	.cropper-modal {
		background: var(--bg-surface, #1e1e2e);
		border-radius: 12px;
		padding: 1.5rem;
		max-width: 450px;
		width: 100%;
	}

	.cropper-modal h3 {
		margin: 0 0 0.25rem;
		font-size: 1.1rem;
		color: var(--text);
	}

	.hint {
		margin: 0 0 0.75rem;
		font-size: 0.8rem;
		color: var(--text-muted);
	}

	.cropper-container {
		position: relative;
		width: 400px;
		height: 400px;
		overflow: hidden;
		background: #000;
		border-radius: 8px;
		margin: 0 auto;
		user-select: none;
	}

	.cropper-image {
		position: absolute;
		pointer-events: none;
	}

	.crop-darken {
		position: absolute;
		background: rgba(0, 0, 0, 0.6);
		pointer-events: none;
	}

	.crop-selection {
		position: absolute;
		cursor: move;
		border-radius: 50%;
		overflow: hidden;
	}

	.crop-border {
		width: 100%;
		height: 100%;
		border: 2px solid white;
		border-radius: 50%;
		box-sizing: border-box;
	}

	.cropper-actions {
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
		margin-top: 1rem;
	}

	.btn-cancel {
		padding: 0.5rem 1rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 6px;
		cursor: pointer;
		font-size: 0.875rem;
	}

	.btn-cancel:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.btn-crop {
		padding: 0.5rem 1rem;
		border: none;
		background: var(--accent, #5865f2);
		color: white;
		border-radius: 6px;
		cursor: pointer;
		font-size: 0.875rem;
		font-weight: 600;
	}

	.btn-crop:hover {
		opacity: 0.9;
	}
</style>
