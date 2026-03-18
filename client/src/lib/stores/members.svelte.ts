import type { Member } from '$lib/api/users.js';

let members = $state<Member[]>([]);

export function getMembers(): Member[] {
	return members;
}

export function setMembers(m: Member[]) {
	members = m;
}

export function addMember(m: Member) {
	members = [...members, m];
}

export function updateMember(m: Member) {
	members = members.map(existing => existing.id === m.id ? m : existing);
}

export function removeMember(id: string) {
	members = members.filter(m => m.id !== id);
}

const ROLE_PRIORITY: Record<string, number> = { owner: 0, admin: 1, moderator: 2, member: 3 };

/** Get top role color for a user by ID */
export function getMemberRoleColor(userId: string): string | null {
	const member = members.find(m => m.id === userId);
	if (!member) return null;
	const roles: import('$lib/api/users.js').RoleBadge[] = Array.isArray(member.roles) ? member.roles : [];
	if (roles.length === 0) return null;
	const top = [...roles].sort((a, b) => (ROLE_PRIORITY[a.name?.toLowerCase()] ?? 99) - (ROLE_PRIORITY[b.name?.toLowerCase()] ?? 99))[0];
	return top?.color ?? null;
}

/** Update a role badge (name/color) across all members who have that role */
export function updateMemberRoleBadge(roleId: string, name: string, color: string | null) {
	members = members.map(m => {
		const hasRole = m.roles.some(r => r.id === roleId);
		if (!hasRole) return m;
		return {
			...m,
			roles: m.roles.map(r => r.id === roleId ? { ...r, name, color } : r)
		};
	});
}
