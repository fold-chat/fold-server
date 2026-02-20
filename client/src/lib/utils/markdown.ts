import { marked } from 'marked';
import DOMPurify from 'dompurify';
import type { MentionedUser, MentionedRole } from '$lib/api/messages.js';
import { findCustomEmojiByName } from '$lib/stores/emoji.svelte.js';

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

	// Replace custom emoji shortcodes: :name: -> <img class="custom-emoji" ...>
	processed = processed.replace(/:([a-zA-Z0-9_]{2,32}):/g, (match, name) => {
		const emoji = findCustomEmojiByName(name.toLowerCase());
		if (!emoji) return match; // Not a known custom emoji, leave as-is
		return `<img class="custom-emoji" src="${emoji.url}" alt=":${emoji.name}:" title=":${emoji.name}:">`;
	});

	// Wrap unicode emoji in styled spans so they can be sized independently from text
	processed = processed.replace(
		/[\p{Emoji_Presentation}\p{Extended_Pictographic}][\u{FE0F}\u{20E3}\u{1F3FB}-\u{1F3FF}\u{200D}\p{Emoji_Presentation}\p{Extended_Pictographic}]*/gu,
		(match) => `<span class="unicode-emoji">${match}</span>`
	);

	return processed;
}

/** True when raw content is exclusively emoji (unicode and/or custom shortcodes). */
export function isEmojiOnly(content: string): boolean {
	let stripped = content;
	// Strip validated custom emoji shortcodes
	stripped = stripped.replace(/:([a-zA-Z0-9_]{2,32}):/g, (match, name) => {
		return findCustomEmojiByName(name.toLowerCase()) ? '' : match;
	});
	// Strip unicode emoji (incl. ZWJ sequences, skin tones, variation selectors)
	stripped = stripped.replace(/[\p{Emoji_Presentation}\p{Extended_Pictographic}][\u{FE0F}\u{20E3}\u{1F3FB}-\u{1F3FF}\u{200D}\p{Emoji_Presentation}\p{Extended_Pictographic}]*/gu, '');
	return stripped.trim() === '' && content.trim().length > 0;
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
		ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'class', 'target', 'rel', 'data-user-id', 'data-role-id', 'style', 'data-emoji-name'],
		ADD_ATTR: ['target']
	});
}

const GIF_MSG_RE = /!\[GIF\]\([^)]+\)/g;

/** Matches YouTube URLs and extracts video IDs. Supports youtube.com/watch, youtu.be, and embed URLs. */
const YOUTUBE_URL_RE = /(?:https?:\/\/)?(?:www\.|m\.)?(?:youtube\.com\/watch\?[^\s]*v=|youtu\.be\/|youtube\.com\/embed\/)([a-zA-Z0-9_-]{11})(?:[^\s]*)/g;

/** Extract unique YouTube video IDs from raw message content. */
export function extractYouTubeVideoIds(content: string): string[] {
	const ids = new Set<string>();
	let match;
	while ((match = YOUTUBE_URL_RE.exec(content)) !== null) {
		ids.add(match[1]);
	}
	YOUTUBE_URL_RE.lastIndex = 0;
	return [...ids];
}

/** Strip markdown artifacts from content for plain-text previews. */
export function contentPreview(content: string | undefined | null, maxLen = 200, mentions?: MentionedUser[]): string {
	if (!content) return '(attachment)';
	let cleaned = content.replace(GIF_MSG_RE, '[GIF]').trim();
	if (!cleaned) return '[GIF]';
	if (mentions && mentions.length > 0) {
		const userMap = new Map(mentions.map(u => [u.id, u]));
		cleaned = cleaned.replace(/<@([a-f0-9-]{36})>/g, (match, userId) => {
			const user = userMap.get(userId);
			return user ? `@${user.display_name}` : match;
		});
	}
	return cleaned.length > maxLen ? cleaned.slice(0, maxLen) : cleaned;
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
