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
