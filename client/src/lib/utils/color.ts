export function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
	const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
	if (!m) return null;
	return { r: parseInt(m[1], 16), g: parseInt(m[2], 16), b: parseInt(m[3], 16) };
}

function clamp(v: number): string {
	return Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0');
}

export function darkenHex(hex: string, factor: number): string {
	const rgb = hexToRgb(hex);
	if (!rgb) return hex;
	return `#${clamp(rgb.r * (1 - factor))}${clamp(rgb.g * (1 - factor))}${clamp(rgb.b * (1 - factor))}`;
}

export function isColorDark(hex: string): boolean {
	const rgb = hexToRgb(hex);
	if (!rgb) return true;
	// Perceived luminance
	return (0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b) / 255 < 0.5;
}
