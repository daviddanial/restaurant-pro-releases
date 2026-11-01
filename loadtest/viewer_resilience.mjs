// Viewer resilience checks against a running TeleRad (headless Chromium, real viewer code):
//   A) dropped connection: the first attempt of every image request is reset -> images must still show
//   B) background downloads: max image requests in flight while a big series prefetches
//   C) switching study mid-prefetch: old-study downloads must stop, new study's first image fast
//
//   node loadtest/viewer_resilience.mjs --base https://host --email x --password y \
//        --small <studyId> --big <studyId>
import { chromium } from 'playwright'

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]])
  return acc
}, []))
const BASE = args.base
const IMG = /\/api\/dicom\/studies\/([^/]+)\/series\/[^/]+\/instances\/[^/?]+/

const auth = await (await fetch(`${BASE}/api/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: args.email, password: args.password }),
})).json()
if (!auth.token && !auth.accessToken) throw new Error('login failed')

const browser = await chromium.launch()
async function newPage() {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } })
  await ctx.addInitScript((a) => {
    localStorage.setItem('telerad_auth', JSON.stringify(a))
    window.__lt = { rendered: [] }
    document.addEventListener('CORNERSTONE_IMAGE_RENDERED', () => window.__lt.rendered.push(Date.now()), true)
  }, auth)
  const page = await ctx.newPage()
  const log = []
  page.on('console', (m) => { if (/retry|telerad/.test(m.text())) log.push(m.type() + ' ' + m.text()) })
  return { ctx, page, log }
}
const waitRendered = (page, n, ms) => page.waitForFunction((k) => window.__lt.rendered.length >= k, n, { timeout: ms })
const errorText = (page) => page.locator('text=/Failed to load/i').count()
const results = {}

// ---- A) dropped connection -------------------------------------------------------------
{
  const { ctx, page, log } = await newPage()
  const seen = new Map()
  let resets = 0
  await page.route(IMG, (route) => {
    const u = route.request().url()
    const n = (seen.get(u) || 0) + 1
    seen.set(u, n)
    if (n === 1) { resets++; return route.abort('connectionreset') }
    return route.continue()
  })
  const t0 = Date.now()
  await page.goto(`${BASE}/studies/${args.small}`)
  let ok = true
  try { await waitRendered(page, 1, 30_000) } catch { ok = false }
  const t = Date.now() - t0
  await page.waitForTimeout(3000)
  results.A = { imageShown: ok, msToFirstImage: t, firstAttemptsReset: resets,
    retriesLogged: log.filter((l) => l.includes("[telerad]")).length, everyResetRetried: [...seen.values()].every((n) => n >= 2),
    attempts: [...seen.entries()].map(([u, n]) => `${n}x ${u.split('/').pop().slice(-12)}`), errorShown: (await errorText(page)) > 0 }
  await page.screenshot({ path: `${args.out || '.'}/A_dropped_connection.png` })
  await ctx.close()
}

if (args['only-a']) { console.log(JSON.stringify(results, null, 2)); await browser.close(); process.exit(0) }
// ---- B + C) prefetch cap, then switch study --------------------------------------------
{
  const { ctx, page } = await newPage()
  const inflight = new Map() // url -> studyUid
  let peak = 0
  const started = []
  page.on('request', (r) => {
    const m = r.url().match(IMG); if (!m) return
    inflight.set(r, m[1]); started.push({ t: Date.now(), study: m[1] })
    peak = Math.max(peak, inflight.size)
  })
  const done = (r) => inflight.delete(r)
  page.on('requestfinished', done); page.on('requestfailed', done)

  await page.goto(`${BASE}/studies/${args.big}`)
  await waitRendered(page, 1, 60_000)
  if (args['big-series']) {
    const n0 = await page.evaluate(() => window.__lt.rendered.length)
    await page.getByText(args['big-series']).first().click()
    await waitRendered(page, n0 + 1, 60_000)
    peak = inflight.size
  }
  const tB = Date.now()
  await page.waitForTimeout(20_000)
  const bigUid = started[0]?.study
  const bigDone = started.filter((s) => s.study === bigUid && s.t >= tB).length
  results.B = { peakImageRequestsInFlight: peak, imagesRequestedIn20s: bigDone }

  // In-app navigation (like clicking a study in the worklist): no page reload.
  const tSwitch = Date.now()
  const renderedBefore = await page.evaluate(() => window.__lt.rendered.length)
  await page.evaluate((id) => { history.pushState({}, '', `/studies/${id}`); dispatchEvent(new PopStateEvent('popstate')) }, args.small)
  await waitRendered(page, renderedBefore + 1, 60_000)
  const tFirst = Date.now() - tSwitch
  await page.waitForTimeout(8_000)
  const oldAfter = started.filter((s) => s.study === bigUid && s.t > tSwitch + 1000).length
  results.C = { msToFirstImageAfterSwitch: tFirst, oldStudyRequestsStartedAfterSwitch: oldAfter,
    errorShown: (await errorText(page)) > 0 }
  await page.screenshot({ path: `${args.out || '.'}/C_after_switch.png` })
  await ctx.close()
}
await browser.close()
console.log(JSON.stringify(results, null, 2))
