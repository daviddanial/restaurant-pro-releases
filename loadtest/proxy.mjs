// Per-reader network shaping proxy (user-space stand-in for tc/netem, which needs root).
//   /api/*  -> backend (127.0.0.1:38471), everything else -> Vite (127.0.0.1:5173, incl. HMR websocket)
// Only /api/* (study data + images) is shaped. App code is served unshaped: the Vite dev server sends
// thousands of unbundled modules, which a production bundle (cached after first load) would not.
// Bandwidth is a token bucket per *reader* (JWT subject), shared by all of that reader's
// connections, so a browser's ~6 parallel sockets get one reader's link, not six.
// Latency is added once per request before it is forwarded (approximates one RTT before first byte).
//
//   RATE_MBPS=50 LATENCY_MS=50 PORT=5190 node loadtest/proxy.mjs      (RATE_MBPS=0 => unlimited)
import http from 'node:http'
import net from 'node:net'

const PORT = Number(process.env.PORT || 5190)
const RATE_MBPS = Number(process.env.RATE_MBPS || 0)
const LATENCY_MS = Number(process.env.LATENCY_MS || 0)
const API = { host: '127.0.0.1', port: 38471 }
const VITE = { host: '127.0.0.1', port: 5173 }
const bytesPerMs = RATE_MBPS > 0 ? (RATE_MBPS * 1e6) / 8 / 1000 : 0

let inflight = 0
const buckets = new Map() // key -> next free time (ms)
// Hop-by-hop headers must not be forwarded (RFC 9110 7.6.1); forwarding Tomcat's `Connection: close`
// (sent every 100 keep-alive requests) to the client races the proxy's own socket handling.
const HOP = new Set(['connection', 'keep-alive', 'proxy-connection', 'transfer-encoding', 'te', 'trailer', 'upgrade'])
const endToEnd = (h) => Object.fromEntries(Object.entries(h).filter(([k]) => !HOP.has(k.toLowerCase())))
const agent = new http.Agent({ keepAlive: true, maxSockets: 512 })

function readerKey(req) {
  const auth = req.headers.authorization || ''
  const m = auth.match(/^Bearer\s+[^.]+\.([^.]+)\./)
  if (m) {
    try {
      return 'sub:' + JSON.parse(Buffer.from(m[1], 'base64url').toString()).sub
    } catch { /* fall through */ }
  }
  // Page/asset requests carry no token: key by client port's remote address + UA so browsers are separate.
  return 'anon:' + (req.headers['user-agent'] || '') + (req.headers['x-lt-reader'] || '')
}

function throttledPipe(key, upstream, res) {
  if (!bytesPerMs) {
    upstream.pipe(res)
    return
  }
  let pending = 0
  let ended = false
  const maybeEnd = () => { if (ended && pending === 0) res.end() }
  upstream.on('data', (chunk) => {
    upstream.pause()
    pending++
    const now = performance.now()
    const start = Math.max(now, buckets.get(key) || 0)
    const done = start + chunk.length / bytesPerMs
    buckets.set(key, done)
    setTimeout(() => {
      if (res.__life) { res.__life.chunks++; res.__life.bytes += chunk.length }
      res.write(chunk)
      pending--
      upstream.resume()
      maybeEnd()
    }, Math.max(0, done - now))
  })
  // 'end' can arrive before the last paced write fires; only end once every chunk is written.
  upstream.on('end', () => { ended = true; maybeEnd() })
  upstream.on('error', () => res.destroy())
}

const server = http.createServer((req, res) => {
  const target = req.url.startsWith('/api/') ? API : VITE
  const key = readerKey(req)
  const life = { arrived: performance.now(), forwarded: null, headers: null, chunks: 0, bytes: 0 }
  res.on('close', () => {
    if (!res.writableFinished) {
      const t = (x) => (x == null ? '-' : Math.round(x - life.arrived) + 'ms')
      console.log(`ABORTED by client after ${t(performance.now())}: forwarded=${t(life.forwarded)} upstreamHeaders=${t(life.headers)} ` +
        `chunksWritten=${life.chunks} bytes=${life.bytes} bucketAhead=${Math.round((buckets.get(key) || 0) - performance.now())}ms key=${key.slice(0, 30)} ${req.url.slice(-40)}`)
    }
  })
  res.__life = life
  const forward = () => {
    const sentAt = performance.now()
    life.forwarded = sentAt
    const slow = setTimeout(() => console.log(`SLOW no upstream headers after 5s: key=${key.slice(0, 40)} ${req.url.slice(-50)} inflight=${inflight}`), 5000)
    inflight++
    const up = http.request(
      { ...target, method: req.method, path: req.url, headers: { ...endToEnd(req.headers), host: `${target.host}:${target.port}` }, agent },
      (upRes) => {
        clearTimeout(slow)
        life.headers = performance.now()
        const hdrMs = performance.now() - sentAt
        if (hdrMs > 5000) console.log(`LATE headers ${Math.round(hdrMs)}ms key=${key.slice(0, 40)} ${req.url.slice(-50)}`)
        upRes.on('end', () => inflight--)
        upRes.on('error', () => inflight--)
        res.writeHead(upRes.statusCode, endToEnd(upRes.headers))
        if (target === API) throttledPipe(key, upRes, res)
        else upRes.pipe(res)
      },
    )
    up.on('error', (e) => {
      clearTimeout(slow); inflight--
      console.log(`UPSTREAM ERROR ${e.code} ${req.url.slice(-50)}`)
      if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain' })
      res.end('proxy upstream error: ' + e.code)
    })
    req.pipe(up)
  }
  if (LATENCY_MS > 0 && target === API) setTimeout(forward, LATENCY_MS)
  else forward()
})

// Websocket passthrough (Vite HMR) so the dev client doesn't reload-loop.
server.on('upgrade', (req, socket, head) => {
  const up = net.connect(VITE.port, VITE.host, () => {
    up.write(`${req.method} ${req.url} HTTP/1.1\r\n` +
      Object.entries(req.headers).map(([k, v]) => `${k}: ${v}`).join('\r\n') + '\r\n\r\n')
    if (head?.length) up.write(head)
    socket.pipe(up).pipe(socket)
  })
  up.on('error', () => socket.destroy())
  socket.on('error', () => up.destroy())
})

server.keepAliveTimeout = 65_000
server.listen(PORT, '127.0.0.1', () =>
  console.log(`proxy :${PORT} rate=${RATE_MBPS || 'unlimited'}Mbps/reader latency=${LATENCY_MS}ms`))
