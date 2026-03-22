import { writeFileSync } from 'node:fs';

export interface Snapshot {
  timestamp: number;
  elapsed: number;
  connected: number;
  messagesSent: number;
  messagesReceived: number;
  rateLimited: number;
  errors: number;
  reconnects: number;
  refreshes: number;
  latencyP50: number;
  latencyP95: number;
  latencyP99: number;
}

class Metrics {
  // Counters
  messagesSent = 0;
  messagesReceived = 0;
  rateLimited = 0;
  errors = 0;
  reconnects = 0;
  loginSuccess = 0;
  loginFailed = 0;
  helloReceived = 0;
  connectFailed = 0;
  refreshSuccess = 0;
  refreshFailed = 0;

  connected = 0;
  startTime = 0;

  // Latency samples (ms) — ring buffer to bound memory over long runs
  private latencies: number[] = [];
  private readonly maxSamples = 100_000;

  // Time-series for CSV export
  private snapshots: Snapshot[] = [];

  private liveInterval: ReturnType<typeof setInterval> | null = null;

  start(): void {
    this.startTime = Date.now();
  }

  recordLatency(ms: number): void {
    if (this.latencies.length >= this.maxSamples) {
      // Evict oldest quarter
      this.latencies = this.latencies.slice(this.maxSamples / 4);
    }
    this.latencies.push(ms);
  }

  percentile(p: number): number {
    if (this.latencies.length === 0) return 0;
    const sorted = [...this.latencies].sort((a, b) => a - b);
    const idx = Math.ceil((p / 100) * sorted.length) - 1;
    return sorted[Math.max(0, idx)];
  }

  snapshot(): Snapshot {
    return {
      timestamp: Date.now(),
      elapsed: Math.floor((Date.now() - this.startTime) / 1000),
      connected: this.connected,
      messagesSent: this.messagesSent,
      messagesReceived: this.messagesReceived,
      rateLimited: this.rateLimited,
      errors: this.errors,
      reconnects: this.reconnects,
      refreshes: this.refreshSuccess,
      latencyP50: this.percentile(50),
      latencyP95: this.percentile(95),
      latencyP99: this.percentile(99),
    };
  }

  startLiveReporting(intervalMs = 5000): void {
    this.liveInterval = setInterval(() => {
      const s = this.snapshot();
      this.snapshots.push(s);
      const elapsed = formatDuration(s.elapsed);
      console.log(
        `[${elapsed}] conn=${s.connected} sent=${s.messagesSent} recv=${s.messagesReceived} ` +
        `429s=${s.rateLimited} err=${s.errors} reconn=${s.reconnects} refresh=${s.refreshes} ` +
        `p50=${s.latencyP50}ms p95=${s.latencyP95}ms p99=${s.latencyP99}ms`
      );
    }, intervalMs);
  }

  stopLiveReporting(): void {
    if (this.liveInterval) {
      clearInterval(this.liveInterval);
      this.liveInterval = null;
    }
  }

  printFinalReport(): void {
    const elapsed = (Date.now() - this.startTime) / 1000;
    const throughput = elapsed > 0 ? (this.messagesSent / elapsed).toFixed(2) : '0';
    const errorRate = this.messagesSent > 0
      ? ((this.errors / this.messagesSent) * 100).toFixed(2)
      : '0';

    console.log('\n=== FINAL REPORT ===');
    console.log(`Duration:          ${formatDuration(Math.floor(elapsed))}`);
    console.log(`Messages sent:     ${this.messagesSent}`);
    console.log(`Messages received: ${this.messagesReceived}`);
    console.log(`Throughput:        ${throughput} msg/s`);
    console.log(`Rate limited:      ${this.rateLimited}`);
    console.log(`Errors:            ${this.errors}`);
    console.log(`Error rate:        ${errorRate}%`);
    console.log(`Reconnects:        ${this.reconnects}`);
    console.log(`Token refreshes:   ${this.refreshSuccess} ok / ${this.refreshFailed} failed`);
    console.log(`Logins:            ${this.loginSuccess} ok / ${this.loginFailed} failed`);
    console.log(`WS HELLOs:        ${this.helloReceived}`);
    console.log(`Connect failures:  ${this.connectFailed}`);
    console.log(`Latency p50:       ${this.percentile(50)}ms`);
    console.log(`Latency p95:       ${this.percentile(95)}ms`);
    console.log(`Latency p99:       ${this.percentile(99)}ms`);
    console.log('====================\n');
  }

  writeJsonReport(path: string): void {
    const report = {
      startTime: this.startTime,
      endTime: Date.now(),
      durationSeconds: Math.floor((Date.now() - this.startTime) / 1000),
      messagesSent: this.messagesSent,
      messagesReceived: this.messagesReceived,
      throughput: this.messagesSent / ((Date.now() - this.startTime) / 1000),
      rateLimited: this.rateLimited,
      errors: this.errors,
      reconnects: this.reconnects,
      refreshSuccess: this.refreshSuccess,
      refreshFailed: this.refreshFailed,
      loginSuccess: this.loginSuccess,
      loginFailed: this.loginFailed,
      helloReceived: this.helloReceived,
      connectFailed: this.connectFailed,
      latency: {
        p50: this.percentile(50),
        p95: this.percentile(95),
        p99: this.percentile(99),
      },
    };
    writeFileSync(path, JSON.stringify(report, null, 2));
    console.log(`JSON report written to ${path}`);
  }

  writeCsvReport(path: string): void {
    if (this.snapshots.length === 0) return;
    const header = Object.keys(this.snapshots[0]).join(',');
    const rows = this.snapshots.map(s => Object.values(s).join(','));
    writeFileSync(path, header + '\n' + rows.join('\n') + '\n');
    console.log(`CSV time-series written to ${path}`);
  }
}

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

// Singleton
export const metrics = new Metrics();
