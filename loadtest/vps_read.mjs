// One radiologist over the real network: open a study and load its largest series the way the viewer does.
//   node loadtest/vps_read.mjs --base https://telerad.example --email x --password y --study-id <uuid> [--old 30]
// New viewer: first image, then the rest with 5 requests in flight (stackPrefetch), gzip accepted.
// --old N: also time the old viewer's pattern on the first N images (one at a time, no gzip) for comparison.
import fs from 'node:fs'
import path from 'node:path'

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]])
  return acc
}, []))
const BASE = args.base
const PREFETCH = Number(args.prefetch || 5)
const OLD = Number(args.old || 0)
const HERE = path.dirname(new URL(import.meta.url).pathname)
const now = () => performance.now()
const headers = (token, extra = {}) => ({ Authorization: `Bearer ${token}`, Origin: BASE, ...extra })

async function get(url, token, extra) {
  const t0 = now()
  const res = await fetch(url, { headers: headers(token, extra) })
  const buf = await res.arrayBuffer()
  return { status: res.status, ms: now() - t0, bytes: buf.byteLength,
    wire: Number(res.headers.get('content-length') || buf.byteLength), gzip: res.headers.get('content-encoding') === 'gzip' }
}

const login = await fetch(`${BASE}/api/auth/login`, { method: 'POST',
  headers: { 'Content-Type': 'application/json', Origin: BASE },
  body: JSON.stringify({ email: args.email, password: args.password }) })
if (!login.ok) throw new Error('login ' + login.status)
const token = (await login.json()).accessToken

const t0 = now()
const detailRes = await fetch(`${BASE}/api/studies/${args['study-id']}`, { headers: headers(token) })
const detailText = await detailRes.text()
const detail = JSON.parse(detailText)
const openMs = now() - t0
const series = [...detail.series].sort((a, b) => b.instances.length - a.instances.length)[0]
const inst = [...series.instances].sort((a, b) => (a.instanceNumber ?? 0) - (b.instanceNumber ?? 0))
const url = (i) => `${BASE}/api/dicom/studies/${detail.study.studyInstanceUid}/series/${series.seriesInstanceUid}/instances/${inst[i].sopInstanceUid}`

const images = []
const first = await get(url(0), token)
images.push(first)
const firstImageMs = now() - t0
let next = 1
await Promise.all(Array.from({ length: PREFETCH }, async () => {
  while (next < inst.length) { const i = next++; images.push(await get(url(i), token)) }
}))
const fullMs = now() - t0
const errors = images.filter((r) => r.status !== 200).length
const wire = images.reduce((a, r) => a + r.wire, 0)
const raw = images.reduce((a, r) => a + r.bytes, 0)

let old = null
if (OLD) {
  const t1 = now()
  let oldBytes = 0
  for (let i = 0; i < Math.min(OLD, inst.length); i++) {
    // cache-buster: the new server would answer a repeat with 304 / the client might reuse nothing anyway
    const r = await get(url(i) + `?nocache=${Date.now()}`, token, { 'Accept-Encoding': 'identity' })
    oldBytes += r.bytes
  }
  const ms = now() - t1
  old = { images: Math.min(OLD, inst.length), ms: Math.round(ms), mb: +(oldBytes / 1e6).toFixed(1),
    projectedFullSeriesS: Math.round(ms / Math.min(OLD, inst.length) * inst.length / 1000) }
}

const out = {
  at: new Date().toISOString(), base: BASE, study: args['study-id'], series: series.seriesDescription,
  images: inst.length, studyDetailKb: Math.round(detailText.length / 1024), openMs: Math.round(openMs),
  firstImageMs: Math.round(firstImageMs - openMs), firstImageFromOpenMs: Math.round(firstImageMs),
  fullSeriesS: +(fullMs / 1000).toFixed(1), wireMb: +(wire / 1e6).toFixed(1), rawMb: +(raw / 1e6).toFixed(1),
  gzipShare: images.filter((r) => r.gzip).length / images.length, mbps: +((wire * 8) / (fullMs - openMs) / 1000).toFixed(2),
  errors, old,
}
fs.mkdirSync(path.join(HERE, 'results_vps'), { recursive: true })
fs.writeFileSync(path.join(HERE, 'results_vps', `read-${Date.now()}.json`), JSON.stringify(out, null, 1))
console.log(JSON.stringify(out, null, 1))
