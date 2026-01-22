import { marked } from 'marked';
import DOMPurify from 'dompurify';
import type { MentionedUser, MentionedRole } from '$lib/api/messages.js';

// Configure marked
marked.setOptions({
	breaks: true,
	gfm: true
});

/**
 * Pre-process message content to replace mention patterns with styled spans
 */
function processMentions(
	content: string,
	mentions?: MentionedUser[],
	mentionRoles?: MentionedRole[],
	mentionEveryone?: boolean
): string {
	let processed = content;

	// Replace user mentions: <@userId> -> <span class="mention" data-user-id="...">@displayName</span>
	if (mentions && mentions.length > 0) {
		const userMap = new Map(mentions.map(u => [u.id, u]));
		processed = processed.replace(/<@([a-f0-9-]{36})>/g, (match, userId) => {
			const user = userMap.get(userId);
			if (!user) return match; // Invalid mention stays as-is
			return `<span class="mention" data-user-id="${userId}">@${user.display_name}</span>`;
		});
	}

	// Replace role mentions: <@&roleId> -> <span class="mention mention-role" data-role-id="..." style="color:...">@roleName</span>
	if (mentionRoles && mentionRoles.length > 0) {
		const roleMap = new Map(mentionRoles.map(r => [r.id, r]));
		processed = processed.replace(/<@&([a-zA-Z0-9_-]+)>/g, (match, roleId) => {
			const role = roleMap.get(roleId);
			if (!role) return match;
			const style = role.color ? `style="color: ${role.color};"` : '';
			return `<span class="mention mention-role" data-role-id="${roleId}" ${style}>@${role.name}</span>`;
		});
	}

	// @everyone stays as-is (server already validated permission)

	return processed;
}

export function renderMarkdown(
	content: string,
	opts?: {
		mentions?: MentionedUser[];
		mention_roles?: MentionedRole[];
		mention_everyone?: boolean;
	}
): string {
	// Pre-process mentions before markdown
	const withMentions = processMentions(content, opts?.mentions, opts?.mention_roles, opts?.mention_everyone);

	const html = marked.parse(withMentions, { async: false }) as string;
	return DOMPurify.sanitize(html, {
		ALLOWED_TAGS: [
			'p', 'br', 'strong', 'em', 'del', 'code', 'pre', 'blockquote',
			'ul', 'ol', 'li', 'a', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
			'hr', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'span'
		],
		ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'class', 'target', 'rel', 'data-user-id', 'data-role-id', 'style'],
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
