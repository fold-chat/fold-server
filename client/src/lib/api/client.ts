const BASE = '/api/v0';

const PUBLIC_PATHS = ['/login', '/register', '/setup', '/invite'];
function isPublicPath(path: string): boolean {
	return PUBLIC_PATHS.some((p) => path === p || path.startsWith(p + '/'));
}

let isRefreshing = false;
let refreshPromise: Promise<boolean> | null = null;

export interface ApiError {
	error: string;
	message?: string;
	retry_after?: number;
	status?: number;
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
	const res = await fetch(`${BASE}${path}`, {
		credentials: 'same-origin',
		headers: {
			'Content-Type': 'application/json',
			...options.headers
		},
		...options
	});

	if (res.status === 503) {
		const body = await res.json().catch(() => ({ error: 'maintenance' })) as ApiError;
		body.status = res.status;
		if (body.error === 'maintenance') {
			if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/maintenance')) {
				window.location.href = '/maintenance';
			}
			throw body;
		}
		throw body;
	}

	if (res.status === 403) {
		const body = await res.json().catch(() => ({ error: 'forbidden' })) as ApiError;
		body.status = res.status;
		if (body.error === 'banned') {
			if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
				window.location.href = '/login?reason=banned';
			}
			throw body;
		}
		if (body.error === 'password_must_change') {
			if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/change-password')) {
				window.location.href = '/change-password';
			}
			throw body;
		}
		throw body;
	}

	if (res.status === 401) {
		// Auth endpoints handle their own 401s — don't refresh or redirect
		if (path.startsWith('/auth/')) {
			const err = (await res.json().catch(() => ({ error: 'invalid_credentials' }))) as ApiError;
			err.status = 401;
			throw err;
		}

		// Attempt refresh once
		const refreshed = await tryRefresh();
		if (refreshed) {
			const retry = await fetch(`${BASE}${path}`, {
				credentials: 'same-origin',
				headers: {
					'Content-Type': 'application/json',
					...options.headers
				},
				...options
			});
			if (retry.ok) {
				if (retry.status === 204) return undefined as T;
				return retry.json();
			}
		}
		// Refresh failed — redirect to login (skip for public routes)
		if (typeof window !== 'undefined' && !isPublicPath(window.location.pathname)) {
			window.location.href = '/login';
		}
		throw { error: 'authentication_required', status: 401 } as ApiError;
	}

	if (!res.ok) {
		const err = (await res.json().catch(() => ({ error: 'unknown' }))) as ApiError;
		err.status = res.status;
		throw err;
	}

	if (res.status === 204) return undefined as T;
	return res.json();
}

export async function apiRaw(path: string, options: RequestInit = {}): Promise<Response> {
	return fetch(`${BASE}${path}`, {
		credentials: 'same-origin',
		...options
	});
}

async function tryRefresh(): Promise<boolean> {
	if (isRefreshing) return refreshPromise!;

	isRefreshing = true;
	refreshPromise = (async () => {
		try {
			const res = await fetch(`${BASE}/auth/refresh`, {
				method: 'POST',
				credentials: 'same-origin',
				headers: { 'Content-Type': 'application/json' }
			});
			return res.ok;
		} catch {
			return false;
		} finally {
			isRefreshing = false;
			refreshPromise = null;
		}
	})();

	return refreshPromise;
}
