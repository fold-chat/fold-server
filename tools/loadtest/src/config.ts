import { readFileSync } from 'node:fs';
import { parse as parseYaml } from 'yaml';

export interface PersonaWeights {
  active: number;
  normal: number;
  lurker: number;
  spammer: number;
}

export interface Config {
  baseUrl: string;
  users: number;
  serverPassword: string;
  inviteCode: string;
  rampUpSeconds: number;
  durationSeconds: number;
  messageIntervalMs: number;
  skipSeed: boolean;
  password: string;
  personas: PersonaWeights;
}

const DEFAULTS: Config = {
  baseUrl: 'http://localhost:8080',
  users: 500,
  serverPassword: '',
  inviteCode: '',
  rampUpSeconds: 600,
  durationSeconds: 14400,
  messageIntervalMs: 5000,
  skipSeed: false,
  password: 'password123',
  personas: { active: 15, normal: 55, lurker: 25, spammer: 5 },
};

export function loadConfig(): Config {
  const args = process.argv.slice(2);
  let config = { ...DEFAULTS, personas: { ...DEFAULTS.personas } };

  // Load YAML profile if --profile=<path> given
  const profileArg = args.find(a => a.startsWith('--profile='));
  if (profileArg) {
    const path = profileArg.split('=')[1];
    const yaml = parseYaml(readFileSync(path, 'utf-8')) as Partial<Config>;
    config = mergeConfig(config, yaml);
  }

  // CLI overrides
  for (const arg of args) {
    if (arg.startsWith('--profile=')) continue;
    const [key, val] = arg.replace(/^--/, '').split('=');
    if (!val) continue;
    switch (key) {
      case 'baseUrl': config.baseUrl = val; break;
      case 'users': config.users = parseInt(val, 10); break;
      case 'serverPassword': config.serverPassword = val; break;
      case 'rampUpSeconds': config.rampUpSeconds = parseInt(val, 10); break;
      case 'durationSeconds': config.durationSeconds = parseInt(val, 10); break;
      case 'messageIntervalMs': config.messageIntervalMs = parseInt(val, 10); break;
      case 'skipSeed': config.skipSeed = val === 'true'; break;
      case 'password': config.password = val; break;
      case 'inviteCode': config.inviteCode = val; break;
      case 'personas': {
        const p = JSON.parse(val) as Partial<PersonaWeights>;
        config.personas = { ...config.personas, ...p };
        break;
      }
    }
  }

  validateConfig(config);
  return config;
}

function mergeConfig(base: Config, override: Partial<Config>): Config {
  const merged = { ...base };
  for (const [k, v] of Object.entries(override)) {
    if (k === 'personas' && typeof v === 'object') {
      merged.personas = { ...base.personas, ...(v as Partial<PersonaWeights>) };
    } else if (v !== undefined) {
      (merged as Record<string, unknown>)[k] = v;
    }
  }
  return merged;
}

function validateConfig(config: Config): void {
  const { active, normal, lurker, spammer } = config.personas;
  const sum = active + normal + lurker + spammer;
  if (sum !== 100) {
    throw new Error(`Persona weights must sum to 100, got ${sum}`);
  }
  if (config.users < 1) throw new Error('users must be >= 1');
  if (config.rampUpSeconds < 0) throw new Error('rampUpSeconds must be >= 0');
  if (config.durationSeconds < 1) throw new Error('durationSeconds must be >= 1');
}
