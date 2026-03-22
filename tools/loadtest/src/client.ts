import WebSocket from 'ws';
import type { Config } from './config.js';
import type { PersonaType, PersonaBehaviour } from './personas.js';
import { getBehaviour, randInt, pick } from './personas.js';
import { metrics } from './metrics.js';

const EMOJIS = ['👍', '❤️', '😂', '🎉', '🔥', '👀', '💯', '🙌', '😍', '🤔'];
const TOKEN_REFRESH_MS = 13 * 60 * 1000; // 13 min
const HEARTBEAT_MS = 30_000;

const MESSAGES = [
  'hey everyone', 'whats up', 'anyone around?', 'lol', 'nice',
  'agreed', 'interesting', 'sounds good', 'thanks!', 'haha',
  'yeah for sure', 'not sure about that', 'good point', 'brb',
  'just saw this', 'that makes sense', 'wait really?', 'oh wow',
  'can someone explain?', 'im back', 'gm', 'gn', ':)',
  'this is great', 'hmm', 'true', 'fair enough', 'lets go',
];

interface Channel {
  id: string;
  type: string;
}

export class LoadTestClient {
  private username: string;
  private config: Config;
  private persona: PersonaType;
  private behaviour: PersonaBehaviour;
  private stopped = false;

  // Auth state
  private accessCookie = '';
  private refreshCookie = '';

  // WS state
  private ws: WebSocket | null = null;
  private sessionId: string | null = null;
  private lastSequence: number | null = null;

  // Channel state
  private channels: Channel[] = [];
  private activeChannels: Channel[] = [];
  private recentMessageIds: string[] = []; // last N received message IDs for reactions

  // Timers
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private activityTimer: ReturnType<typeof setTimeout> | null = null;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  // Latency tracking: messageId -> sendTimestamp
  private pendingLatency = new Map<string, number>();

  constructor(username: string, persona: PersonaType, config: Config) {
    this.username = username;
    this.config = config;
    this.persona = persona;
    this.behaviour = getBehaviour(persona);
  }

  async start(): Promise<void> {
    try {
      await this.login();
      await this.connectWs();
    } catch (err) {
      metrics.connectFailed++;
    }
  }

  stop(): void {
    this.stopped = true;
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    if (this.activityTimer) clearTimeout(this.activityTimer);
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.close();
    }
    this.ws = null;
  }

  // --- Auth ---

  private async login(): Promise<void> {
    for (let attempt = 0; attempt < 5; attempt++) {
      const res = await fetch(`${this.config.baseUrl}/api/v0/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: this.username, password: this.config.password }),
        redirect: 'manual',
      });

      if (res.ok) {
        this.extractCookies(res);
        metrics.loginSuccess++;
        return;
      }

      if (res.status === 429) {
        const body = await res.json().catch(() => ({})) as Record<string, unknown>;
        const retryAfter = (body.retry_after as number) ?? 10;
        await sleep(retryAfter * 1000);
        continue;
      }

      metrics.loginFailed++;
      throw new Error(`Login failed for ${this.username}: ${res.status}`);
    }
    metrics.loginFailed++;
    throw new Error(`Login failed for ${this.username}: max retries`);
  }

  private async refreshToken(): Promise<void> {
    if (this.stopped) return;
    try {
      const res = await fetch(`${this.config.baseUrl}/api/v0/auth/refresh`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Cookie': this.refreshCookie,
        },
        redirect: 'manual',
      });

      if (res.ok) {
        this.extractCookies(res);
        metrics.refreshSuccess++;
        // Reconnect WS with new cookies
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
          this.ws.close();
          // onclose handler will reconnect via RESUME
        }
      } else {
        metrics.refreshFailed++;
      }
    } catch {
      metrics.refreshFailed++;
    }
    this.scheduleRefresh();
  }

  private extractCookies(res: Response): void {
    const setCookies = res.headers.getSetCookie?.() ?? [];
    for (const cookie of setCookies) {
      const part = cookie.split(';')[0];
      if (part.startsWith('fold_access=')) {
        this.accessCookie = part;
      } else if (part.startsWith('fold_refresh=')) {
        this.refreshCookie = part;
      }
    }
  }

  private cookieHeader(): string {
    return [this.accessCookie, this.refreshCookie].filter(Boolean).join('; ');
  }

  // --- WebSocket ---

  private connectWs(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.stopped) return reject(new Error('stopped'));

      const protocol = this.config.baseUrl.startsWith('https') ? 'wss' : 'ws';
      const host = this.config.baseUrl.replace(/^https?:\/\//, '');
      const url = `${protocol}://${host}/api/ws`;

      this.ws = new WebSocket(url, {
        headers: { 'Cookie': this.cookieHeader() },
      });

      let resolved = false;

      this.ws.on('open', () => {
        // Wait for READY from server
      });

      this.ws.on('message', (data) => {
        try {
          const msg = JSON.parse(data.toString());
          this.handleWsMessage(msg);

          if (msg.op === 'HELLO' && !resolved) {
            resolved = true;
            resolve();
          }
        } catch { /* ignore parse errors */ }
      });

      this.ws.on('close', () => {
        metrics.connected--;
        this.stopHeartbeat();
        if (!this.stopped) {
          metrics.reconnects++;
          this.scheduleReconnect();
        }
      });

      this.ws.on('error', () => {
        if (!resolved) {
          resolved = true;
          metrics.connectFailed++;
          reject(new Error('ws error'));
        }
      });

      // Timeout
      setTimeout(() => {
        if (!resolved) {
          resolved = true;
          metrics.connectFailed++;
          reject(new Error('ws timeout'));
        }
      }, 15_000);
    });
  }

  private handleWsMessage(msg: { op: string; d?: Record<string, unknown>; s?: number }): void {
    if (msg.s != null) this.lastSequence = msg.s;

    switch (msg.op) {
      case 'READY':
        if (this.sessionId && this.lastSequence != null) {
          this.wsSend('RESUME', { session_id: this.sessionId, last_sequence: this.lastSequence });
        } else {
          this.wsSend('IDENTIFY');
        }
        break;

      case 'HELLO':
        this.handleHello(msg.d);
        break;

      case 'RESUMED':
        metrics.connected++;
        this.startHeartbeat();
        break;

      case 'RESUME_FAILED':
        this.sessionId = null;
        this.lastSequence = null;
        this.wsSend('IDENTIFY');
        break;

      case 'MESSAGE_CREATE':
        metrics.messagesReceived++;
        this.handleMessageCreate(msg.d);
        break;

      case 'HEARTBEAT_ACK':
        break;
    }
  }

  private handleHello(data?: Record<string, unknown>): void {
    metrics.helloReceived++;
    metrics.connected++;

    if (data) {
      this.sessionId = data.session_id as string;

      // Extract TEXT channels only
      const rawChannels = (data.channels as Channel[]) ?? [];
      this.channels = rawChannels.filter(c => c.type === 'TEXT');

      // Pick active channels for this user based on persona
      if (this.behaviour.channelCount > 0 && this.channels.length > this.behaviour.channelCount) {
        const shuffled = [...this.channels].sort(() => Math.random() - 0.5);
        this.activeChannels = shuffled.slice(0, this.behaviour.channelCount);
      } else {
        this.activeChannels = [...this.channels];
      }
    }

    this.startHeartbeat();
    this.scheduleRefresh();
    this.scheduleActivity();
  }

  private handleMessageCreate(data?: Record<string, unknown>): void {
    if (!data) return;

    const msgId = data.id as string;
    const authorId = data.author_id as string;
    const channelId = data.channel_id as string;

    // Check latency if this is our own message
    if (msgId && this.pendingLatency.has(msgId)) {
      const sent = this.pendingLatency.get(msgId)!;
      metrics.recordLatency(Date.now() - sent);
      this.pendingLatency.delete(msgId);
    }

    // Store for reactions (only other users' messages)
    if (msgId && authorId !== this.username) {
      this.recentMessageIds.push(msgId);
      if (this.recentMessageIds.length > 50) this.recentMessageIds.shift();

      // Maybe react
      if (this.behaviour.reactionChance > 0 && Math.random() < this.behaviour.reactionChance) {
        this.addReaction(msgId, channelId);
      }
    }

    // Maybe update read state
    if (this.behaviour.updatesReadState && msgId && channelId && Math.random() < 0.3) {
      this.updateReadState(channelId, msgId);
    }
  }

  // --- Heartbeat ---

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      this.wsSend('HEARTBEAT');
    }, HEARTBEAT_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  // --- Activity loop ---

  private scheduleActivity(): void {
    if (this.stopped) return;
    if (this.behaviour.messageIntervalMin === 0) return; // lurker

    const delay = randInt(this.behaviour.messageIntervalMin, this.behaviour.messageIntervalMax);
    this.activityTimer = setTimeout(() => this.activityTick(), delay);
  }

  private async activityTick(): Promise<void> {
    if (this.stopped || this.activeChannels.length === 0) return;

    // Maybe switch channel
    if (this.behaviour.channelSwitchChance > 0 && Math.random() < this.behaviour.channelSwitchChance) {
      if (this.channels.length > 1) {
        // Swap one active channel for a random one
        const newChannel = pick(this.channels);
        if (this.activeChannels.length > 0) {
          const idx = Math.floor(Math.random() * this.activeChannels.length);
          this.activeChannels[idx] = newChannel;
        }
        // Fetch message history for the new channel
        await this.fetchMessages(newChannel.id);
      }
    }

    // Send typing then message
    const channel = pick(this.activeChannels);
    if (this.behaviour.sendsTyping) {
      this.wsSend('TYPING', { channel_id: channel.id });
    }

    // Brief delay to simulate typing
    const typingDelay = this.behaviour.sendsTyping ? randInt(500, 2000) : 0;
    await sleep(typingDelay);

    if (this.behaviour.sendsTyping) {
      this.wsSend('TYPING_STOP', { channel_id: channel.id });
    }

    await this.sendMessage(channel.id);

    // Maybe random reconnect
    if (this.behaviour.reconnectChance > 0 && Math.random() < this.behaviour.reconnectChance) {
      this.simulateReconnect();
      return; // reconnect will restart activity
    }

    this.scheduleActivity();
  }

  // --- API calls ---

  private async sendMessage(channelId: string): Promise<void> {
    const content = pick(MESSAGES);
    const sendTime = Date.now();

    try {
      const res = await fetch(`${this.config.baseUrl}/api/v0/channels/${channelId}/messages`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Cookie': this.cookieHeader(),
        },
        body: JSON.stringify({ content }),
      });

      if (res.status === 429) {
        metrics.rateLimited++;
        const body = await res.json().catch(() => ({})) as Record<string, unknown>;
        const retryAfter = (body.retry_after as number) ?? 5;
        await sleep(retryAfter * 1000);
      } else if (res.ok) {
        metrics.messagesSent++;
        const body = await res.json().catch(() => ({})) as Record<string, unknown>;
        if (body.id) {
          this.pendingLatency.set(body.id as string, sendTime);
          // Clean up old pending entries
          if (this.pendingLatency.size > 100) {
            const oldest = this.pendingLatency.keys().next().value;
            if (oldest) this.pendingLatency.delete(oldest);
          }
        }
      } else {
        metrics.errors++;
      }
    } catch {
      metrics.errors++;
    }
  }

  private async addReaction(messageId: string, channelId: string): Promise<void> {
    const emoji = pick(EMOJIS);
    try {
      const res = await fetch(
        `${this.config.baseUrl}/api/v0/messages/${messageId}/reactions/${encodeURIComponent(emoji)}`,
        {
          method: 'PUT',
          headers: { 'Cookie': this.cookieHeader() },
        }
      );
      if (res.status === 429) metrics.rateLimited++;
      else if (!res.ok) metrics.errors++;
    } catch {
      metrics.errors++;
    }
  }

  private async updateReadState(channelId: string, messageId: string): Promise<void> {
    try {
      await fetch(`${this.config.baseUrl}/api/v0/channels/${channelId}/read-state`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Cookie': this.cookieHeader(),
        },
        body: JSON.stringify({ last_read_message_id: messageId }),
      });
    } catch { /* best effort */ }
  }

  private async fetchMessages(channelId: string): Promise<void> {
    try {
      await fetch(`${this.config.baseUrl}/api/v0/channels/${channelId}/messages?limit=50`, {
        headers: { 'Cookie': this.cookieHeader() },
      });
    } catch { /* best effort */ }
  }

  // --- Token refresh ---

  private scheduleRefresh(): void {
    if (this.stopped) return;
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    // Jitter: 0-120s random offset
    const jitter = randInt(0, 120_000);
    this.refreshTimer = setTimeout(() => this.refreshToken(), TOKEN_REFRESH_MS + jitter);
  }

  // --- Reconnection ---

  private simulateReconnect(): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.close(); // will trigger onclose -> scheduleReconnect
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    const delay = randInt(1000, 5000);
    this.reconnectTimer = setTimeout(async () => {
      try {
        await this.connectWs();
      } catch {
        // Will retry on next close
      }
    }, delay);
  }

  // --- WS send ---

  private wsSend(op: string, data?: Record<string, unknown>): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ op, d: data }));
    }
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
