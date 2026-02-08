import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { api, type ApiError } from './client.js';

function mockFetch(status: number, body: Record<string, unknown> = {}) {
	return vi.fn().mockResolvedValue({
		ok: status >= 200 && status < 300,
		status,
		json: () => Promise.resolve(body)
	});
}

describe('api() error handling', () => {
	const originalFetch = globalThis.fetch;

	beforeEach(() => {
		// Prevent redirects in jsdom
		Object.defineProperty(window, 'location', {
			value: { pathname: '/login', href: '/login' },
			writable: true,
			configurable: true
		});
	});

	afterEach(() => {
		globalThis.fetch = originalFetch;
	});

	it('attaches status to 403 errors', async () => {
		globalThis.fetch = mockFetch(403, { error: 'forbidden', message: 'Forbidden' });

		try {
			await api('/test');
			expect.fail('should have thrown');
		} catch (err) {
			const apiErr = err as ApiError;
			expect(apiErr.status).toBe(403);
			expect(apiErr.error).toBe('forbidden');
		}
	});

	it('attaches status to 403 banned errors', async () => {
		globalThis.fetch = mockFetch(403, { error: 'banned' });

		try {
			await api('/test');
			expect.fail('should have thrown');
		} catch (err) {
			const apiErr = err as ApiError;
			expect(apiErr.status).toBe(403);
			expect(apiErr.error).toBe('banned');
		}
	});

	it('throws with status 401 on auth failure', async () => {
		// First call returns 401, refresh also fails
		const fetchMock = vi.fn()
			.mockResolvedValueOnce({ ok: false, status: 401, json: () => Promise.resolve({ error: 'unauthorized' }) })
			.mockResolvedValueOnce({ ok: false, status: 401, json: () => Promise.resolve({}) }); // refresh fails
		globalThis.fetch = fetchMock;

		try {
			await api('/test');
			expect.fail('should have thrown');
		} catch (err) {
			const apiErr = err as ApiError;
			expect(apiErr.status).toBe(401);
			expect(apiErr.error).toBe('authentication_required');
		}
	});

	it('attaches status to 500 server errors', async () => {
		globalThis.fetch = mockFetch(500, { error: 'internal_error' });

		try {
			await api('/test');
			expect.fail('should have thrown');
		} catch (err) {
			const apiErr = err as ApiError;
			expect(apiErr.status).toBe(500);
			expect(apiErr.error).toBe('internal_error');
		}
	});

	it('attaches status to 502 errors', async () => {
		globalThis.fetch = mockFetch(502, { error: 'bad_gateway' });

		try {
			await api('/test');
			expect.fail('should have thrown');
		} catch (err) {
			const apiErr = err as ApiError;
			expect(apiErr.status).toBe(502);
		}
	});

	it('attaches status to 422 validation errors', async () => {
		globalThis.fetch = mockFetch(422, { error: 'invalid_credentials', message: 'Invalid username or password' });

		try {
			await api('/test');
			expect.fail('should have thrown');
		} catch (err) {
			const apiErr = err as ApiError;
			expect(apiErr.status).toBe(422);
			expect(apiErr.error).toBe('invalid_credentials');
			expect(apiErr.message).toBe('Invalid username or password');
		}
	});

	it('falls back to { error: "unknown" } when JSON parsing fails on non-ok response', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue({
			ok: false,
			status: 503,
			json: () => Promise.reject(new Error('invalid json'))
		});

		try {
			await api('/test');
			expect.fail('should have thrown');
		} catch (err) {
			const apiErr = err as ApiError;
			expect(apiErr.status).toBe(503);
			expect(apiErr.error).toBe('unknown');
		}
	});
});
