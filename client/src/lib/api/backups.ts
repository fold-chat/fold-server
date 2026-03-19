import { api } from './client.js';

export interface BackupMetadata {
	filename: string;
	timestamp: string;
	areas: string[];
	size_bytes: number;
	db_encrypted: boolean;
	download_url: string;
	restore_note?: string;
	password_note?: string;
}

export interface BackupListEntry {
	filename: string;
	size_bytes: number;
	created_at: string;
}

export interface SizeEstimate {
	database: number;
	files: number;
	emojis: number;
}

export function getBackupEstimate() {
	return api<SizeEstimate>('/admin/backups/estimate');
}

export function listBackups() {
	return api<BackupListEntry[]>('/admin/backups');
}

export function createBackup(areas: string[], password?: string) {
	return api<BackupMetadata>('/admin/backups', {
		method: 'POST',
		body: JSON.stringify({ areas, password: password || null })
	});
}

export function deleteBackup(filename: string) {
	return api<void>('/admin/backups/' + encodeURIComponent(filename), {
		method: 'DELETE'
	});
}

export function downloadBackupUrl(filename: string) {
	return '/api/v0/admin/backups/' + encodeURIComponent(filename);
}
