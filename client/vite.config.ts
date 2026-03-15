import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig, loadEnv, type Plugin } from 'vite';

function foldVersionComment(version: string): Plugin {
	return {
		name: 'fold-version-comment',
		transformIndexHtml(html) {
			return html.replace('<head>', `<head>\n\t\t<!-- fold:${version} -->`);
		}
	};
}

export default defineConfig(({ mode }) => {
	const env = loadEnv(mode, '..');
	const version = env.VITE_BUILD_VERSION || 'snapshot';
	return {
		plugins: [sveltekit(), foldVersionComment(version)],
		server: {
			proxy: {
				'/api': {
					target: 'http://localhost:8080',
					changeOrigin: true,
					ws: true
				}
			}
		}
	};
});
