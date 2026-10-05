#!/usr/bin/env python3
"""Headless viewer-render check (P1).

Why this exists
---------------
`api-viewer` proves the pixels can be *fetched* (HTTP 200, DICM magic). It does
not prove the browser can *render* them: a broken frontend bundle, a crashed
JS chunk or a WebGL failure all leave the pixels fetchable while the operator
sees a blank page. This module drives real headless Chrome (Browserless) to the
study route, injects the auth token **before the app scripts run**, waits for the
SPA to mount and then asserts the three observable facts:

  (a) ``#root`` has at least one child   — the bundle mounted
  (b) at least one ``<canvas>`` exists   — a viewport was created
  (c) zero uncaught ``pageerror`` events — no JS blew up

It captures a PNG screenshot as evidence.

This is a pure module: no FastAPI imports, so the selftest and the console can
both import it. It never logs the browserless token.
"""
from __future__ import annotations

import base64
import json
import time
from pathlib import Path

import httpx

# Browserless enforces a 60 s cap per request; stay comfortably under it.
BROWSERLESS_TIMEOUT_SECONDS = 55.0
# How long the in-page code waits for the SPA to mount and create a canvas.
DEFAULT_WAIT_MS = 30_000


def build_function_code(app_url: str, study_id: str, auth_json: str,
                        wait_ms: int = DEFAULT_WAIT_MS,
                        block_url_regex: str | None = None) -> str:
    """Return the ESM module Browserless runs for one render check.

    All values are JSON-encoded into the source so a study id / URL can never
    break out of the string. The auth object is passed as a page-function
    argument (a JSON literal) and installed via ``evaluateOnNewDocument`` so it
    is present before any app script runs.

    ``block_url_regex`` is a *test hook* used by the selftest to simulate a
    broken frontend bundle (abort matching requests). It is ``None`` in every
    production path.
    """
    url = f"{app_url.rstrip('/')}/studies/{study_id}"
    return (
        "export default async function ({ page }) {\n"
        "  const AUTH = " + json.dumps(auth_json) + ";\n"
        "  const URL = " + json.dumps(url) + ";\n"
        "  const WAIT_MS = " + str(int(wait_ms)) + ";\n"
        "  const BLOCK = " + json.dumps(block_url_regex) + ";\n"
        "  const pageErrors = [];\n"
        "  page.on('pageerror', (err) => {\n"
        "    pageErrors.push(String((err && err.message) || err));\n"
        "  });\n"
        "  await page.evaluateOnNewDocument((auth) => {\n"
        "    window.localStorage.setItem('telerad_auth', auth);\n"
        "  }, AUTH);\n"
        "  if (BLOCK) {\n"
        "    const re = new RegExp(BLOCK);\n"
        "    await page.setRequestInterception(true);\n"
        "    page.on('request', (req) => {\n"
        "      if (re.test(req.url())) { req.abort().catch(() => {}); }\n"
        "      else { req.continue().catch(() => {}); }\n"
        "    });\n"
        "  }\n"
        "  await page.setViewport({ width: 1440, height: 900 });\n"
        "  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 30000 });\n"
        "  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));\n"
        "  const deadline = Date.now() + WAIT_MS;\n"
        "  let rootChildren = -1;\n"
        "  let canvasCount = 0;\n"
        "  while (Date.now() < deadline) {\n"
        "    const state = await page.evaluate(() => {\n"
        "      const root = document.getElementById('root');\n"
        "      return {\n"
        "        root: root ? root.children.length : -1,\n"
        "        canvas: document.querySelectorAll('canvas').length,\n"
        "      };\n"
        "    });\n"
        "    rootChildren = state.root;\n"
        "    canvasCount = state.canvas;\n"
        "    if (rootChildren > 0 && canvasCount > 0) break;\n"
        "    await sleep(500);\n"
        "  }\n"
        "  await sleep(1200);\n"  # let Cornerstone finish painting for the screenshot
        "  let screenshot = '';\n"
        "  try {\n"
        "    screenshot = await page.screenshot({ encoding: 'base64' });\n"
        "  } catch (e) {\n"
        "    pageErrors.push('screenshot-failed: ' + String((e && e.message) || e));\n"
        "  }\n"
        "  return {\n"
        "    rootChildren,\n"
        "    canvasCount,\n"
        "    pageErrors,\n"
        "    screenshot,\n"
        "    finalUrl: page.url(),\n"
        "  };\n"
        "}\n"
    )


def _sanitize(text: str, token: str) -> str:
    """Never let the browserless token reach a log, report or screenshot name."""
    if token:
        text = text.replace(token, "<redacted>")
    return text[:300]


def _cannot_run(url: str, start: float, reason: str) -> dict:
    return {
        "ok": False,
        "ran": False,
        "rootChildren": -1,
        "canvasCount": 0,
        "pageErrors": [],
        "screenshot": None,
        "seconds": round(time.monotonic() - start, 2),
        "url": url,
        "reason": reason,
    }


def _one_attempt(app_url: str, study_id: str, auth_str: str, browserless_url: str,
                 browserless_token: str, out_dir: Path, wait_ms: int,
                 block_url_regex: str | None, token_for_redaction: str) -> dict:
    """One browserless round-trip. Returns a parsed result or a cannot-run dict."""
    url = f"{app_url.rstrip('/')}/studies/{study_id}"
    code = build_function_code(app_url, study_id, auth_str, wait_ms=wait_ms,
                               block_url_regex=block_url_regex)
    endpoint = f"{browserless_url.rstrip('/')}/function"

    try:
        with httpx.Client(timeout=BROWSERLESS_TIMEOUT_SECONDS) as client:
            resp = client.post(endpoint, params={"token": browserless_token},
                               json={"code": code, "context": {}})
    except Exception as exc:  # unreachable host, TLS, timeout, connection reset
        # seconds is set by the caller; this inner value is a placeholder.
        return _cannot_run(url, 0.0,
                           _sanitize(f"{type(exc).__name__}: {exc}", token_for_redaction))

    if resp.status_code != 200:
        return _cannot_run(url, 0.0,
                           f"browserless HTTP {resp.status_code}: "
                           + _sanitize(resp.text, token_for_redaction))

    try:
        data = resp.json()
    except Exception:
        return _cannot_run(url, 0.0, "browserless returned a non-JSON response")

    if not isinstance(data, dict):
        return _cannot_run(url, 0.0, "browserless returned an unexpected response shape")

    if data.get("error"):
        return _cannot_run(url, 0.0,
                           _sanitize(str(data["error"]), token_for_redaction))

    root_children = int(data.get("rootChildren", -1))
    canvas_count = int(data.get("canvasCount", 0))
    page_errors = [str(e) for e in (data.get("pageErrors") or [])]
    final_url = str(data.get("finalUrl") or url)

    screenshot_path = None
    screenshot_error = None
    shot_b64 = data.get("screenshot") or ""
    if shot_b64:
        try:
            raw = base64.b64decode(shot_b64)
            out_dir.mkdir(parents=True, exist_ok=True)
            screenshot_path = out_dir / f"viewer-render-{study_id}-{int(time.time() * 1000)}.png"
            screenshot_path.write_bytes(raw)
        except Exception as exc:
            # A screenshot that cannot be written is an evidence-file problem, NOT a
            # render failure — never let it flip a healthy viewer to "fail".
            screenshot_path = None
            screenshot_error = f"{type(exc).__name__}: {exc}"[:160]

    ok = root_children >= 1 and canvas_count > 0 and not page_errors
    problems: list[str] = []
    if root_children < 1:
        problems.append("the app did not mount (the page body stayed empty)")
    if canvas_count < 1:
        problems.append("no image viewport was created (no canvas element)")
    if page_errors:
        first = page_errors[0][:160]
        problems.append(f"the page raised {len(page_errors)} JavaScript error(s): {first}")

    result = {
        "ok": ok,
        "ran": True,
        "rootChildren": root_children,
        "canvasCount": canvas_count,
        "pageErrors": page_errors,
        "screenshot": str(screenshot_path) if screenshot_path else None,
        "url": final_url,
    }
    if not ok:
        result["reason"] = "; ".join(problems) or "the viewer did not render"
    if screenshot_error:
        result["screenshotError"] = screenshot_error
    return result


def check_viewer_render(
    app_url: str,
    study_id: str,
    auth_json,
    browserless_url: str,
    browserless_token: str,
    out_dir,
    wait_ms: int = DEFAULT_WAIT_MS,
    block_url_regex: str | None = None,
) -> dict:
    """Open the study route in headless Chrome and assert that it renders.

    ``auth_json`` is the *full* login response object (dict) or its JSON string;
    the app stores it verbatim under ``localStorage['telerad_auth']``.

    Returns a dict with:
      ``ok``            bool — True only when all three assertions hold
      ``ran``           bool — False when the check could not run at all
                               (browserless unreachable / timed out); the caller
                               must treat this as a warning, never a pass
      ``rootChildren``  int
      ``canvasCount``   int
      ``pageErrors``    list[str]
      ``screenshot``    str | None — path to the saved PNG
      ``screenshotError`` str | None — set when the PNG could not be written
                               (an evidence problem, never a render failure)
      ``seconds``       float
      ``url``           str
      ``reason``        str — present when ok is False or the check could not run
    """
    start = time.monotonic()
    out_dir = Path(out_dir)
    run_url = f"{app_url.rstrip('/')}/studies/{study_id}"

    if not browserless_token:
        return _cannot_run(run_url, start, "browserless token is not configured")

    auth_str = json.dumps(auth_json) if isinstance(auth_json, (dict, list)) else str(auth_json)

    result = _one_attempt(app_url, study_id, auth_str, browserless_url,
                          browserless_token, out_dir, wait_ms, block_url_regex,
                          browserless_token)
    result["seconds"] = round(time.monotonic() - start, 2)
    return result


if __name__ == "__main__":  # tiny manual probe: python viewer_render.py <study_id>
    import argparse
    import os

    ap = argparse.ArgumentParser(description="Manual viewer-render probe")
    ap.add_argument("study_id")
    ap.add_argument("--app-url", default=os.environ.get("TELERAD_APP_URL",
                                                        "https://telerad.13.140.56.88.sslip.io"))
    ap.add_argument("--out", default=str(Path(__file__).parent / "selftest-evidence"))
    ap.add_argument("--block-url-regex", default=None,
                    help="test hook: abort matching requests to simulate a broken bundle")
    args = ap.parse_args()

    api_base = os.environ.get("TELERAD_API", "http://127.0.0.1:18080")
    login = httpx.post(f"{api_base}/api/auth/login", timeout=15, json={
        "email": os.environ["TELERAD_ADMIN_EMAIL"],
        "password": os.environ["TELERAD_ADMIN_PASSWORD"],
    }).json()
    result = check_viewer_render(
        args.app_url, args.study_id, login,
        os.environ.get("BROWSERLESS_URL", "http://127.0.0.1:3000"),
        os.environ.get("BROWSERLESS_TOKEN", ""),
        args.out,
        block_url_regex=args.block_url_regex,
    )
    print(json.dumps({k: v for k, v in result.items() if k != "screenshot"}, indent=2))
    print("screenshot:", result["screenshot"])
