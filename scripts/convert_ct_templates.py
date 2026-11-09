#!/usr/bin/env python3
"""Convert the CT "normal format" Word templates into TeleRad report templates.

  1. soffice --headless --convert-to "txt:Text (encoded):UTF8" each .doc/.docx (keep folder layout)
  2. python3 scripts/convert_ct_templates.py <txt-root> > frontend/src/ctNormalTemplates.ts

Each document becomes one template:
  body       = EXAM / CLINICAL DATA / TECHNIQUE / FINDINGS (bullets as "- ")
  impression = numbered IMPRESSION / Conclusion bullets
Header lines (date, NAME/AGE/SEX, referral) are dropped: the report already carries patient details.
"""
import json, os, re, sys

# file stem -> (template name, body part). Folder decides the body part group.
NAMES = {
    'Abdominal and pelvic ct with contrast': ('CT Abdomen & Pelvis — With Contrast (Normal)', 'Abdomen'),
    'Abdominal and pelvic ct with out contrast': ('CT Abdomen & Pelvis — Without Contrast (Normal)', 'Abdomen'),
    'CTU + Adrenals': ('CT Urography + Adrenals (Normal)', 'Abdomen'),
    'angiography/Abdominal and pelvic ct with contrast': ('CT Angiography — Abdomen & Pelvis (Normal)', 'Angiography'),
    'Head and neck CT angiography': ('CT Angiography — Head & Neck (Normal)', 'Angiography'),
    'Lower limb CT angiography': ('CT Angiography — Lower Limbs (Normal)', 'Angiography'),
    'upper limb CT angiography': ('CT Angiography — Upper Limbs (Normal)', 'Angiography'),
    'Chest ct pulmonary CT angiography': ('CT Pulmonary Angiography (Normal)', 'Chest'),
    'Chest ct with contrast': ('CT Chest — With Contrast (Normal)', 'Chest'),
    'Chest ct without contrast or HRCT': ('CT Chest — Without Contrast / HRCT (Normal)', 'Chest'),
    'Noncontrast CT of Pelvic Bones and hip joints': ('CT Pelvic Bones & Hips — Non-contrast (Normal)', 'Extremity'),
    'Non-contrast CT of the Ankle Joint': ('CT Ankle — Non-contrast (Normal)', 'Extremity'),
    'Non-contrast CT of the Elbow Joint': ('CT Elbow — Non-contrast (Normal)', 'Extremity'),
    'Non-contrast CT of the KNEE Joint': ('CT Knee — Non-contrast (Normal)', 'Extremity'),
    'Non-contrat CT of the Shoulder Joint': ('CT Shoulder — Non-contrast (Normal)', 'Extremity'),
    'Wrist  Jt CT': ('CT Wrist — Non-contrast (Normal)', 'Extremity'),
    'Facial CT with contrast': ('CT Face — With Contrast (Normal)', 'Head & Neck'),
    'Facial CT without contrast': ('CT Face — Without Contrast (Normal)', 'Head & Neck'),
    'HEAD AND NECK': ('CT Head & Neck (Normal)', 'Head & Neck'),
    'HEAD CT with contrast': ('CT Head — With Contrast (Normal)', 'Head & Neck'),
    'HEAD CT with out contrast': ('CT Head — Without Contrast (Normal)', 'Head & Neck'),
    'Neck CT': ('CT Neck (Normal)', 'Head & Neck'),
    'PNS CT WITH CONTRAST': ('CT Paranasal Sinuses — With Contrast (Normal)', 'Head & Neck'),
    'PNS NON CONTRAST CT': ('CT Paranasal Sinuses — Non-contrast (Normal)', 'Head & Neck'),
    'Temporal Ct - NC': ('CT Temporal Bone — Non-contrast (Normal)', 'Head & Neck'),
    'Cervical spine': ('CT Cervical Spine (Normal)', 'Spine'),
    'Lumbar CT': ('CT Lumbar Spine (Normal)', 'Spine'),
    'Lumbosacral CT': ('CT Lumbosacral Spine (Normal)', 'Spine'),
    'Thoracic spine CT': ('CT Thoracic Spine (Normal)', 'Spine'),
    'thoracolumbar CT': ('CT Thoracolumbar Spine (Normal)', 'Spine'),
}

DATE = re.compile(r'^(january|february|march|april|may|june|july|august|september|october|november|december)\s+\d', re.I)
DROP = re.compile(r"^(patient['’]?s?\s*name|name\b|source of refer|age\s*:|sex\s*:)", re.I)
FINDINGS = re.compile(r'^findings?\s*[:\-]*\s*(.*)$', re.I)
IMPRESSION = re.compile(r'^(impression|conclusion)s?\s*[:\-]*\s*(.*)$', re.I)
CLINICAL = re.compile(r'^(clinical\s*(data|information|history))\s*[:\-]*\s*(.*)$', re.I)
TECHNIQUE = re.compile(r'^(technique|protocol)\s*[:\-]*\s*(.*)$', re.I)
STUDY = re.compile(r'^type of study (?:requested|provided)\s*[:\-]*\s*(.*)$', re.I)


def clean(line):
    line = line.replace('﻿', '').replace('\t', ' ').strip()
    line = re.sub(r'^[•◦▪▫■□●○·\-\*]+\s*', '', line).strip()
    return re.sub(r'\s{2,}', ' ', line)


def convert(text, name):
    exam, technique, findings, impression = None, [], [], []
    section = None
    for raw in text.splitlines():
        line = clean(raw)
        if not line or DATE.match(line) or DROP.match(line):
            continue
        m = STUDY.match(line)
        if m:
            exam = m.group(1).strip() or exam; continue
        m = CLINICAL.match(line)
        if m:
            section = None; continue
        m = TECHNIQUE.match(line)
        if m:
            section = 'tech'
            if m.group(2).strip(' -:'): technique.append(m.group(2).strip())
            continue
        m = FINDINGS.match(line)
        if m:
            section = 'find'
            if m.group(1).strip(' -:'): findings.append(m.group(1).strip())
            continue
        m = IMPRESSION.match(line)
        if m:
            section = 'imp'
            if m.group(2).strip(' -:'): impression.append(m.group(2).strip())
            continue
        if section == 'tech': technique.append(line)
        elif section == 'find': findings.append(line)
        elif section == 'imp': impression.append(line)
        elif exam is None and len(line) < 60:
            exam = line  # standalone title line such as "HEAD CT"
    exam = (exam or name.replace(' (Normal)', '')).upper().replace(' REPORT', '')
    body = [f'EXAM: {exam}', '', 'CLINICAL DATA: {CLINICAL_INDICATION}', '']
    if technique:
        body += ['TECHNIQUE: ' + ' '.join(technique), '']
    body += ['FINDINGS:'] + [f'- {f}' for f in findings]
    imp = [f'{i}. {t}' for i, t in enumerate(impression, 1)] or ['1. {IMPRESSION_ADDENDUM}']
    return '\n'.join(body), '\n'.join(imp), len(findings)


def main(root):
    out = []
    for dp, _, fs in sorted(os.walk(root)):
        for f in sorted(fs):
            if not f.endswith('.txt'): continue
            stem = f[:-4]; folder = os.path.basename(dp)
            key = f'{folder}/{stem}' if f'{folder}/{stem}' in NAMES else stem
            name, part = NAMES[key]
            body, imp, n = convert(open(os.path.join(dp, f), encoding='utf-8', errors='replace').read(), name)
            if n == 0: raise SystemExit(f'no findings parsed: {key}')
            slug = re.sub(r'[^a-z0-9]+', '-', name.lower()).strip('-')
            out.append(dict(id=f'tpl-ct-normal-{slug}', name=name, part=part, body=body, impression=imp))
    out.sort(key=lambda t: (t['part'], t['name']))
    assert len({t['name'] for t in out}) == len(out), 'duplicate template names'
    print('// Generated by scripts/convert_ct_templates.py from the "CT scan normal formats" documents.')
    print('// Do not edit by hand: re-run the script instead.')
    print("import type { ReportTemplate } from './reportTemplates'\n")
    print('export const CT_NORMAL_TEMPLATES: ReportTemplate[] = [')
    for t in out:
        print('  {')
        print(f"    id: {json.dumps(t['id'])},")
        print(f"    name: {json.dumps(t['name'], ensure_ascii=False)},")
        print("    type: 'Global',")
        print("    modality: 'CT',")
        print(f"    bodyPart: {json.dumps(t['part'])},")
        print("    updated: 'Imported Nov 01, 2026',")
        print(f"    body: {json.dumps(t['body'], ensure_ascii=False)},")
        print(f"    impression: {json.dumps(t['impression'], ensure_ascii=False)},")
        print('  },')
    print(']')
    print(f'// {len(out)} templates', file=sys.stderr)


if __name__ == '__main__':
    main(sys.argv[1])
