#!/usr/bin/env node
/**
 * Seed script: spawns the fold-mcp MCP server and calls its seed tools
 * in order to populate the database with realistic test data.
 */

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const serverPath = path.join(__dirname, "dist", "index.js");

async function call(client, toolName, args = {}) {
  console.log(`\n→ ${toolName}`, JSON.stringify(args));
  const result = await client.callTool({ name: toolName, arguments: args });
  const text = result.content?.map((c) => c.text).join("\n") ?? "(no output)";
  console.log(text);
  return text;
}

async function main() {
  const transport = new StdioClientTransport({
    command: "node",
    args: [serverPath],
  });

  const client = new Client({ name: "seed-client", version: "1.0.0" });
  await client.connect(transport);

  // 1. Create channels
  await call(client, "seed_channels");

  // 2. Create 50 users
  await call(client, "seed_users", { count: 50 });

  // 3. Seed 500 messages spread across channels
  await call(client, "seed_messages", { count: 500 });

  // 4. Create 30 threads with reply chains
  await call(client, "seed_threads", { count: 30 });

  // 5. Add 300 emoji reactions
  await call(client, "seed_reactions", { count: 300 });

  console.log("\n✓ Seed complete.");
  await client.close();
}

main().catch((err) => {
  console.error("Seed failed:", err);
  process.exit(1);
});
