let onlineUsers = $state(new Set<string>());

export function setOnlineUserIds(ids: string[]) {
	onlineUsers = new Set(ids);
}

export function setUserOnline(userId: string) {
	const next = new Set(onlineUsers);
	next.add(userId);
	onlineUsers = next;
}

export function setUserOffline(userId: string) {
	const next = new Set(onlineUsers);
	next.delete(userId);
	onlineUsers = next;
}

export function isUserOnline(userId: string): boolean {
	return onlineUsers.has(userId);
}

export function getOnlineCount(): number {
	return onlineUsers.size;
}
