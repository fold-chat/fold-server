const clientVersion: string = import.meta.env.VITE_BUILD_VERSION || 'snapshot';

let serverVersion = $state<string | null>(null);
let mismatchDismissed = $state(false);

export function getClientVersion(): string {
	return clientVersion;
}

export function getServerVersion(): string | null {
	return serverVersion;
}

export function setServerVersion(v: string) {
	serverVersion = v;
	mismatchDismissed = false;
}

export function isVersionMismatch(): boolean {
	return serverVersion !== null && serverVersion !== clientVersion;
}

export function isMismatchDismissed(): boolean {
	return mismatchDismissed;
}

export function dismissMismatch() {
	mismatchDismissed = true;
}
