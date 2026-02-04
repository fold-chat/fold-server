#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import Database from "better-sqlite3";
import argon2 from "argon2";
import { v4 as uuidv4, v7 as uuidv7 } from "uuid";
import { faker } from "@faker-js/faker";
import { z } from "zod";
import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DB_PATH =
  process.env.KITH_DB_PATH ||
  path.resolve(__dirname, "../../../server/kith.db");

function getDb(): InstanceType<typeof Database> {
  if (!fs.existsSync(DB_PATH)) {
    throw new Error(
      `Database not found at ${DB_PATH}. Set KITH_DB_PATH env var or start the Kith server first.`
    );
  }
  const db = new Database(DB_PATH);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  return db;
}

const server = new McpServer({
  name: "kith-db",
  version: "1.0.0",
});

// ── Query (read-only) ───────────────────────────────────────────────

server.tool(
  "query",
  "Execute a read-only SQL query against the Kith database",
  { sql: z.string().describe("SQL SELECT query") },
  async ({ sql }) => {
    const db = getDb();
    try {
      const rows = db.prepare(sql).all();
      return {
        content: [{ type: "text", text: JSON.stringify(rows, null, 2) }],
      };
    } finally {
      db.close();
    }
  }
);

// ── Execute (write) ─────────────────────────────────────────────────

server.tool(
  "execute",
  "Execute a write SQL statement (INSERT/UPDATE/DELETE)",
  { sql: z.string().describe("SQL statement") },
  async ({ sql }) => {
    const db = getDb();
    try {
      const result = db.prepare(sql).run();
      return {
        content: [{ type: "text", text: `Changes: ${result.changes}` }],
      };
    } finally {
      db.close();
    }
  }
);

// ── Seed Users ──────────────────────────────────────────────────────

server.tool(
  "seed_users",
  "Bulk-create dummy users with Argon2id password hashes and 'member' role",
  {
    count: z
      .number()
      .min(1)
      .max(1000)
      .describe("Number of users to create"),
    password: z
      .string()
      .optional()
      .describe("Shared password for all seed users (default: 'password123')"),
  },
  async ({ count, password }) => {
    const pw = password ?? "password123";

    // Hash once — all seed users share the same hash (fast)
    const hash = await argon2.hash(pw, {
      type: argon2.argon2id,
      memoryCost: 65536,
      timeCost: 3,
      parallelism: 1,
      hashLength: 32,
    });

    const db = getDb();
    try {
      const insertUser = db.prepare(
        `INSERT INTO user (id, username, display_name, password_hash, bio)
         VALUES (?, ?, ?, ?, ?)`
      );
      const assignRole = db.prepare(
        "INSERT OR IGNORE INTO user_role (user_id, role_id) VALUES (?, 'member')"
      );

      // Collect existing usernames to avoid collisions
      const existing = new Set(
        (
          db.prepare("SELECT username FROM user").all() as {
            username: string;
          }[]
        ).map((u) => u.username.toLowerCase())
      );

      const created: { id: string; username: string; display_name: string }[] =
        [];

      db.transaction(() => {
        for (let i = 0; i < count; i++) {
          let username: string;
          do {
            username = faker.internet
              .username()
              .replace(/[^a-zA-Z0-9_-]/g, "_")
              .slice(0, 32);
            if (username.length < 2)
              username = `user_${faker.string.alphanumeric(6)}`;
          } while (existing.has(username.toLowerCase()));
          existing.add(username.toLowerCase());

          const id = uuidv4();
          const displayName = faker.person.fullName();
          const bio = faker.person.bio();

          insertUser.run(id, username, displayName, hash, bio);
          assignRole.run(id);
          created.push({ id, username, display_name: displayName });
        }
      })();

      const preview = created
        .slice(0, 10)
        .map((u) => `  ${u.username} — ${u.display_name}`)
        .join("\n");
      return {
        content: [
          {
            type: "text",
            text: `Created ${created.length} users (pw: "${pw}")\n\n${preview}${created.length > 10 ? `\n  ... and ${created.length - 10} more` : ""}`,
          },
        ],
      };
    } finally {
      db.close();
    }
  }
);

// ── Chat message pools (realistic English) ─────────────────────────

const CHAT: Record<string, string[]> = {
  general: [
    "hey everyone",
    "good morning!",
    "anyone around?",
    "what's everyone up to today?",
    "just got back from lunch, what did I miss?",
    "that's a great point actually",
    "yeah I agree with that",
    "lol",
    "haha nice",
    "wait really?",
    "oh interesting, I didn't know that",
    "can someone explain that to me?",
    "thanks for sharing!",
    "I was just thinking about the same thing",
    "how's everyone doing?",
    "happy friday!",
    "monday mood honestly",
    "who's around this weekend?",
    "just saw this and thought of you all",
    "that makes a lot of sense",
    "I'm not sure I agree, but I see your point",
    "has anyone else noticed this?",
    "oh wow I had no idea",
    "this is exactly what I was looking for",
    "I think we should do a community event soon",
    "what does everyone think about that?",
    "couldn't agree more",
    "that's hilarious",
    "ok so hear me out on this one",
    "can we talk about this for a second?",
    "I've been thinking about this all day",
    "honestly this community is great",
    "hey does anyone have experience with this?",
    "just dropping in to say hi",
    "brb grabbing coffee",
    "back!",
    "alright who wants to start a debate",
    "not gonna lie, that's pretty cool",
    "anyone else having a slow day?",
    "this weather is insane right now",
    "I just learned something wild, check this out",
    "that's a hot take but I respect it",
    "wait I think I missed something, can someone fill me in?",
    "yo that's actually genius",
    "ok I need opinions on something",
    "anyone tried that new restaurant downtown?",
    "I keep forgetting to check this channel lol",
    "ok but why is nobody talking about this",
    "just wanted to share some good news - got promoted today!",
    "congrats!! that's awesome",
    "late to the conversation but +1 on that",
    "I respectfully disagree here",
    "solid point, hadn't thought of it that way",
    "does anyone else feel like time is flying?",
    "welp, there goes my evening plans",
    "ngl that caught me off guard",
  ],
  introductions: [
    "Hey everyone! Just joined, excited to be here",
    "Hi! Found this community through a friend, looking forward to getting to know everyone",
    "New here! Been looking for a place like this for a while",
    "Hey, I'm new! What do I need to know about this server?",
    "Just signed up, this place seems really cool",
    "Hi all! Longtime lurker, finally made an account",
    "Hey! Came over from Discord, liking this so far",
    "Welcome! Always nice to see new faces around here",
    "Hey welcome! You'll love it here",
    "Another new member? We're growing fast!",
    "Welcome to the community! Feel free to jump into any channel",
    "Nice to meet you! What brought you here?",
    "Glad to have you! Don't be shy, we're all friendly",
    "Welcome aboard! Check out #general to get started",
    "Hey new friend! Hope you enjoy it here",
  ],
  help: [
    "hey quick question - how do I change my profile picture?",
    "is there a way to mute specific channels?",
    "having trouble with notifications, anyone else?",
    "how do I create an invite link?",
    "can someone help me figure out the voice chat?",
    "is there a dark mode? my eyes are dying",
    "how do I format text in messages?",
    "try refreshing the page, that usually fixes it",
    "check your settings, there should be an option for that",
    "I had the same issue, restarting fixed it for me",
    "that's a known bug, should be fixed soon",
    "have you tried clearing your cache?",
    "go to settings > profile to change that",
    "yeah that happened to me too, just give it a minute",
    "oh I think you need to update first",
    "works fine for me, might be a browser thing?",
    "anyone know if there's keyboard shortcuts?",
    "what's the max file size for uploads?",
    "is there a way to search old messages?",
    "thanks that fixed it!",
  ],
  dev: [
    "anyone working on anything cool right now?",
    "just pushed a fix for that nasty race condition",
    "been refactoring all day and I'm finally happy with the architecture",
    "what's everyone's preferred language these days?",
    "Rust or Go for the backend? serious question",
    "TypeScript is the way, fight me",
    "I swear CSS is harder than any systems language",
    "just discovered this library and it's a game changer",
    "does anyone use Vim or am I the only one left",
    "ok hot take: tests slow you down more than they help on small projects",
    "nah tests save you, I've been burned too many times",
    "been playing with the new React compiler, thoughts?",
    "Docker compose for local dev is so underrated",
    "just spent 3 hours debugging a typo in an env variable. AMA",
    "what's the best way to handle auth in a side project?",
    "JWT vs sessions, here we go again",
    "anyone have a good CI/CD setup they want to share?",
    "the new Svelte updates are looking really nice",
    "is anyone else obsessed with making their terminal look good?",
    "just wrote my first Rust program and I'm hooked",
    "linting rules are either too strict or not strict enough, no in between",
    "can someone explain WebSockets to me like I'm 5",
    "SQLite is genuinely all you need for most projects",
    "GraphQL or REST? I keep going back and forth",
    "pair programming session anyone?",
    "the amount of time I spend on tooling vs actual code is embarrassing",
    "finally got GitHub Actions working the way I want",
    "monorepo or polyrepo? discuss",
    "just open sourced my side project, check it out if you want",
    "code reviews should be mandatory even for solo devs, change my mind",
  ],
  "off-topic": [
    "ok what's everyone watching right now?",
    "just finished that new show on Netflix and WOW",
    "anyone into board games? looking for recommendations",
    "coffee or tea? wrong answers only",
    "what's for dinner tonight?",
    "I just adopted a cat and I'm in love",
    "going hiking this weekend, any trail suggestions?",
    "does anyone else have strong opinions about pizza toppings?",
    "pineapple on pizza is valid and I will die on this hill",
    "just got back from vacation and I already want another one",
    "it's 2am and I'm still on here, what is wrong with me",
    "anyone else a night owl?",
    "been getting into cooking lately, made pasta from scratch",
    "what song is stuck in your head right now?",
    "I need book recommendations, what are you all reading?",
    "the sunrise this morning was incredible",
    "anyone follow F1? that race was insane",
    "trying to get back into working out, any tips?",
    "just discovered a new coffee shop near me and it's amazing",
    "who's a morning person here? I need your secrets",
    "random thought: why do we park in driveways and drive on parkways",
    "I spent way too much money on plants this month",
    "movie night? what should we watch?",
    "the audacity of my alarm clock this morning",
    "anyone else doom scrolling or just me?",
  ],
  gaming: [
    "what's everyone playing right now?",
    "just got destroyed in ranked, I need a break",
    "anyone want to squad up tonight?",
    "the new update is actually really good",
    "ok that boss fight was NOT fair",
    "just 100%'d it, took me like 80 hours",
    "anyone tried the new indie game that just dropped?",
    "my backlog is out of control at this point",
    "steam sale incoming, RIP my wallet",
    "controller or mouse and keyboard? go",
    "I've been on a retro gaming kick lately",
    "that trailer looked insane, can't wait",
    "who wants to do a game night this weekend?",
    "I keep dying at the same part, any tips?",
    "this game's soundtrack is unreal",
    "just built a new PC and everything runs so smooth",
    "cozy games > competitive games, don't @ me",
    "anyone else excited for that release next month?",
    "I should not have started a new save at midnight",
    "gg everyone, that was a good session",
  ],
  music: [
    "what are you all listening to today?",
    "just discovered this artist and I can't stop listening",
    "anyone going to any concerts soon?",
    "drop your favorite album of the year so far",
    "this song has been on repeat all week",
    "anyone else make playlists for literally everything?",
    "live music > recorded music, always",
    "just started learning guitar, wish me luck",
    "vinyl or streaming? both are valid honestly",
    "that new album is a masterpiece, no cap",
    "music recommendations please, I'm in a rut",
    "anyone into jazz? need some chill vibes",
    "the acoustics at that venue are incredible",
    "headphones on, world off",
    "what's your go-to coding music?",
    "lo-fi beats or synthwave for focus?",
    "I have such a soft spot for 90s music",
    "just learned a new song on piano, feeling proud",
    "anyone want to share their Spotify wrapped?",
    "that bassline is absolutely filthy",
  ],
  announcements: [
    "Hey everyone! We've added some new channels based on your feedback",
    "Reminder: please keep conversations respectful and on-topic",
    "Welcome to all our new members this week!",
    "We hit 100 members! Thanks for being part of this community",
    "New feature: you can now react to messages with emoji",
    "Scheduled maintenance this weekend, might be brief downtime",
    "Community game night this Saturday at 8pm, be there!",
    "We're looking for moderators, DM me if interested",
    "Updated the server rules, please give them a read",
    "Thanks for all the great conversations lately, you all rock",
  ],
};

const SHORT_REACTIONS = [
  "lol", "haha", "nice", "true", "facts", "this", "same",
  "agreed", "100%", "absolutely", "nah", "idk", "maybe",
  "fair enough", "good point", "big mood", "mood", "relatable",
  "oof", "rip", "F", "let's go!", "W", "based", "valid",
];

const THREAD_REPLIES = [
  "totally agree with this",
  "interesting take, hadn't thought about it that way",
  "can you elaborate on that?",
  "+1",
  "following this thread",
  "wait, really? that's wild",
  "oh that makes sense now",
  "I had the same experience actually",
  "good point, I'll give that a try",
  "thanks for the tip!",
  "this is super helpful",
  "yeah I've been dealing with the same thing",
  "lol same",
  "oh nice, thanks for sharing",
  "hmm I'm not sure about that",
  "that's exactly what I was thinking",
  "wait can you explain that again?",
  "this cleared things up for me, thanks",
  "yep confirmed this works",
  "anyone else tried this?",
  "great suggestion",
  "I disagree actually, here's why...",
  "oh wow didn't know that was possible",
  "just tested this and it works!",
  "solid advice, appreciate it",
  "haha yeah been there",
  "this deserves more attention",
  "can confirm, same thing happened to me",
  "that's a really good point",
  "oh interesting, I'll try that",
  "wait hold on, what about...",
  "not gonna lie this is brilliant",
  "saving this for later",
  "thanks, this was exactly what I needed",
  "tbh I didn't even know about that",
];

const EMOJI_REACTIONS = [
  "👍", "❤️", "😂", "🔥", "😮", "👀", "🎉", "💯",
  "🤔", "😢", "✅", "💀", "🙌", "👏", "🫡", "🤣", "😍", "🥳",
];

// Fake but structurally valid klipy proxy URLs for GIF messages
const GIF_IDS = [
  "aV2DXjYv0X", "bK3mNpY8zR", "cQ9wLxJ2aT", "dF5gHnV7kM",
  "eW1rUsB4pN", "fZ6tOiC8qL", "gX0yEdA3sJ", "hR7vPwF9uK",
  "iN4jQhG1mD", "jL8kTbH5oE", "kM2lYcI6wF", "lP9nZdJ0xG",
  "mS3oWfK7yH", "nU5pXgL2zI", "oV1qYhM8aJ", "pW4rZiN3bK",
];

function makeGifMessage(): string {
  const id = GIF_IDS[Math.floor(Math.random() * GIF_IDS.length)];
  const rawUrl = `https://media.klipy.com/media/${id}/gif`;
  return `![GIF](/api/v0/media/proxy?url=${encodeURIComponent(rawUrl)})`;
}

function pickMessage(channelName: string, users: { id: string; username: string }[]): string {
  const key = Object.keys(CHAT).find((k) =>
    channelName.toLowerCase().includes(k)
  );
  const pool = key ? CHAT[key] : CHAT.general;

  // 5% chance of GIF message
  if (Math.random() < 0.05) return makeGifMessage();

  // 20% chance of a short reaction instead of a pool message
  if (Math.random() < 0.2) {
    return SHORT_REACTIONS[Math.floor(Math.random() * SHORT_REACTIONS.length)];
  }

  // 10% chance of mentioning another user
  let msg = pool[Math.floor(Math.random() * pool.length)];
  if (Math.random() < 0.1 && users.length > 1) {
    const mentioned = users[Math.floor(Math.random() * users.length)];
    msg = `@${mentioned.username} ${msg.charAt(0).toLowerCase()}${msg.slice(1)}`;
  }

  return msg;
}

function pickThreadReply(): string {
  if (Math.random() < 0.1) return makeGifMessage();
  return THREAD_REPLIES[Math.floor(Math.random() * THREAD_REPLIES.length)];
}

// ── Seed Channels ───────────────────────────────────────────────────

const DEFAULT_CHANNELS = [
  { name: "general", topic: "Everyday conversation", category: "Community" },
  { name: "introductions", topic: "Say hi and tell us about yourself", category: "Community" },
  { name: "off-topic", topic: "Anything goes", category: "Community" },
  { name: "announcements", topic: "Server news and updates", category: "Community" },
  { name: "help", topic: "Questions and support", category: "Support" },
  { name: "dev", topic: "Programming and tech discussion", category: "Interests" },
  { name: "gaming", topic: "Games and gaming culture", category: "Interests" },
  { name: "music", topic: "Share and discuss music", category: "Interests" },
];

server.tool(
  "seed_channels",
  "Create a realistic set of community channels with categories (general, dev, gaming, etc.)",
  {},
  async () => {
    const db = getDb();
    try {
      const insertCategory = db.prepare(
        "INSERT INTO category (id, name, position) VALUES (?, ?, ?)"
      );
      const insertChannel = db.prepare(
        "INSERT INTO channel (id, category_id, name, type, topic, position) VALUES (?, ?, ?, 'TEXT', ?, ?)"
      );

      const existingChannels = new Set(
        (db.prepare("SELECT name FROM channel").all() as { name: string }[]).map(
          (c) => c.name.toLowerCase()
        )
      );
      const existingCategories = new Map(
        (db.prepare("SELECT id, name FROM category").all() as { id: string; name: string }[]).map(
          (c) => [c.name, c.id]
        )
      );

      let catPosition = (db.prepare("SELECT MAX(position) as m FROM category").get() as any)?.m ?? 0;
      let chPosition = (db.prepare("SELECT MAX(position) as m FROM channel").get() as any)?.m ?? 0;

      const created: string[] = [];

      db.transaction(() => {
        for (const ch of DEFAULT_CHANNELS) {
          if (existingChannels.has(ch.name.toLowerCase())) continue;

          // Ensure category exists
          let catId = existingCategories.get(ch.category);
          if (!catId) {
            catId = uuidv4();
            catPosition++;
            insertCategory.run(catId, ch.category, catPosition);
            existingCategories.set(ch.category, catId);
          }

          chPosition++;
          insertChannel.run(uuidv4(), catId, ch.name, ch.topic, chPosition);
          created.push(`#${ch.name}`);
        }
      })();

      return {
        content: [
          {
            type: "text",
            text: created.length
              ? `Created ${created.length} channels:\n${created.join("\n")}`
              : "All channels already exist.",
          },
        ],
      };
    } finally {
      db.close();
    }
  }
);

// ── Seed Messages ───────────────────────────────────────────────────

server.tool(
  "seed_messages",
  "Bulk-create realistic English chat messages from random users across channels, spread over 30 days",
  {
    count: z
      .number()
      .min(1)
      .max(10000)
      .describe("Number of messages to create"),
    channel_id: z
      .string()
      .optional()
      .describe("Target channel ID (omit = distribute across all text channels)"),
  },
  async ({ count, channel_id }) => {
    const db = getDb();
    try {
      const users = db
        .prepare("SELECT id, username FROM user WHERE deleted_at IS NULL")
        .all() as { id: string; username: string }[];
      if (!users.length)
        return {
          content: [{ type: "text", text: "No users. Run seed_users first." }],
        };

      const channels = channel_id
        ? (db
            .prepare("SELECT id, name FROM channel WHERE id = ?")
            .all(channel_id) as { id: string; name: string }[])
        : (db
            .prepare("SELECT id, name FROM channel WHERE type = 'TEXT'")
            .all() as { id: string; name: string }[]);

      if (!channels.length)
        return {
          content: [
            {
              type: "text",
              text: channel_id ? "Channel not found." : "No text channels.",
            },
          ],
        };

      const insert = db.prepare(
        "INSERT INTO message (id, channel_id, author_id, content, created_at) VALUES (?, ?, ?, ?, ?)"
      );

      const now = Date.now();
      const thirtyDays = 30 * 24 * 60 * 60 * 1000;

      db.transaction(() => {
        for (let i = 0; i < count; i++) {
          const ch = channels[Math.floor(Math.random() * channels.length)];
          const user = users[Math.floor(Math.random() * users.length)];
          const content = pickMessage(ch.name, users);
          // Spread messages chronologically over last 30 days
          const ts = now - thirtyDays + (i / count) * thirtyDays;
          const createdAt = new Date(ts)
            .toISOString()
            .replace("T", " ")
            .slice(0, 19);
          insert.run(uuidv7(), ch.id, user.id, content, createdAt);
        }
      })();

      return {
        content: [
          {
            type: "text",
            text: `Created ${count} messages across ${channels.length} channel(s):\n${channels.map((c) => `  #${c.name}`).join("\n")}`,
          },
        ],
      };
    } finally {
      db.close();
    }
  }
);

// ── Seed Threads ────────────────────────────────────────────────────

server.tool(
  "seed_threads",
  "Create threads on random messages with realistic reply chains",
  {
    count: z
      .number()
      .min(1)
      .max(500)
      .describe("Number of threads to create"),
  },
  async ({ count }) => {
    const db = getDb();
    try {
      const users = db
        .prepare("SELECT id, username FROM user WHERE deleted_at IS NULL")
        .all() as { id: string; username: string }[];
      if (!users.length)
        return { content: [{ type: "text", text: "No users. Run seed_users first." }] };

      // Get messages that don't already have threads and aren't thread replies themselves
      const candidates = db.prepare(
        `SELECT m.id, m.channel_id, m.author_id, m.created_at
         FROM message m
         LEFT JOIN thread t ON t.parent_message_id = m.id
         WHERE m.thread_id IS NULL AND t.id IS NULL
         ORDER BY RANDOM() LIMIT ?`
      ).all(count) as { id: string; channel_id: string; author_id: string; created_at: string }[];

      if (!candidates.length)
        return { content: [{ type: "text", text: "No eligible messages for threads." }] };

      const insertThread = db.prepare(
        "INSERT INTO thread (id, channel_id, parent_message_id, author_id, created_at, last_activity_at) VALUES (?, ?, ?, ?, ?, ?)"
      );
      const insertReply = db.prepare(
        "INSERT INTO message (id, channel_id, author_id, content, thread_id, created_at) VALUES (?, ?, ?, ?, ?, ?)"
      );

      let threadCount = 0;
      let replyCount = 0;

      db.transaction(() => {
        for (const parent of candidates) {
          const threadId = uuidv4();
          const parentTs = new Date(parent.created_at + "Z").getTime();
          // Thread created shortly after parent message
          const threadTs = parentTs + 60_000 + Math.random() * 600_000; // 1-11 min later
          const threadCreated = new Date(threadTs).toISOString().replace("T", " ").slice(0, 19);

          // Random first replier (often the OP, sometimes someone else)
          const firstReplier = Math.random() < 0.3
            ? parent.author_id
            : users[Math.floor(Math.random() * users.length)].id;

          insertThread.run(threadId, parent.channel_id, parent.id, firstReplier, threadCreated, threadCreated);
          threadCount++;

          // Add 2-12 replies spread over minutes to hours
          const numReplies = 2 + Math.floor(Math.random() * 11);
          let lastTs = threadTs;

          for (let r = 0; r < numReplies; r++) {
            const replyUser = users[Math.floor(Math.random() * users.length)];
            const content = pickThreadReply();
            // Each reply 30s - 30min after previous
            lastTs += 30_000 + Math.random() * 1_800_000;
            const replyCreated = new Date(lastTs).toISOString().replace("T", " ").slice(0, 19);
            insertReply.run(uuidv7(), parent.channel_id, replyUser.id, content, threadId, replyCreated);
            replyCount++;
          }

          // Update last_activity_at
          const lastActivity = new Date(lastTs).toISOString().replace("T", " ").slice(0, 19);
          db.prepare("UPDATE thread SET last_activity_at = ? WHERE id = ?").run(lastActivity, threadId);
        }
      })();

      return {
        content: [{
          type: "text",
          text: `Created ${threadCount} threads with ${replyCount} replies total`,
        }],
      };
    } finally {
      db.close();
    }
  }
);

// ── Seed Reactions ──────────────────────────────────────────────────

server.tool(
  "seed_reactions",
  "Add emoji reactions to random messages from random users",
  {
    count: z
      .number()
      .min(1)
      .max(10000)
      .describe("Approximate number of reactions to add"),
  },
  async ({ count }) => {
    const db = getDb();
    try {
      const users = db
        .prepare("SELECT id FROM user WHERE deleted_at IS NULL")
        .all() as { id: string }[];
      if (!users.length)
        return { content: [{ type: "text", text: "No users. Run seed_users first." }] };

      const messages = db.prepare(
        "SELECT id FROM message ORDER BY RANDOM() LIMIT ?"
      ).all(Math.min(count, 5000)) as { id: string }[];

      if (!messages.length)
        return { content: [{ type: "text", text: "No messages. Run seed_messages first." }] };

      const insertReaction = db.prepare(
        "INSERT OR IGNORE INTO reaction (id, message_id, user_id, emoji) VALUES (?, ?, ?, ?)"
      );

      let added = 0;

      db.transaction(() => {
        let remaining = count;
        for (const msg of messages) {
          if (remaining <= 0) break;
          // 1-4 unique emoji per message
          const numEmoji = 1 + Math.floor(Math.random() * 4);
          const chosen = new Set<string>();
          for (let e = 0; e < numEmoji && remaining > 0; e++) {
            const emoji = EMOJI_REACTIONS[Math.floor(Math.random() * EMOJI_REACTIONS.length)];
            if (chosen.has(emoji)) continue;
            chosen.add(emoji);
            // 1-6 users react with this emoji
            const numUsers = 1 + Math.floor(Math.random() * 6);
            const shuffled = [...users].sort(() => Math.random() - 0.5).slice(0, numUsers);
            for (const user of shuffled) {
              if (remaining <= 0) break;
              insertReaction.run(uuidv4(), msg.id, user.id, emoji);
              added++;
              remaining--;
            }
          }
        }
      })();

      return {
        content: [{
          type: "text",
          text: `Added ${added} reactions across ${messages.length} messages`,
        }],
      };
    } finally {
      db.close();
    }
  }
);

// ── Schema ──────────────────────────────────────────────────────────

server.tool(
  "get_schema",
  "Show all table schemas in the Kith database",
  {},
  async () => {
    const db = getDb();
    try {
      const tables = db
        .prepare(
          "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_%' ORDER BY name"
        )
        .all() as { name: string; sql: string }[];
      return {
        content: [
          {
            type: "text",
            text: tables.map((t) => `-- ${t.name}\n${t.sql};`).join("\n\n"),
          },
        ],
      };
    } finally {
      db.close();
    }
  }
);

// ── Start ───────────────────────────────────────────────────────────

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch(console.error);
