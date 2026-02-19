/**
 * Compact theme exchange format: kt1.<base64url-name>.<base64url-payload>
 *
 * Payload (28 bytes):
 *   9 colors × 3 bytes (RGB) in fixed order: bg, bg-surface, bg-input,
 *   text, text-muted, accent, border, success, danger
 *   1 byte: radius (integer 0-255, capped to 0-20 on decode)
 */
import { hexToRgb } from './color.js';
import type { CustomTheme, CustomThemeColors } from '$lib/stores/theme.svelte.js';

const VERSION = 'kt1';

const COLOR_KEYS: (keyof CustomThemeColors)[] = [
	'bg', 'bg-surface', 'bg-input', 'text', 'text-muted',
	'accent', 'border', 'success', 'danger',
];

const PAYLOAD_BYTES = COLOR_KEYS.length * 3 + 1; // 28

function toBase64url(bytes: Uint8Array): string {
	let binary = '';
	for (const b of bytes) binary += String.fromCharCode(b);
	return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function fromBase64url(str: string): Uint8Array {
	const padded = str.replace(/-/g, '+').replace(/_/g, '/');
	const pad = (4 - (padded.length % 4)) % 4;
	return Uint8Array.from(atob(padded + '='.repeat(pad)), (c) => c.charCodeAt(0));
}

export function exportTheme(theme: CustomTheme): string {
	const bytes = new Uint8Array(PAYLOAD_BYTES);
	let i = 0;
	for (const key of COLOR_KEYS) {
		const rgb = hexToRgb(theme.colors[key]) ?? { r: 0, g: 0, b: 0 };
		bytes[i++] = rgb.r;
		bytes[i++] = rgb.g;
		bytes[i++] = rgb.b;
	}
	bytes[i] = Math.min(255, Math.max(0, parseInt(theme.colors.radius) || 8));
	const nameBytes = new TextEncoder().encode(theme.name.slice(0, 40));
	return `${VERSION}.${toBase64url(nameBytes)}.${toBase64url(bytes)}`;
}

export type DecodeResult = { theme: CustomTheme } | { error: string };

export function decodeTheme(str: string): DecodeResult {
	const parts = str.trim().split('.');
	if (parts.length !== 3) return { error: 'Must be in kt1.name.payload format' };
	if (parts[0] !== VERSION) return { error: `Unknown version "${parts[0]}"` };

	let nameBytes: Uint8Array;
	let payloadBytes: Uint8Array;
	try {
		nameBytes = fromBase64url(parts[1]);
		payloadBytes = fromBase64url(parts[2]);
	} catch {
		return { error: 'Corrupt data — could not decode' };
	}

	if (payloadBytes.length !== PAYLOAD_BYTES) {
		return { error: `Invalid payload length (got ${payloadBytes.length}, expected ${PAYLOAD_BYTES})` };
	}

	let name: string;
	try {
		name = new TextDecoder('utf-8', { fatal: true }).decode(nameBytes).trim().slice(0, 40) || 'Imported Theme';
	} catch {
		return { error: 'Invalid name encoding' };
	}

	const colors: Partial<CustomThemeColors> = {};
	let i = 0;
	for (const key of COLOR_KEYS) {
		const r = payloadBytes[i++], g = payloadBytes[i++], b = payloadBytes[i++];
		colors[key] = `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
	}
	colors.radius = `${Math.min(20, payloadBytes[i])}px`;

	return {
		theme: {
			id: crypto.randomUUID(),
			name,
			colors: colors as CustomThemeColors,
		},
	};
}
