import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, screen, waitFor } from '@testing-library/svelte';
import LoginPage from './+page.svelte';

// Mock modules
vi.mock('$lib/api/auth.js', () => ({
	login: vi.fn()
}));
vi.mock('$lib/api/users.js', () => ({
	getMe: vi.fn()
}));
vi.mock('$lib/stores/auth.svelte.js', () => ({
	setUser: vi.fn()
}));

import { login } from '$lib/api/auth.js';
import type { ApiError } from '$lib/api/client.js';

const mockedLogin = vi.mocked(login);

async function submitLogin() {
	const usernameInput = screen.getByLabelText('Username');
	const passwordInput = screen.getByLabelText('Password');
	await fireEvent.input(usernameInput, { target: { value: 'testuser' } });
	await fireEvent.input(passwordInput, { target: { value: 'testpass' } });
	const form = usernameInput.closest('form')!;
	await fireEvent.submit(form);
}

describe('Login error messages', () => {
	beforeEach(() => {
		vi.clearAllMocks();
		// Mock window.location to avoid URL search param issues
		Object.defineProperty(window, 'location', {
			value: { search: '', pathname: '/login', href: '/login' },
			writable: true,
			configurable: true
		});
	});

	it('shows "Unable to reach server" on network failure (TypeError)', async () => {
		mockedLogin.mockRejectedValue(new TypeError('Failed to fetch'));

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			expect(screen.getByText('Unable to reach server. Please try again later.')).toBeInTheDocument();
		});
	});

	it('shows "Server error" on 500 status', async () => {
		const err: ApiError = { error: 'internal_error', status: 500 };
		mockedLogin.mockRejectedValue(err);

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			expect(screen.getByText('Server error. Please try again later.')).toBeInTheDocument();
		});
	});

	it('shows "Server error" on 502 status', async () => {
		const err: ApiError = { error: 'bad_gateway', status: 502 };
		mockedLogin.mockRejectedValue(err);

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			expect(screen.getByText('Server error. Please try again later.')).toBeInTheDocument();
		});
	});

	it('shows "Server error" on 503 status', async () => {
		const err: ApiError = { error: 'service_unavailable', status: 503 };
		mockedLogin.mockRejectedValue(err);

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			expect(screen.getByText('Server error. Please try again later.')).toBeInTheDocument();
		});
	});

	it('shows banned message on 403 banned error', async () => {
		const err: ApiError = { error: 'banned', status: 403 };
		mockedLogin.mockRejectedValue(err);

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			expect(screen.getByText('You have been banned from this server.')).toBeInTheDocument();
		});
	});

	it('shows account locked message with retry_after', async () => {
		const err: ApiError = { error: 'account_locked', retry_after: 60, status: 429 };
		mockedLogin.mockRejectedValue(err);

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			expect(screen.getByText('Account locked. Try again in 60 seconds.')).toBeInTheDocument();
		});
	});

	it('shows custom server message for 401 with message', async () => {
		const err: ApiError = { error: 'invalid_credentials', message: 'Wrong password', status: 401 };
		mockedLogin.mockRejectedValue(err);

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			expect(screen.getByText('Wrong password')).toBeInTheDocument();
		});
	});

	it('shows default "Invalid username or password." when no message provided', async () => {
		const err: ApiError = { error: 'invalid_credentials', status: 422 };
		mockedLogin.mockRejectedValue(err);

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			expect(screen.getByText('Invalid username or password.')).toBeInTheDocument();
		});
	});

	it('re-enables the submit button after error', async () => {
		mockedLogin.mockRejectedValue(new TypeError('Failed to fetch'));

		render(LoginPage);
		await submitLogin();

		await waitFor(() => {
			const button = screen.getByRole('button', { name: 'Sign in' });
			expect(button).not.toBeDisabled();
		});
	});
});
