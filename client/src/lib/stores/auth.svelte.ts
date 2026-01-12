import { getMe, type User } from '$lib/api/users.js';
import { getSetupStatus } from '$lib/api/auth.js';

let user = $state<User | null>(null);
let setupRequired = $state(false);
let initialized = $state(false);

export function getUser(): User | null {
	return user;
}

export function setUser(u: User | null) {
	user = u;
}

export function isAuthenticated(): boolean {
	return user !== null;
}

export function isSetupRequired(): boolean {
	return setupRequired;
}

export function isInitialized(): boolean {
	return initialized;
}

export async function init() {
	if (initialized) return;

	try {
		const status = await getSetupStatus();
		setupRequired = status.setup_required;

		if (!setupRequired) {
			try {
				user = await getMe();
			} catch {
				user = null;
			}
		}
	} catch {
		// Server unreachable
	}

	initialized = true;
}

export function reset() {
	user = null;
	initialized = false;
	setupRequired = false;
}
