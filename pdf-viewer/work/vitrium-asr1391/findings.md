# Evidence → Finding → Path

## Evidence
1. Original PDF uses Vitrium Security (`extracted.js`, copyright Vitrium Systems Inc.)
2. Embedded JPEG page images are valid DCT streams but all pixels are RGB(255,255,255) until Adobe unlock
3. Stripping `/JS`, `/AA`, `/OpenAction` removed unlock logic → blank pages in browsers
4. Security OCG layer `20ac533d-136f-4248-9013-0684ef464e8d` renders "THIS PAGE IS PROTECTED" in Chrome/PyMuPDF
5. Content OCG `e186e82b-9c24-4447-9b6c-4d8a0b2c4128` has zero XObject references while locked
6. Unlock API `https://doc.drm.saiglobal.com/api/2.0/unlock/challenge` returns HTTP 404 (unavailable here)

## Finding
Browser blank pages were caused by over-aggressive DRM removal, not a missing browser feature alone.
The document cannot display standard body text in browsers without either:
- Adobe Reader DC + Intertek login (legitimate unlock), or
- Rasterizing whatever is visually present while locked (license page + protection notices)

## Path (implemented)
- **Route**: `js-reverse` (Observe Vitrium JS) + `reverse-engineering` (crypto/OCG structure)
- **Fix**: `scripts/flatten_for_browser.py` rasterizes the original PDF into `AS_1391-2020-tensile-test-flattened.pdf`
- **Preserve**: original DRM PDF for Adobe Reader download
- **Skills**: installed under `.cursor/skills/` from `claude-skills-build/reverse-skill-router-new.zip`
