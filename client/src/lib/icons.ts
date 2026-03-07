export interface IconGroup {
	label: string;
	icons: string[];
}

export const ICON_GROUPS: IconGroup[] = [
	{
		label: 'General',
		icons: ['tag', 'home', 'star', 'bookmark', 'lightbulb', 'info', 'help', 'flag']
	},
	{
		label: 'Communication',
		icons: ['forum', 'chat', 'campaign', 'mail', 'notifications', 'record_voice_over']
	},
	{
		label: 'Media',
		icons: ['headphones', 'mic', 'videocam', 'photo_camera', 'music_note', 'movie']
	},
	{
		label: 'Dev',
		icons: ['code', 'terminal', 'bug_report', 'build', 'science', 'data_object']
	},
	{
		label: 'Gaming',
		icons: [
			'sports_esports',
			'stadia_controller',
			'casino',
			'extension',
			'token',
			'sword_rose',
			'strategy',
			'trophy'
		]
	},
	{
		label: 'Fun',
		icons: [
			'local_cafe',
			'restaurant',
			'palette',
			'brush',
			'school',
			'pets',
			'rocket_launch'
		]
	}
];

export const ALL_ICONS: string[] = ICON_GROUPS.flatMap((g) => g.icons);

export const DEFAULT_ICONS: Record<string, string> = {
	TEXT: 'tag',
	THREAD_CHANNEL: 'forum',
	VOICE: 'mic'
};
