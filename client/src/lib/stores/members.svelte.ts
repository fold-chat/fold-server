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
