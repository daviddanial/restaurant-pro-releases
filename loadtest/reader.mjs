// Simulated radiologist readers. Reproduces the request sequence of the real viewer:
//   GET /api/studies/{id}, GET /api/studies/{id}/priors, the first image, then the rest of the stack.
//   --prefetch N (default 5): after the first image, N images in flight at once, like Cornerstone's
//   stackPrefetch (5 concurrent prefetch requests). --prefetch 0 = the old viewer: one at a time.
//   Requests send Accept-Encoding: gzip (Node fetch default), as browsers do; wireBytes = bytes on the wire.
// Each reader N uses account lt_rad_NN and its own study (no shared-cache effects).
//
//   node loadtest/reader.mjs --readers 10 --first 1 --modality CT --label ct-10-50mbps-cold \
//        --base http://127.0.0.1:5190 [--pace-ms 0] [--max-images 0]
import fs from 'node:fs'
import path from 'node:path'

const HERE = path.dirname(new URL(import.meta.url).pathname)
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]?.startsWith('--') ? 'true' : all[i + 1]])
  return acc
}, []))
const READERS = Number(args.readers || 1)
const FIRST = Number(args.first || 1) // first reader / study index
const MODALITY = args.modality || 'CT'
const BASE = args.base || 'http://127.0.0.1:5190'
const LABEL = args.label || `${MODALITY}-${READERS}`
const PACE_MS = Number(args['pace-ms'] || 0)
const MAX_IMAGES = Number(args['max-images'] || 0)
const PREFETCH = Number(args.prefetch ?? 5)
const TIMEOUT_MS = 30_000

const creds = JSON.parse(fs.readFileSync(path.join(HERE, 'creds.json'), 'utf8'))
const studies = JSON.parse(fs.readFileSync(path.join(HERE, 'study_ids.json'), 'utf8'))
  .filter((s) => s.modality === MODALITY)
  .sort((a, b) => a.accession.localeCompare(b.accession))

async function timed(url, token) {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS)
  const t0 = performance.now()
  try {
    const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` }, signal: ctl.signal })
    const ttfb = performance.now() - t0
    const buf = await res.arrayBuffer()
    const wire = Number(res.headers.get('content-length') || buf.byteLength)
    return { status: res.status, ttfb, ms: performance.now() - t0, bytes: buf.byteLength, wire,
      gzip: res.headers.get('content-encoding') === 'gzip' }
  } catch (e) {
    return { status: e.name === 'AbortError' ? 'timeout' : 'neterr:' + (e.cause?.code || e.message), ttfb: null,
      ms: performance.now() - t0, bytes: 0, wire: 0 }
  } finally {
    clearTimeout(timer)
  }
}

async function login(email) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: creds[email] }),
  })
  if (!res.ok) throw new Error(`login ${email} -> ${res.status}`)
  return (await res.json()).accessToken
}

async function runReader(n, study, token, startGate) {
  await startGate
  const out = { reader: n, accession: study.accession, compressed: study.compressed, errors: [], images: [] }
  const t0 = performance.now()
  const s = await timed(`${BASE}/api/studies/${study.id}`, token)
  const p = await timed(`${BASE}/api/studies/${study.id}/priors`, token)
  for (const r of [s, p]) if (r.status !== 200) out.errors.push({ step: 'open', status: r.status })
  // Instance list comes from the study payload, exactly like the viewer.
  const detail = await (await fetch(`${BASE}/api/studies/${study.id}`, { headers: { Authorization: `Bearer ${token}` } })).json()
  const series = detail.series[0]
  let instances = [...series.instances].sort((a, b) => a.instanceNumber - b.instanceNumber)
  if (MAX_IMAGES) instances = instances.slice(0, MAX_IMAGES)
  out.openMs = performance.now() - t0
  const urlOf = (inst) => `${BASE}/api/dicom/studies/${detail.study.studyInstanceUid}/series/${series.seriesInstanceUid}/instances/${inst.sopInstanceUid}`
  const fetchImage = async (i) => {
    const r = await timed(urlOf(instances[i]), token)
    out.images.push({ i, ms: Math.round(r.ms), ttfb: r.ttfb == null ? null : Math.round(r.ttfb), bytes: r.bytes,
      wire: r.wire, gzip: r.gzip, status: r.status })
    if (r.status !== 200) out.errors.push({ step: `image ${i}`, status: r.status })
    if (PACE_MS) await new Promise((res) => setTimeout(res, PACE_MS))
  }
  await fetchImage(0)
  out.firstImageMs = performance.now() - t0
  let next = 1
  const workers = Array.from({ length: Math.max(1, PREFETCH) }, async () => {
    while (next < instances.length) await fetchImage(next++)
  })
  await Promise.all(workers)
  out.images.sort((a, b) => a.i - b.i)
  out.fullStudyMs = performance.now() - t0
  out.bytes = out.images.reduce((a, x) => a + x.bytes, 0)
  out.wireBytes = out.images.reduce((a, x) => a + x.wire, 0)
  return out
}

function pct(arr, p) {
  if (!arr.length) return null
  const s = [...arr].sort((a, b) => a - b)
  return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))]
}

const assignments = Array.from({ length: READERS }, (_, k) => {
  const n = FIRST + k
  return { n, email: `lt_rad_${String(n).padStart(2, '0')}@telerad.local`, study: studies[n - 1] }
})
const tokens = await Promise.all(assignments.map((a) => login(a.email)))
let release
const gate = new Promise((r) => { release = r })
const startedAt = new Date().toISOString()
const runs = assignments.map((a, k) => runReader(a.n, a.study, tokens[k], gate))
release()
const results = await Promise.all(runs)

const scroll = results.flatMap((r) => r.images.slice(1).map((x) => x.ms))
const summary = {
  label: LABEL, startedAt, readers: READERS, modality: MODALITY, paceMs: PACE_MS, prefetch: PREFETCH, base: BASE,
  firstImage: { p50: pct(results.map((r) => r.firstImageMs), 50), max: Math.max(...results.map((r) => r.firstImageMs)) },
  fullStudy: { p50: pct(results.map((r) => r.fullStudyMs), 50), max: Math.max(...results.map((r) => r.fullStudyMs)) },
  scrollMs: { p50: pct(scroll, 50), p95: pct(scroll, 95), p99: pct(scroll, 99), max: Math.max(...scroll) },
  errors: results.reduce((a, r) => a + r.errors.length, 0),
  errorKinds: [...new Set(results.flatMap((r) => r.errors.map((e) => String(e.status))))],
  mbTotal: results.reduce((a, r) => a + r.bytes, 0) / 1e6,
  wireMbTotal: results.reduce((a, r) => a + r.wireBytes, 0) / 1e6,
  perReader: results.map((r) => ({ reader: r.reader, accession: r.accession, compressed: r.compressed,
    firstImageMs: Math.round(r.firstImageMs), fullStudyMs: Math.round(r.fullStudyMs),
    scrollP95: pct(r.images.slice(1).map((x) => x.ms), 95), errors: r.errors.length, mb: +(r.bytes / 1e6).toFixed(1), wireMb: +(r.wireBytes / 1e6).toFixed(1) })),
}
fs.mkdirSync(path.join(HERE, 'results'), { recursive: true })
fs.writeFileSync(path.join(HERE, 'results', `${LABEL}.json`), JSON.stringify({ summary, results }, null, 1))
const f = (x) => (x == null ? '-' : (x / 1000).toFixed(2) + 's')
console.log(`${LABEL}: readers=${READERS} first p50=${f(summary.firstImage.p50)} max=${f(summary.firstImage.max)} | ` +
  `full p50=${f(summary.fullStudy.p50)} max=${f(summary.fullStudy.max)} | scroll p50=${summary.scrollMs.p50}ms ` +
  `p95=${summary.scrollMs.p95}ms max=${summary.scrollMs.max}ms | errors=${summary.errors} ${summary.errorKinds.join(',')}`)
