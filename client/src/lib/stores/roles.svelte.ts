import type { Role } from '$lib/api/roles.js';

let roles = $state<Map<string, Role>>(new Map());

export function getRoles(): Map<string, Role> {
	return roles;
}

export function getRolesList(): Role[] {
	return [...roles.values()].sort((a, b) => a.position - b.position);
}

export function getRole(id: string): Role | undefined {
	return roles.get(id);
}

export function setRoles(list: Role[]) {
	roles = new Map(list.map((r) => [r.id, r]));
}

export function addRole(role: Role) {
	roles = new Map(roles);
	roles.set(role.id, role);
}

export function updateRole(role: Role) {
	roles = new Map(roles);
	roles.set(role.id, role);
}

export function removeRole(id: string) {
	roles = new Map(roles);
	roles.delete(id);
}
