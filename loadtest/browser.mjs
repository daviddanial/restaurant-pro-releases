// Real-browser readers (headless Chromium via Playwright) running the actual TeleRad viewer,
// routed through the shaping proxy. Measures what a radiologist sees:
//   first image  = first study API request -> first CORNERSTONE_IMAGE_RENDERED in the viewport
//   scroll step  = ArrowDown keypress -> CORNERSTONE_STACK_NEW_IMAGE for that index (image displayed)
//   full study   = first study API request -> last image displayed
//   preview      = first study API request -> low-res preview <img> loaded (large CR/DX images only)
//   --reopen 1   : after reading, open the same study again in the same browser profile (HTTP cache test)
//
//   PLAYWRIGHT_BROWSERS_PATH=loadtest/.browsers node loadtest/browser.mjs --readers 18,19,20 \
//        --modality CT --label ct-20-50mbps-cold [--base http://localhost:5190] [--max-images 0]
import fs from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'

const HERE = path.dirname(new URL(import.meta.url).pathname)
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]])
  return acc
}, []))
const READERS = String(args.readers || '1').split(',').map(Number)
const MODALITY = args.modality || 'CT'
const BASE = args.base || 'http://localhost:5190'
const LABEL = args.label || `browser-${MODALITY}`
const MAX_IMAGES = Number(args['max-images'] || 0)
const REOPEN = args.reopen === '1' 
const STEP_TIMEOUT = 30_000

const creds = JSON.parse(fs.readFileSync(path.join(HERE, 'creds.json'), 'utf8'))
const studies = JSON.parse(fs.readFileSync(path.join(HERE, 'study_ids.json'), 'utf8'))
  .filter((s) => s.modality === MODALITY).sort((a, b) => a.accession.localeCompare(b.accession))

const pct = (arr, p) => { if (!arr.length) return null; const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))] }

async function session(browser, n) {
  const email = `lt_rad_${String(n).padStart(2, '0')}@telerad.local`
  const study = studies[n - 1]
  const auth = await (await fetch(`${BASE}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password: creds[email] }),
  })).json()
  const ctxOpts = { viewport: { width: 1600, height: 900 }, extraHTTPHeaders: { 'x-lt-reader': String(n) } }
  // Reopen test: a persistent profile has a disk HTTP cache like a real workstation browser;
  // a default (incognito-like) context only keeps a small in-memory cache.
  const profileDir = REOPEN ? fs.mkdtempSync(path.join(HERE, '.profile-')) : null
  const ctx = REOPEN
    ? await chromium.launchPersistentContext(profileDir, { ...LAUNCH, ...ctxOpts })
    : await browser.newContext(ctxOpts)
  await ctx.addInitScript((a) => {
    localStorage.setItem('telerad_auth', JSON.stringify(a))
    window.__lt = { rendered: [], newImage: [], preview: null }
    document.addEventListener('CORNERSTONE_IMAGE_RENDERED', () => window.__lt.rendered.push(Date.now()), true)
    document.addEventListener('CORNERSTONE_STACK_NEW_IMAGE', (e) => window.__lt.newImage.push({ i: e.detail?.imageIdIndex, t: Date.now() }), true)
    // Preview <img> gets data-loaded=1 on load.
    new MutationObserver(() => {
      if (window.__lt.preview == null && document.querySelector('img.viewport-preview[data-loaded="1"]')) window.__lt.preview = Date.now()
    }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['data-loaded'] })
  }, auth)
  let out
  try {
    out = await readStudy(ctx, n, study)
    if (REOPEN) out.reopen = await readStudy(ctx, n, study)
  } finally {
    await ctx.close().catch(() => {})
    if (profileDir) fs.rmSync(profileDir, { recursive: true, force: true })
  }
  return out
}

async function readStudy(ctx, n, study) {
  const page = await ctx.newPage()
  // A killed renderer (e.g. out of memory) never fires the events we wait on: fail fast instead of hanging.
  const crashed = new Promise((_, reject) => page.on('crash', () => reject(new Error('page crashed (renderer killed)'))))
  crashed.catch(() => {})
  try {
    return await Promise.race([readStudyIn(page, n, study), crashed])
  } finally {
    await page.close().catch(() => {})
  }
}

async function readStudyIn(page, n, study) {
  const out = { reader: n, accession: study.accession, compressed: study.compressed, errors: [], steps: [],
    wireBytes: 0, imageRequests: 0, fromCache: 0, notModified: 0 }
  // DevTools network events: bytes actually received from the network and HTTP-cache hits.
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Network.enable')
  const imageReq = new Set()
  cdp.on('Network.responseReceived', (e) => {
    if (!e.response.url.includes('/instances/')) return
    imageReq.add(e.requestId)
    out.imageRequests++
    if (e.response.fromDiskCache || e.response.fromPrefetchCache) out.fromCache++
    if (e.response.status === 304) out.notModified++
  })
  cdp.on('Network.requestServedFromCache', () => { /* counted via fromDiskCache */ })
  let lastImageDone = null
  cdp.on('Network.loadingFinished', (e) => {
    if (!imageReq.has(e.requestId)) return
    out.wireBytes += e.encodedDataLength
    lastImageDone = Date.now()
  })
  const consoleErrors = []
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 160)) })
  page.on('response', (r) => { if (r.url().includes('/api/') && r.status() >= 400) out.errors.push({ status: r.status(), url: r.url().slice(-60) }) })
  page.on('requestfailed', (r) => { if (r.url().includes('/api/')) out.errors.push({ status: 'failed:' + r.failure()?.errorText }) })
  let t0 = null
  let firstInstanceDone = null
  page.on('request', (r) => { if (t0 == null && r.url().includes(`/api/studies/${study.id}`)) t0 = Date.now() })
  page.on('requestfinished', (r) => { if (firstInstanceDone == null && r.url().includes('/instances/')) firstInstanceDone = Date.now() })

  await page.goto(`${BASE}/studies/${study.id}`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  // First image = the stack viewport reports image index 0 as displayed (STACK_NEW_IMAGE).
  // IMAGE_RENDERED alone is not used: it also fires for renders before any pixel data has arrived.
  await page.waitForFunction(() => window.__lt.newImage.some((e) => e.i === 0), null, { timeout: 180_000, polling: 20 })
  const ev = await page.evaluate(() => ({ shown: window.__lt.newImage.find((e) => e.i === 0).t, rendered0: window.__lt.rendered[0], preview: window.__lt.preview }))
  out.previewMs = ev.preview == null ? null : ev.preview - t0
  out.firstImageMs = ev.shown - t0
  out.firstRenderEventMs = ev.rendered0 - t0
  out.firstInstanceBytesMs = firstInstanceDone == null ? null : firstInstanceDone - t0
  let total = study.images
  if (MAX_IMAGES) total = Math.min(total, MAX_IMAGES)
  await page.mouse.move(900, 500)
  for (let i = 1; i < total; i++) {
    const before = Date.now()
    await page.keyboard.press('ArrowDown')
    try {
      await page.waitForFunction((idx) => window.__lt.newImage.some((e) => e.i === idx), i, { timeout: STEP_TIMEOUT, polling: 10 })
      const shown = await page.evaluate((idx) => window.__lt.newImage.find((e) => e.i === idx).t, i)
      out.steps.push(shown - before)
    } catch {
      out.errors.push({ status: `step ${i} not displayed in ${STEP_TIMEOUT / 1000}s` })
      break
    }
  }
  out.fullStudyMs = Date.now() - t0
  // Network side of "full study": last image response finished (prefetch makes this precede the scroll).
  out.allImagesReceivedMs = lastImageDone == null ? null : lastImageDone - t0
  out.consoleErrors = [...new Set(consoleErrors)].slice(0, 5)
  await page.waitForTimeout(300) // let the last network events arrive
  return out
}

const LAUNCH = { headless: true, args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader'] }
const browser = await chromium.launch(LAUNCH)
const results = await Promise.all(READERS.map((n) => session(browser, n).catch((e) => ({ reader: n, fatal: e.message.slice(0, 200), errors: [{ status: 'fatal' }], steps: [] }))))
await browser.close()

const steps = results.flatMap((r) => r.steps)
const ok = results.filter((r) => r.firstImageMs != null)
const summary = {
  label: LABEL, readers: READERS, modality: MODALITY,
  firstImage: { p50: pct(ok.map((r) => r.firstImageMs), 50), max: Math.max(...ok.map((r) => r.firstImageMs)) },
  fullStudy: { p50: pct(ok.map((r) => r.fullStudyMs), 50), max: Math.max(...ok.map((r) => r.fullStudyMs)) },
  allImagesReceived: { max: Math.max(...ok.map((r) => r.allImagesReceivedMs ?? 0)) },
  scrollMs: { p50: pct(steps, 50), p95: pct(steps, 95), p99: pct(steps, 99), max: steps.length ? Math.max(...steps) : null },
  errors: results.reduce((a, r) => a + r.errors.length + (r.reopen?.errors.length || 0), 0),
  previewMs: { max: ok.some((r) => r.previewMs != null) ? Math.max(...ok.filter((r) => r.previewMs != null).map((r) => r.previewMs)) : null },
  perReader: results.map((r) => ({ reader: r.reader, accession: r.accession, compressed: r.compressed, firstImageMs: r.firstImageMs,
    firstRenderEventMs: r.firstRenderEventMs, firstInstanceBytesMs: r.firstInstanceBytesMs,
    fullStudyMs: r.fullStudyMs, scrollP95: pct(r.steps, 95), steps: r.steps.length, errors: r.errors.slice(0, 3), fatal: r.fatal, consoleErrors: r.consoleErrors,
    previewMs: r.previewMs, allImagesReceivedMs: r.allImagesReceivedMs, wireMb: r.wireBytes == null ? null : +(r.wireBytes / 1e6).toFixed(1), imageRequests: r.imageRequests,
    reopen: r.reopen && { firstImageMs: r.reopen.firstImageMs, fullStudyMs: r.reopen.fullStudyMs, wireMb: +(r.reopen.wireBytes / 1e6).toFixed(2),
      imageRequests: r.reopen.imageRequests, fromCache: r.reopen.fromCache, notModified: r.reopen.notModified, errors: r.reopen.errors.length } })),
}
fs.mkdirSync(path.join(HERE, 'results'), { recursive: true })
fs.writeFileSync(path.join(HERE, 'results', `${LABEL}.browser.json`), JSON.stringify(summary, null, 1))
const f = (x) => (x == null ? '-' : (x / 1000).toFixed(2) + 's')
console.log(`${LABEL} [browser x${READERS.length}]: first p50=${f(summary.firstImage.p50)} max=${f(summary.firstImage.max)} | ` +
  `full p50=${f(summary.fullStudy.p50)} max=${f(summary.fullStudy.max)} | step p50=${summary.scrollMs.p50}ms p95=${summary.scrollMs.p95}ms ` +
  `max=${summary.scrollMs.max}ms | errors=${summary.errors}`)
