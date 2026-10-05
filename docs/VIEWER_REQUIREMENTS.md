# TeleRad — DICOM Viewer Requirements & Backlog (Jira-style)

**Doc ID:** TR-VIEW-REQ · **Version:** 1.0 · **Owner:** Viewer/Imaging team
**Component:** `frontend` (React + Vite + Cornerstone3D), `backend` (Spring Boot) where noted
**Related:** `docs/REQUIREMENTS.md` (product), `PLAN.md` (roadmap) · **Repo:** `github.com/saidmurad/teleradiology`

---

## 0. How to use this document

- Every requirement is a **story** with an ID (`VR-###`), a **Status**, a **Sprint**, and **Acceptance criteria**.
- Status legend: `✅ DONE` (shipped & verified) · `🟡 PARTIAL` (some of it exists) · `⬜ TODO` (not implemented).
- **Estimate** is in points (1/2/3/5/8). **Files** lists where the work lands (real paths in this repo).
- Jira import: create **Epics E1–E10**, then stories in the tables below (ID → Summary), set Fix Version = `Sprint n`.
  See §13 for a paste-ready CSV.

### Definition of Done (applies to every story)
1. Mouse **and** keyboard paths work where applicable.
2. Active state is visually obvious; tooltips present.
3. Works in **full-screen** and, where applicable, in **multi-viewport**.
4. Image is never distorted or unintentionally cropped.
5. Annotations stay aligned across zoom/pan/rotate/resize.
6. Errors surfaced to the user (never silent).
7. Unit tests added under `frontend/src/__tests__/` (Vitest) for logic (not pixels).
8. No PHI leakage in overlays/exports/localStorage (§11).

---

## 1. Implementation baseline (what exists today — verified)

| Area | Current state | Where |
|---|---|---|
| Viewer page | Single-pane + **compare (current vs prior)** with auto prior selection, series pairing, **Sync scroll**, per-pane labels | `frontend/src/pages/StudyViewerPage.tsx` |
| Viewport | Cornerstone3D `RenderingEngine` STACK viewport, tool group, HUD, toolbar, cine, rotate/flip/invert, W/L presets | `frontend/src/components/DicomViewport.tsx` |
| Tools | `WindowLevel`, `Pan`, `Zoom`, `StackScroll`, `Length` registered + mouse bindings | `frontend/src/cornerstone/initCornerstone.ts` |
| Loader | WADO-URI (`wadouri:`) with Bearer auth; `useLegacyMetadataProvider: true`; JPEG-LS decodes in-browser | `initCornerstone.ts`, `api.ts` |
| Series rail | Thumbnails rendered in-browser (works for JPEG-LS), active highlight, description + count | `components/SeriesThumbnail.tsx`, `StudyViewerPage.tsx` |
| Priors | Prior discovery + interval label + series matching | `frontend/src/priors.ts` |
| W/L presets | Modality-aware presets (Wide/Narrow/…) | `frontend/src/wlPresets.ts` |
| Imperative viewport API | `goToFraction()` only (used for sync scroll) | `DicomViewportHandle` |
| Keyboard (today) | `↑/↓/←/→` slice, `Space` cine, `I` invert, `R` reset, `+/-` zoom, `0` fit, `[`/`]` rotate, `L/Z/P/W` tools | `DicomViewport.tsx` (~L544-570) |
| Auth/session | JWT; expired session → **401 JSON** + SPA redirect to `/login?expired=1` | `backend SecurityConfig`, `frontend/src/api.ts` |
| Caching | `index.html` `no-store`; hashed assets `immutable` | `frontend/nginx.conf` |
| Tests | Vitest suites for api/auth/priors/wlPresets/types/shell/reportTemplates | `frontend/src/__tests__/` |

**Recently fixed (context, no work needed):** viewport **0-height collapse** in single-pane mode (`styles.css`: `.viewer-layout{min-height:70vh}` + `.viewer-pane .viewport{min-height:360px}`); stale-chunk hang after redeploys; ambiguous 403 on expired tokens.

---

## 2. Epics

| Epic | Title | Spec §| Sprint(s) |
|---|---|---|---|
| **E1** | Viewer shell, true full-screen, sizing & navigation | 2, 6, 20, 22, 36 | 1 |
| **E2** | Interaction model: tools, gestures, keyboard | 7, 8, 9, 10, 31 | 1–2 |
| **E3** | Contextual UI: auto-hide overlays & drawers | 19, 20, 21, 22, 23 | 2 |
| **E4** | Clinical orientation & calibration | 11, 35 | 2 |
| **E5** | Annotation system (persistent, selectable) | 12–17 | 3 |
| **E6** | Multi-viewport layouts & active viewport | 25, 26 | 4 |
| **E7** | Synchronization (spatial, cross-series) | 27 | 4 |
| **E8** | MPR (axial/sagittal/coronal + crosshair) | 28, 29 | 5 |
| **E9** | Information surfaces (patient/study/tags) | 24 | 6 |
| **E10** | Export, print, cine, 3D foundation | 32, 33, 43 | 6 |

---

## 3. Sprint 0 — Hardening (✅ DONE, shipped)

| ID | Story | Status | Files |
|---|---|---|---|
| VR-001 | Viewer viewport must not collapse to 0 height in single-pane mode | ✅ DONE | `frontend/src/styles.css` |
| VR-002 | Deploys must not strand clients on stale chunk hashes (`no-store` index, immutable assets) | ✅ DONE | `frontend/nginx.conf` |
| VR-003 | Expired/invalid session must return 401 (not 403) and redirect the SPA to login | ✅ DONE | `backend .../config/SecurityConfig.java`, `frontend/src/api.ts` |

---

## 4. Sprint 1 — Viewer foundation (goal: a true image-first, full-screen workspace)

**Sprint goal:** the image fills the viewport, resizes correctly, and every navigation/zoom/WL control behaves predictably.

| ID | Type | Story / Task | Status | Est | Files | Acceptance criteria |
|---|---|---|---|---|---|---|
| VR-100 | Story | `DicomViewerShell` component: header overlay + viewport grid + toolbar + status overlay | ⬜ TODO | 5 | `components/viewer/DicomViewerShell.tsx` (new) | Shell renders existing viewer 1:1; no visual regression vs `StudyViewerPage`; route `/studies/:id` uses it |
| VR-101 | Story | **True full-screen** mode using `100vw × 100vh` (not "bigger page") | ⬜ TODO | 3 | `DicomViewerShell.tsx`, `styles.css` | Enter/exit via button + `F11`-independent API (`requestFullscreen`); study state preserved (slice, zoom, WL, annotations); canvas recalculated on resize |
| VR-102 | Story | Resize handling: `ResizeObserver` → `engine.resize()` + transform recalculation | 🟡 PARTIAL (ResizeObserver exists for init) | 2 | `components/DicomViewport.tsx` | Window resize and full-screen toggle keep image fit, annotations aligned; no letterbox drift |
| VR-103 | Story | **Fit / 100% / Fill** modes + double-click toggle `Fit ↔ Previous Zoom` | 🟡 PARTIAL (`Fit` only; `0` key) | 3 | `DicomViewport.tsx`, `styles.css` | Fit shows the complete image (no crop), aspect preserved; 100% = native scale; Fill maximises without cropping; toolbar + status show current mode |
| VR-104 | Story | Zoom: wheel, `+/-`, toolbar, **cursor-centered**, visible %, 10–800% range, transient emphasis for ~1.5–2 s | 🟡 PARTIAL (wheel/keys/toolbar exist; centering + emphasis TODO) | 5 | `DicomViewport.tsx` | Cursor-centred zoom within ±1 px feel; indicator appears during interaction then fades; status shows `Zoom: 125%` |
| VR-105 | Story | Pan: Pan tool + secondary-button pan when zoomed; Reset View | 🟡 PARTIAL | 2 | `DicomViewport.tsx` | Pan never changes WL; Reset restores zoom/pan/WL/rotation, **keeps** annotations |
| VR-106 | Story | Slice navigation: Prev/Next, **slider**, wheel, ↑/↓, counter `Slice 14 / 27`, cine | 🟡 PARTIAL (no slider; counter exists) | 5 | `DicomViewport.tsx` | Slider drag is smooth (<50 ms lag), wheel unambiguous per active tool, cine start/stop; counter always correct |
| VR-107 | Story | Mouse-wheel routing rule: scroll = slice **unless** Zoom tool active (then wheel = zoom) | ⬜ TODO | 2 | `DicomViewport.tsx` | Documented + tested matrix of (tool × wheel) behaviour |
| VR-108 | Task | Slim, non-blocking status overlay: series/modality/slice (TL), orientation (TR), `W:/L:` (BL), `Zoom:` (BR) | 🟡 PARTIAL (HUD exists, not layered) | 3 | `components/viewer/ViewportStatus.tsx` (new) | Overlays are subtle, never cover >15% of image area; readable at 1080p and 4K |
| VR-109 | Task | Persist per-viewer UI preferences (layout, annotations visibility) in **session** storage (no PHI) | ⬜ TODO | 2 | `components/viewer/useViewerState.ts` (new) | Reload keeps layout/visibility; no PHI keys stored |

**Sprint 1 exit criteria:** all VR-100…VR-109 acceptance criteria pass; manual test script executed on: single-pane JPEG-LS MR, compare MR, multi-frame series.

---

## 5. Sprint 2 — Contextual UI (goal: UI appears on demand, never fights the image)

| ID | Story / Task | Status | Est | Files | Acceptance criteria |
|---|---|---|---|---|---|
| VR-200 | Auto-hide framework with edge zones (top=header+toolbar, left=series, right=tools/patient, bottom=slice/status) | ⬜ TODO | 5 | `components/viewer/useAutoHide.ts` (new), `styles.css` | Zones reveal on hover; drawers **overlay** (never re-layout) the image |
| VR-201 | Auto-hide **safety**: never hide while drawing/measuring/panning/WL/zooming/slider-drag/dialog-open | ⬜ TODO | 3 | `useAutoHide.ts` | `isInteracting` blocks hiding; unit test for each interaction state |
| VR-202 | Timing: toolbar show 150–200 ms, hide 300–500 ms, drawer show 150–250 ms, zoom indicator 1.5–2 s | ⬜ TODO | 2 | `useAutoHide.ts` | Values centralised as config; test asserts ranges |
| VR-203 | Header overlay: normal vs full-screen collapsed form; fades when pointer leaves top region | 🟡 PARTIAL (static header) | 3 | `ViewerHeaderOverlay.tsx` (new), `pages/AppShell.tsx` | Full-screen header shows `TeleRad · Patient · Study · Date`; Back to Worklist / Report remain reachable |
| VR-204 | Series drawer: search/filter, active highlight, thumbnail, description, modality, count, (optional) drag-to-viewport | 🟡 PARTIAL (rail exists, no search/drag) | 5 | `components/viewer/SeriesDrawer.tsx` (new), `SeriesThumbnail.tsx` | Filter matches description/modality; active series obvious (non-colour-only indicator) |
| VR-205 | Toolbar: Select, W/L, Zoom, Pan, Length, Angle, ROI, Undo, Redo, Layout, MPR, 3D, Export, Print, Patient Info | 🟡 PARTIAL (W/L, Zoom, Pan, Length, Invert, rotate/flip, presets, cine) | 5 | `components/viewer/ViewerToolbar.tsx` (new) | Every button has tooltip + shortcut hint; disabled states for unavailable actions |
| VR-206 | Keyboard shortcut map (see §9) incl. dialog/text-field guard | 🟡 PARTIAL (`L/Z/P/W`, arrows, space, `R`, `0`, `+/-`, `[/]`) | 3 | `useViewerShortcuts.ts` (new) | Shortcuts inert inside inputs/dialogs; `Esc` cancels current tool/drawing |
| VR-207 | Accessibility pass on all viewport controls (focus ring, aria-labels, contrast, non-colour states) | ⬜ TODO | 3 | `components/viewer/*`, `styles.css` | Keyboard tab order complete; axe/`vitest-axe`-style assertions on toolbar |

**Sprint 2 exit criteria:** image occupies ≥85 % of screen when idle; every control reachable by keyboard; no control disappears mid-interaction.

---

## 6. Sprint 3 — Annotation system (goal: measurements are first-class, deletable objects)

**Root problem to solve:** today a finished `LengthTool` measurement cannot be deleted or selected. This sprint replaces "tool output" with a **persistent annotation model**.

| ID | Story / Task | Status | Est | Files | Acceptance criteria |
|---|---|---|---|---|---|
| VR-300 | Annotation data model (`Annotation` union, image-space geometry) + store (add/select/delete/clear, per viewport) | ⬜ TODO | 5 | `viewer/annotations/model.ts`, `store.ts` (new), `types.ts` | Geometry stored in **image coordinates**; zoom/pan never causes drift (unit test with transform matrix) |
| VR-301 | Annotation rendering layer bound to viewport transforms (Cornerstone annotation state **or** SVG overlay — see Q-1) | ⬜ TODO | 5 | `viewer/annotations/AnnotationLayer.tsx` (new) | Annotations redraw on zoom/pan/rotate/resize; independent of image transform state |
| VR-302 | **Length** tool lifecycle: select → activate → draw → create → auto-return to Select | 🟡 PARTIAL (draws via Cornerstone; not persistent/selectable) | 5 | `AnnotationLayer.tsx`, `installTools.ts` | After mouse-up the measurement persists; tool returns to Select; value shown `42.6 mm` |
| VR-303 | Measurement uses **DICOM calibration**: `distance = pixel distance × Pixel Spacing` (mm), MPR uses voxel spacing | ⬜ TODO | 3 | `viewer/measure/calibration.ts` (new) | Verified against a study with known PixelSpacing (test fixture); never derived from screen pixels |
| VR-304 | **Selection**: click annotation → selected state; context menu (Length, value, Edit, Hide, Delete) | ⬜ TODO | 5 | `AnnotationLayer.tsx`, `AnnotationContextMenu.tsx` (new) | Selected annotation visibly distinct; right-click opens menu anchored to pointer |
| VR-305 | **Deletion**: `Delete`/`Backspace` on selection, right-click → Delete, toolbar Delete | ⬜ TODO | 3 | `useViewerShortcuts.ts`, `AnnotationLayer.tsx` | Deletes only the selected annotation; other annotations untouched (regression test) |
| VR-306 | `Esc` cancels an unfinished measurement | 🟡 PARTIAL (Cornerstone default) | 2 | `AnnotationLayer.tsx` | Pressing `Esc` mid-draw leaves no partial annotation |
| VR-307 | Visibility toggle `Annotations 👁` (hide ≠ delete) | ⬜ TODO | 2 | `ViewerToolbar.tsx`, `store.ts` | Toggle hides all; re-toggle restores identical geometry |
| VR-308 | `Clear Selected` / `Clear All` (Clear All requires confirmation) | ⬜ TODO | 2 | `AnnotationManager.tsx` (new) | Confirm dialog; clearing removes only annotations, never the image transform |
| VR-309 | **Undo/Redo** for annotation ops (`Ctrl/Cmd+Z`, `Ctrl/Cmd+Shift+Z`, toolbar ↶ ↷) | ⬜ TODO | 5 | `store.ts` (history stack), `ViewerToolbar.tsx` | Undo/redo covers add/delete/hide for Length (and later Angle/ROI); stack depth ≥50 |
| VR-310 | **Annotation manager panel**: list with type + value, click selects in viewport, `Clear all` | ⬜ TODO | 3 | `AnnotationManager.tsx` | List ↔ viewport selection is two-way; scroll-sync with many items |
| VR-311 | Angle + ROI annotation types on the same model | ⬜ TODO | 5 | `model.ts`, `AnnotationLayer.tsx` | Angle in degrees; ROI shows area in mm² using calibration |

**Sprint 3 exit criteria:** create → select → delete → undo → redo → clear-all works on a JPEG-LS MR with 3 annotations; measurements survive tool switching and full-screen toggles.

---

## 7. Sprint 4 — Multi-viewport & synchronization

| ID | Story / Task | Status | Est | Files | Acceptance criteria |
|---|---|---|---|---|---|
| VR-400 | Layout manager: `1×1`, `1×2`, `2×2` (grid-based, resizable) | ⬜ TODO | 5 | `viewer/layout/LayoutGrid.tsx` (new) | Layout switch preserves existing viewport state where the slot persists |
| VR-401 | Active viewport concept (click to activate; visible border) | ⬜ TODO | 2 | `store.ts` | Toolbar actions target the active viewport only |
| VR-402 | Per-viewport independent state (series, slice, zoom, pan, WL, fit, annotations) | 🟡 PARTIAL (two fixed panes only) | 5 | `ViewportState` in `store.ts` | Changing W/L in viewport 2 does not affect viewport 1 (test) |
| VR-403 | Assign series to a viewport (from SeriesDrawer; drag or click-to-active) | ⬜ TODO | 3 | `SeriesDrawer.tsx`, `store.ts` | Any series can be loaded into any viewport; loading indicator per viewport |
| VR-404 | Sync Scroll toggle, **spatial sync** (image position/normal, spacing-aware) rather than index matching | 🟡 PARTIAL (index/fraction sync exists) | 5 | `viewer/sync/useSpatialSync.ts` (new), `priors.ts` | Series with different slice counts/spacing stay anatomically matched; toggle off stops all coupling |
| VR-405 | Pane headers: series description, slice counter, sync indicator | 🟡 PARTIAL (compare labels) | 2 | `ViewportHeader.tsx` (new) | Always clear which series is in which pane |

---

## 8. Sprint 5 — MPR (volumetric review)

| ID | Story / Task | Status | Est | Files | Acceptance criteria |
|---|---|---|---|---|---|
| VR-500 | Volume model from a series (geometry + voxel spacing + origin) | ⬜ TODO | 8 | `viewer/mpr/volume.ts` (new) | Rejects non-volumetric series with a clear message |
| VR-501 | Axial / Sagittal / Coronal reconstruction | ⬜ TODO | 8 | `viewer/mpr/MprViewport.tsx` (new) | Each plane correct per DICOM orientation; spacing respected (no stretched anatomy) |
| VR-502 | Crosshair + linked position across planes | ⬜ TODO | 5 | `MprViewport.tsx` | Moving crosshair in one plane updates the others within 1 slice |
| VR-503 | `Link views` toggle (crosshair sync can be disabled) | ⬜ TODO | 2 | `store.ts` | Unlinked planes browse independently |
| VR-504 | Measurements in MPR use voxel spacing (mm), not pixel spacing | ⬜ TODO | 3 | `calibration.ts` | Length in sagittal measures correctly (test vs known phantom) |
| VR-505 | (Future) Oblique plane | ⬜ TODO | 8 | `mpr/` | Backlog spike; not in acceptance for P3 |

---

## 9. Sprint 6 — Workflow: information, export, print, cine, 3D

| ID | Story / Task | Status | Est | Files | Acceptance criteria |
|---|---|---|---|---|---|
| VR-600 | Patient Info drawer (patient/study/series sections) | 🟡 PARTIAL (header shows basics) | 5 | `viewer/PatientInfoDrawer.tsx` (new) | Shows §24 fields; no PHI beyond what the user is authorised to see |
| VR-601 | DICOM Tags drawer with search + copy | ⬜ TODO | 3 | `viewer/DicomTagsDrawer.tsx` (new), `backend` metadata endpoint | Tag list searchable; copy to clipboard works per tag/line |
| VR-602 | Export menu: viewport PNG/JPEG, layout PNG, image/series/study DICOM | 🟡 PARTIAL (no export UI; API serves instances) | 8 | `viewer/ExportDialog.tsx` (new), `backend` download endpoints | PNG/JPEG include overlays optionally; DICOM export streams original objects unchanged; progress + cancel for large sets |
| VR-603 | Print preview dialog (layout + patient/study header + orientation + measurements + page size/orientation) | ⬜ TODO | 8 | `viewer/PrintPreviewDialog.tsx` (new) | Print output matches preview; `Cancel`/`Print`; portrait/landscape honoured |
| VR-604 | Cine controls in toolbar (play/pause, fps) for multi-frame + stacks | 🟡 PARTIAL (cine exists in viewport) | 3 | `ViewerToolbar.tsx` | FPS slider visible; plays across active viewport only |
| VR-605 | 3D architecture foundation (volume → renderer interface, no UI commitment) | ⬜ TODO | 5 | `viewer/three/README.md`, `VolumeRenderer.ts` (new) | Interface documented; feature-flagged off |
| VR-606 | Export/DICOM audit trail (who exported what, when) | ⬜ TODO | 3 | `backend` audit | Every export writes an audit event with actor/study/scope |

---

## 10. Cross-cutting requirements (apply to every sprint)

| ID | Requirement | Status | Acceptance criteria |
|---|---|---|---|
| VR-700 | DICOM-derived **orientation markers** (never hard-coded from viewport type) | ⬜ TODO | Markers (A/P/L/R/H/F) computed from `ImageOrientationPatient` + transforms; rotate/flip updates them correctly |
| VR-701 | **Loading / error / unsupported** states: `Loading image…`, progress for big series, `Unable to load this series [Retry]`, `This DICOM object is not supported by this viewer`, `Export failed [Retry]` | 🟡 PARTIAL (loading + error exist in viewport) | Never fail silently; every failure surfaces an action |
| VR-702 | **Privacy**: minimal PHI in overlays; no PHI in localStorage unless explicitly secured; state cleared on leaving a study; PHI in screenshots/exports is explicit | 🟡 PARTIAL (auth + minimal HUD) | Review checklist per release; localStorage keys contain no PHI |
| VR-703 | **State preservation**: switching tools/layout/full-screen never destroys study state | 🟡 PARTIAL | Automated test: annotate → toggle full-screen → toggle layout → assert annotations & transforms intact |
| VR-704 | Rendering correctness: pixel aspect ratio preserved; `PixelSpacing` respected; image vs annotation transforms separated; transforms recalculated after resize | 🟡 PARTIAL | Unit tests for transform math; visual fixture test on a known study |
| VR-705 | Reset semantics separated: `Reset View` (zoom/pan/WL/rotation; keeps annotations) vs `Clear Annotations` (annotations only) | 🟡 PARTIAL (`R` resets view) | Both actions exist and are distinct in the UI |
| VR-706 | Performance: first frame < 2 s for ≤50-instance series on hospital LAN; tool interactions ≥30 fps | ⬜ TODO | Measured on the reference study; frame budget documented |
| VR-707 | Accessibility (§39): focus states, tooltips, contrast, non-colour states, keyboard dialogs, screen-reader labels | ⬜ TODO | Automated a11y assertions on toolbar + drawers |

---

## 11. Command / API model (viewer command bus)

Implement as a store facade (see Q-1) so UI, keyboard, and future automation share one surface:

```ts
// frontend/src/viewer/commands.ts
setActiveTool(tool: ViewerMode): void
setActiveViewport(id: string): void
zoomIn(id: string): void ; zoomOut(id: string): void ; setZoom(id: string, value: number): void
pan(id: string, dx: number, dy: number): void
setWindowLevel(id: string, width: number, center: number): void
setSlice(id: string, index: number): void ; nextSlice(id) ; previousSlice(id)
fitViewport(id) ; resetViewport(id)
addAnnotation(a: Annotation) ; selectAnnotation(id) ; deleteAnnotation(id) ; clearAnnotations(id)
undo() ; redo()
setLayout(layout: LayoutMode) ; toggleSync() ; toggleAnnotations()
openSeriesDrawer() ; openPatientInfo() ; openDicomTags()
enterFullscreen() ; exitFullscreen()
exportViewport(kind: 'png'|'jpeg') ; exportLayout() ; printLayout()
```

Today only `goToFraction()` is exposed (`DicomViewportHandle`) — VR-100/VR-402 must move to this model.

---

## 12. New files to create (all under `frontend/src/`)

```
viewer/
  DicomViewerShell.tsx        ViewerHeaderOverlay.tsx   ViewerToolbar.tsx
  ViewportGrid.tsx            ViewportHeader.tsx        ViewportStatus.tsx
  SeriesDrawer.tsx            RightToolsDrawer.tsx      PatientInfoDrawer.tsx
  DicomTagsDrawer.tsx         AnnotationManager.tsx     ExportDialog.tsx
  PrintPreviewDialog.tsx      AnnotationContextMenu.tsx
  state/store.ts              state/useViewerState.ts   state/useAutoHide.ts
  state/useViewerShortcuts.ts
  annotations/model.ts        annotations/AnnotationLayer.tsx  annotations/installTools.ts
  measure/calibration.ts
  layout/LayoutGrid.tsx
  sync/useSpatialSync.ts
  mpr/volume.ts               mpr/MprViewport.tsx
  three/VolumeRenderer.ts
```

Backend additions (Spring Boot): instance/series/study **DICOM download** endpoints (streaming, audited), metadata-by-tag endpoint for the tags drawer, export audit events.

---

## 13. Jira import (CSV)

```csv
Issue Type,Issue Key/ID,Summary,Epic Link,Sprint,Story Points,Labels
Epic,E1,Viewer shell full-screen sizing and navigation,,,,
Epic,E2,Interaction model tools gestures keyboard,,,,
Epic,E3,Contextual auto-hide UI,,,,
Epic,E4,Clinical orientation and calibration,,,,
Epic,E5,Annotation system,,,,
Epic,E6,Multi-viewport layouts,,,,
Epic,E7,Spatial synchronization,,,,
Epic,E8,MPR,,,,
Epic,E9,Information surfaces,,,,
Epic,E10,Export print cine 3D foundation,,,,
Story,VR-100,DicomViewerShell component,E1,Sprint 1,5,frontend;viewer
Story,VR-101,True full-screen 100vw x 100vh,E1,Sprint 1,3,frontend;viewer
Story,VR-103,Fit 100% Fill modes,E1,Sprint 1,3,frontend;viewer
Story,VR-104,Cursor-centered zoom with indicator,E2,Sprint 1,5,frontend;viewer
Story,VR-106,Slice slider and cine,E2,Sprint 1,5,frontend;viewer
Story,VR-200,Auto-hide framework with edge zones,E3,Sprint 2,5,frontend;ux
Story,VR-204,Series drawer with search,E3,Sprint 2,5,frontend;ux
Story,VR-206,Keyboard shortcut map,E2,Sprint 2,3,frontend;ux
Story,VR-300,Annotation model and store,E5,Sprint 3,5,frontend;annotations
Story,VR-302,Persistent length annotation lifecycle,E5,Sprint 3,5,frontend;annotations
Story,VR-305,Delete and Backspace deletion of selection,E5,Sprint 3,3,frontend;annotations
Story,VR-309,Undo redo for annotations,E5,Sprint 3,5,frontend;annotations
Story,VR-400,Layout manager 1x1 1x2 2x2,E6,Sprint 4,5,frontend;viewer
Story,VR-404,Spatial sync scroll,E7,Sprint 4,5,frontend;viewer
Story,VR-501,Axial sagittal coronal MPR,E8,Sprint 5,8,frontend;mpr
Story,VR-602,Export menu PNG JPEG DICOM,E10,Sprint 6,8,frontend;backend
Story,VR-603,Print preview dialog,E10,Sprint 6,8,frontend
```

---

## 14. Open questions (decide before Sprint 1 starts)

| Q | Question | Impact | Recommendation |
|---|---|---|---|
| Q-1 | Annotation rendering: reuse **Cornerstone annotation state/tools** vs a custom SVG layer? | Sprint 3 architecture | Cornerstone `@cornerstonejs/tools` annotation state (persistent, selectable, deletable) + thin adapter to our `Annotation` model |
| Q-2 | Viewer state management: extend React context vs add **Zustand** | Sprints 1/4 | Zustand (small, no boilerplate) inside `viewer/state/`; keeps commands trivial |
| Q-3 | Volume/MPR library: build on Cornerstone `volume` + `MPR` viewports vs custom reslicer | Sprint 5 | Cornerstone volume + `ViewportType.ORTHOGRAPHIC` (already a dependency family) |
| Q-4 | DICOM export policy: full metadata (PHI) vs de-identified | Sprint 6 | Export original objects, but **audit + explicit PHI warning**; optional de-identify later |
| Q-5 | Where do viewer preferences live (per user, server-side)? | Sprints 1/4 | Start session-only; server-side prefs in a later epic |
| Q-6 | Print: browser print of an image-only page vs server-side render | Sprint 6 | Browser print of a dedicated route (no new backend) |

---

## 15. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Cornerstone tool state and our annotation model diverge | Medium | Single adapter layer; contract tests (VR-300/VR-302) |
| Spatial sync produces false confidence on different geometries | Medium | Compare `ImagePositionPatient` + spacing; warn when geometries differ |
| MPR on non-volumetric/irregular series | High | Explicit validation + user-facing rejection message (VR-500) |
| Export of very large studies blocks the UI | Medium | Stream + progress + cancel (VR-602) |
| Auto-hide hides a control during an unusual interaction | Medium | `isInteracting` guard + escape hatch (pin toolbar) |
| PHI leakage via screenshots/exports | Medium | VR-702 checklist + export audit (VR-606) |
