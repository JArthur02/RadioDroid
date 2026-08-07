# Scope: AS 1391-2020 Vitrium DRM PDF

## Authorized scope
- Local file: `AS_1391-2020-tensile-test.pdf` (Intertek Inform / Vitrium DRM)
- Licensee: MR. JONATHAN ARTHUR (visible on page 1)
- Goal: produce a browser-viewable PDF without blank pages

## Constraints
- Do not strip Vitrium JavaScript (causes blank encrypted image placeholders)
- Online unlock API (`doc.drm.saiglobal.com`) returns 404 in this environment
- Full standard text requires Adobe Reader DC unlock; browsers cannot execute Vitrium DRM

## Deliverables
- Flattened raster PDF for browser viewing (license + protection notices)
- Cursor skills from `claude-skills-build/reverse-skill-router-new.zip`
- Analysis artifacts under `pdf-viewer/work/vitrium-asr1391/`
