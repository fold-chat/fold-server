import { marked } from 'marked';
import DOMPurify from 'dompurify';

// Configure marked
marked.setOptions({
	breaks: true,
	gfm: true
});

export function renderMarkdown(content: string): string {
	const html = marked.parse(content, { async: false }) as string;
	return DOMPurify.sanitize(html, {
		ALLOWED_TAGS: [
			'p', 'br', 'strong', 'em', 'del', 'code', 'pre', 'blockquote',
			'ul', 'ol', 'li', 'a', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
			'hr', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'span'
		],
		ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'class', 'target', 'rel'],
		ADD_ATTR: ['target']
	});
}

export function formatTimestamp(dateStr: string): string {
	const date = new Date(dateStr.endsWith('Z') ? dateStr : dateStr + 'Z');
	const now = new Date();
	const diff = now.getTime() - date.getTime();
	const dayMs = 86400000;

	if (diff < dayMs && date.getDate() === now.getDate()) {
		return `Today at ${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
	}

	const yesterday = new Date(now.getTime() - dayMs);
	if (diff < 2 * dayMs && date.getDate() === yesterday.getDate()) {
		return `Yesterday at ${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
	}

	return date.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' }) +
		' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
