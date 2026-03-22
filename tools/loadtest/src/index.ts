import { execSync } from 'node:child_process';
import { loadConfig } from './config.js';
import { assignPersona } from './personas.js';
import { LoadTestClient } from './client.js';
import { metrics } from './metrics.js';
import { seedUsers } from './preseed.js';

async function main(): Promise<void> {
  const config = loadConfig();

  console.log('=== Fold Load Test ===');
  console.log(`Target:    ${config.baseUrl}`);
  console.log(`Users:     ${config.users}`);
  console.log(`Ramp-up:   ${config.rampUpSeconds}s`);
  console.log(`Duration:  ${config.durationSeconds}s`);
  console.log(`Personas:  active=${config.personas.active}% normal=${config.personas.normal}% lurker=${config.personas.lurker}% spammer=${config.personas.spammer}%`);
  console.log(`Skip seed: ${config.skipSeed}`);
  console.log('');

  // --- Preflight checks ---
  await preflightChecks(config.baseUrl, config.users);

  // --- Seed users ---
  if (!config.skipSeed) {
    console.log(`Seeding ${config.users} users...`);
    await seedUsers(config.baseUrl, config.users, config.password, config.serverPassword, config.inviteCode);
    console.log('Seeding complete.\n');
  }

  // --- Start test ---
  metrics.start();
  metrics.startLiveReporting();

  const clients: LoadTestClient[] = [];
  const delayPerUser = config.rampUpSeconds > 0
    ? (config.rampUpSeconds * 1000) / config.users
    : 0;

  console.log(`Ramping up ${config.users} users over ${config.rampUpSeconds}s...\n`);

  // Ramp up users
  for (let i = 0; i < config.users; i++) {
    const username = `loadtest_${i + 1}`;
    const persona = assignPersona(i, config.users, config.personas);
    const client = new LoadTestClient(username, persona, config);
    clients.push(client);

    // Start client (non-blocking per user)
    client.start().catch(() => {});

    if (delayPerUser > 0 && i < config.users - 1) {
      await sleep(delayPerUser);
    }
  }

  console.log(`\nAll ${config.users} users launched. Running for ${config.durationSeconds}s...\n`);

  // --- Wait for test duration ---
  await sleep(config.durationSeconds * 1000);

  // --- Graceful shutdown ---
  console.log('\nShutting down...');
  for (const client of clients) {
    client.stop();
  }

  // Brief grace period for pending ops
  await sleep(2000);

  metrics.stopLiveReporting();
  metrics.printFinalReport();
  metrics.writeJsonReport('report.json');
  metrics.writeCsvReport('report.csv');
}

async function preflightChecks(baseUrl: string, users: number): Promise<void> {
  // Check file descriptor limit
  try {
    const fdLimit = parseInt(execSync('ulimit -n', { encoding: 'utf-8' }).trim(), 10);
    const required = users * 2 + 100;
    if (fdLimit < required) {
      console.error(`ERROR: File descriptor limit too low: ${fdLimit} (need >= ${required})`);
      console.error(`Run: ulimit -n 10240`);
      process.exit(1);
    }
    console.log(`fd limit: ${fdLimit} (need ${required}) ✓`);
  } catch {
    console.warn('Could not check file descriptor limit, proceeding anyway.');
  }

  // Check server reachable
  try {
    const res = await fetch(`${baseUrl}/api/v0/info`);
    if (!res.ok) {
      console.error(`ERROR: Server at ${baseUrl} returned ${res.status}`);
      process.exit(1);
    }
    const info = await res.json() as Record<string, unknown>;
    console.log(`Server reachable: ${info.name ?? 'Fold'} v${info.version ?? '?'} ✓\n`);
  } catch (err) {
    console.error(`ERROR: Cannot reach server at ${baseUrl}`);
    console.error(err);
    process.exit(1);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// Handle graceful shutdown on Ctrl+C
process.on('SIGINT', () => {
  console.log('\n\nInterrupted. Generating report...');
  metrics.stopLiveReporting();
  metrics.printFinalReport();
  metrics.writeJsonReport('report.json');
  metrics.writeCsvReport('report.csv');
  process.exit(0);
});

main().catch(err => {
  console.error('Fatal error:', err);
  process.exit(1);
});
