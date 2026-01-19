import { searchMessages, type SearchResult, type SearchFilters } from '$lib/api/search.js';

let open = $state(false);
let query = $state('');
let filters = $state<SearchFilters>({});
let results = $state<SearchResult[]>([]);
let loading = $state(false);
let error = $state<string | null>(null);
let debounceTimer: ReturnType<typeof setTimeout> | null = null;

export function isSearchOpen(): boolean {
	return open;
}

export function openSearch() {
	open = true;
}

export function closeSearch() {
	open = false;
	query = '';
	filters = {};
	results = [];
	error = null;
	loading = false;
	if (debounceTimer) {
		clearTimeout(debounceTimer);
		debounceTimer = null;
	}
}

export function toggleSearch() {
	if (open) closeSearch();
	else openSearch();
}

export function getSearchQuery(): string {
	return query;
}

export function getSearchResults(): SearchResult[] {
	return results;
}

export function getSearchFilters(): SearchFilters {
	return filters;
}

export function isSearchLoading(): boolean {
	return loading;
}

export function getSearchError(): string | null {
	return error;
}

export function setSearchQuery(q: string) {
	query = q;
	debouncedSearch();
}

export function setSearchFilters(f: SearchFilters) {
	filters = { ...f };
	debouncedSearch();
}

export function updateFilter<K extends keyof SearchFilters>(key: K, value: SearchFilters[K]) {
	filters = { ...filters, [key]: value };
	debouncedSearch();
}

export function removeFilter(key: keyof SearchFilters) {
	const next = { ...filters };
	delete next[key];
	filters = next;
	debouncedSearch();
}

function debouncedSearch() {
	if (debounceTimer) clearTimeout(debounceTimer);
	debounceTimer = setTimeout(() => {
		debounceTimer = null;
		executeSearch();
	}, 300);
}

async function executeSearch() {
	const q = query.trim();
	if (!q) {
		results = [];
		error = null;
		loading = false;
		return;
	}

	loading = true;
	error = null;
	try {
		results = await searchMessages(q, filters);
	} catch (e: unknown) {
		const apiErr = e as { message?: string };
		error = apiErr.message ?? 'Search failed';
		results = [];
	} finally {
		loading = false;
	}
}
