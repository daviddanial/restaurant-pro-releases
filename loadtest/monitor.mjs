// 1 s resource sampler, no root needed: /proc for processes, cgroup v2 files for containers.
//   node loadtest/monitor.mjs --out loadtest/results/<label>.monitor.jsonl [--extra-pids 123,456]
// Stops on SIGTERM/SIGINT. Each line: per-component cpu (cores), rss/mem MB, disk read/write MB/s, net MB/s.
import fs from 'node:fs'
import { execSync } from 'node:child_process'

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]])
  return acc
}, []))
const OUT = args.out
const TICK = 100 // CLK_TCK
const read = (p) => { try { return fs.readFileSync(p, 'utf8') } catch { return '' } }

const javaPid = execSync("pgrep -f 'java -jar backend/target/telerad' | head -1").toString().trim()
const containers = Object.fromEntries(['minio', 'postgres', 'rabbitmq'].map((c) => {
  const id = execSync(`docker inspect -f '{{.Id}} {{.State.Pid}}' teleradiology-${c}-1`).toString().trim().split(' ')
  return [c, { cg: `/sys/fs/cgroup/system.slice/docker-${id[0]}.scope`, pid: id[1] }]
}))
const extraPids = (args['extra-pids'] || '').split(',').filter(Boolean)

function procSample(pid) {
  const stat = read(`/proc/${pid}/stat`).split(') ')[1]?.split(' ') || []
  const io = Object.fromEntries(read(`/proc/${pid}/io`).trim().split('\n').filter(Boolean).map((l) => l.split(': ')).map(([k, v]) => [k, Number(v)]))
  const rss = Number((read(`/proc/${pid}/status`).match(/VmRSS:\s+(\d+)/) || [])[1] || 0)
  const threads = Number((read(`/proc/${pid}/status`).match(/Threads:\s+(\d+)/) || [])[1] || 0)
  return { cpuS: (Number(stat[11]) + Number(stat[12])) / TICK, rssMB: rss / 1024, threads,
    rdMB: (io.read_bytes || 0) / 1e6, wrMB: (io.write_bytes || 0) / 1e6, rcharMB: (io.rchar || 0) / 1e6, wcharMB: (io.wchar || 0) / 1e6 }
}
function cgSample({ cg, pid }) {
  const cpu = Number((read(`${cg}/cpu.stat`).match(/usage_usec (\d+)/) || [])[1] || 0) / 1e6
  const mem = Number(read(`${cg}/memory.current`) || 0) / 1e6
  let rd = 0, wr = 0
  for (const l of read(`${cg}/io.stat`).split('\n')) {
    rd += Number((l.match(/rbytes=(\d+)/) || [])[1] || 0); wr += Number((l.match(/wbytes=(\d+)/) || [])[1] || 0)
  }
  let rx = 0, tx = 0
  for (const l of read(`/proc/${pid}/net/dev`).split('\n').slice(2)) {
    const [name, rest] = l.split(':'); if (!rest || name.trim() === 'lo') continue
    const f = rest.trim().split(/\s+/).map(Number); rx += f[0]; tx += f[8]
  }
  return { cpuS: cpu, memMB: mem, rdMB: rd / 1e6, wrMB: wr / 1e6, rxMB: rx / 1e6, txMB: tx / 1e6 }
}
function hostSample() {
  const cpu = read('/proc/stat').split('\n')[0].trim().split(/\s+/).slice(1).map(Number)
  const total = cpu.reduce((a, b) => a + b, 0) / TICK, idle = (cpu[3] + cpu[4]) / TICK, iowait = cpu[4] / TICK
  const memAvail = Number((read('/proc/meminfo').match(/MemAvailable:\s+(\d+)/) || [])[1]) / 1024
  let sr = 0, sw = 0
  for (const l of read('/proc/diskstats').split('\n')) {
    const f = l.trim().split(/\s+/); if (f[2] === 'nvme0n1') { sr = Number(f[5]); sw = Number(f[9]) }
  }
  const lo = read('/proc/net/dev').split('\n').find((l) => l.trim().startsWith('lo:'))?.split(':')[1].trim().split(/\s+/).map(Number) || [0]
  return { cpuS: total - idle, totalS: total, iowaitS: iowait, memAvailMB: memAvail, diskRdMB: sr * 512 / 1e6, diskWrMB: sw * 512 / 1e6, loMB: lo[0] / 1e6 }
}

function snapshot() {
  return {
    t: Date.now(), api: procSample(javaPid),
    ...Object.fromEntries(Object.entries(containers).map(([k, v]) => [k, cgSample(v)])),
    ...Object.fromEntries(extraPids.map((p) => [`pid${p}`, procSample(p)])),
    host: hostSample(),
  }
}

const out = fs.createWriteStream(OUT)
let prev = snapshot()
const diff = (a, b, dt) => Object.fromEntries(Object.entries(a).map(([k, v]) =>
  [k, ['rssMB', 'memMB', 'threads', 'memAvailMB'].includes(k) ? +v.toFixed(1) : +((v - (b?.[k] ?? v)) / dt).toFixed(3)]))
const timer = setInterval(() => {
  const cur = snapshot(); const dt = (cur.t - prev.t) / 1000
  const row = { t: cur.t }
  for (const k of Object.keys(cur)) if (k !== 't') row[k] = diff(cur[k], prev[k], dt)
  row.host.cpuCores = row.host.cpuS; row.host.ncpu = +(row.host.totalS).toFixed(1)
  out.write(JSON.stringify(row) + '\n'); prev = cur
}, 1000)
const stop = () => { clearInterval(timer); out.end(() => process.exit(0)) }
process.on('SIGTERM', stop); process.on('SIGINT', stop)
