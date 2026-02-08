import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
	plugins: [svelte({ hot: false })],
	resolve: {
		conditions: ['browser'],
		alias: {
			$lib: path.resolve('./src/lib'),
			'$app/navigation': path.resolve('./src/test/mocks/navigation.ts')
		}
	},
	test: {
		environment: 'jsdom',
		include: ['src/**/*.test.ts'],
		globals: true,
		setupFiles: ['src/test/setup.ts']
	}
});
