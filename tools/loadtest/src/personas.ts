import type { PersonaWeights } from './config.js';

export type PersonaType = 'active' | 'normal' | 'lurker' | 'spammer';

export interface PersonaBehaviour {
  /** Min ms between messages (0 = no messages) */
  messageIntervalMin: number;
  /** Max ms between messages (0 = no messages) */
  messageIntervalMax: number;
  /** Number of channels to post in (0 = all) */
  channelCount: number;
  /** Whether to send typing indicators */
  sendsTyping: boolean;
  /** Chance to react to a received message (0-1) */
  reactionChance: number;
  /** Whether to periodically update read state */
  updatesReadState: boolean;
  /** Chance to switch active channel each cycle (0-1) */
  channelSwitchChance: number;
  /** Chance to randomly disconnect/reconnect per minute (0-1) */
  reconnectChance: number;
}

const BEHAVIOURS: Record<PersonaType, PersonaBehaviour> = {
  active: {
    messageIntervalMin: 5_000,
    messageIntervalMax: 15_000,
    channelCount: 0, // all channels
    sendsTyping: true,
    reactionChance: 0.3,
    updatesReadState: true,
    channelSwitchChance: 0.4,
    reconnectChance: 0.01,
  },
  normal: {
    messageIntervalMin: 30_000,
    messageIntervalMax: 90_000,
    channelCount: 3,
    sendsTyping: true,
    reactionChance: 0.1,
    updatesReadState: true,
    channelSwitchChance: 0.15,
    reconnectChance: 0.005,
  },
  lurker: {
    messageIntervalMin: 0,
    messageIntervalMax: 0,
    channelCount: 0,
    sendsTyping: false,
    reactionChance: 0,
    updatesReadState: false,
    channelSwitchChance: 0,
    reconnectChance: 0.002,
  },
  spammer: {
    messageIntervalMin: 1_000,
    messageIntervalMax: 1_000,
    channelCount: 0,
    sendsTyping: false,
    reactionChance: 0,
    updatesReadState: false,
    channelSwitchChance: 0.5,
    reconnectChance: 0,
  },
};

export function getBehaviour(persona: PersonaType): PersonaBehaviour {
  return BEHAVIOURS[persona];
}

/** Assign a persona based on configured weights. idx 0..N-1 deterministic. */
export function assignPersona(idx: number, total: number, weights: PersonaWeights): PersonaType {
  const thresholds: [PersonaType, number][] = [
    ['active', weights.active],
    ['normal', weights.normal],
    ['lurker', weights.lurker],
    ['spammer', weights.spammer],
  ];

  // Map user index to a bucket
  const pct = (idx / total) * 100;
  let cumulative = 0;
  for (const [type, weight] of thresholds) {
    cumulative += weight;
    if (pct < cumulative) return type;
  }
  return 'normal';
}

/** Random integer in [min, max] inclusive */
export function randInt(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

/** Random element from array */
export function pick<T>(arr: T[]): T {
  return arr[Math.floor(Math.random() * arr.length)];
}
