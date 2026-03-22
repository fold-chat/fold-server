/**
 * Bulk user seeding via the Fold REST API.
 *
 * Preferred approach: use the fold-db MCP `seed_users(count, password)` tool
 * before running the load test — it bypasses rate limits entirely.
 *
 * This script is a fallback for environments where MCP isn't available.
 * It handles the 3/hr per-IP registration rate limit by falling back to login
 * for users that already exist.
 */

export async function seedUsers(
  baseUrl: string,
  count: number,
  password: string,
  serverPassword: string,
  inviteCode: string = '',
): Promise<void> {
  let created = 0;
  let existing = 0;
  let failed = 0;

  for (let i = 1; i <= count; i++) {
    const username = `loadtest_${i}`;

    // Try login first (fast path if user exists)
    const loginOk = await tryLogin(baseUrl, username, password);
    if (loginOk) {
      existing++;
      if (i % 50 === 0) logProgress(i, count, created, existing, failed);
      continue;
    }

    // Try register
    const registered = await tryRegister(baseUrl, username, password, serverPassword, inviteCode);
    if (registered) {
      created++;
    } else {
      failed++;
    }

    if (i % 50 === 0) logProgress(i, count, created, existing, failed);
  }

  console.log(`Seed complete: ${created} created, ${existing} existing, ${failed} failed`);
}

async function tryLogin(baseUrl: string, username: string, password: string): Promise<boolean> {
  try {
    const res = await fetch(`${baseUrl}/api/v0/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

async function tryRegister(
  baseUrl: string,
  username: string,
  password: string,
  serverPassword: string,
  inviteCode: string = '',
): Promise<boolean> {
  const body: Record<string, string> = { username, password };
  if (inviteCode) body.invite_code = inviteCode;
  else if (serverPassword) body.server_password = serverPassword;

  try {
    const res = await fetch(`${baseUrl}/api/v0/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

    if (res.status === 429) {
      const rb = await res.json().catch(() => ({})) as Record<string, unknown>;
      const retryAfter = (rb.retry_after as number) ?? 60;
      console.warn(`  Rate limited on register, waiting ${retryAfter}s...`);
      await sleep(retryAfter * 1000);
      const retry = await fetch(`${baseUrl}/api/v0/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      return retry.ok;
    }

    return res.ok;
  } catch {
    return false;
  }
}

function logProgress(current: number, total: number, created: number, existing: number, failed: number): void {
  console.log(`  [${current}/${total}] created=${created} existing=${existing} failed=${failed}`);
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// Allow running standalone: pnpm seed --baseUrl=... --users=500 --password=... --serverPassword=...
if (process.argv[1]?.endsWith('preseed.ts') || process.argv[1]?.endsWith('preseed.js')) {
  const args = process.argv.slice(2);
  const get = (key: string, def: string) => {
    const arg = args.find(a => a.startsWith(`--${key}=`));
    return arg ? arg.split('=')[1] : def;
  };

  const baseUrl = get('baseUrl', 'http://localhost:8080');
  const users = parseInt(get('users', '500'), 10);
  const password = get('password', 'password123');
  const serverPassword = get('serverPassword', '');

  console.log(`Seeding ${users} users at ${baseUrl}...`);
  seedUsers(baseUrl, users, password, serverPassword)
    .then(() => console.log('Done.'))
    .catch(err => { console.error(err); process.exit(1); });
}
