#!/usr/bin/env python3
"""Rasterize a Vitrium DRM PDF for browser viewing.

Stripping JavaScript from Intertek/Vitrium PDFs leaves encrypted white image
placeholders (blank pages). Rasterizing the original while locked preserves
the licence page and per-page protection notices that browsers can display.

For full standard content, open the original in Adobe Reader DC after
unlocking with your Intertek Inform credentials.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pymupdf


def flatten_pdf(src: Path, dst: Path, dpi: int = 150) -> int:
    src = src.resolve()
    dst = dst.resolve()
    if not src.is_file():
        raise FileNotFoundError(src)

    src_doc = pymupdf.open(src)
    out = pymupdf.open()
    try:
        for page in src_doc:
            pix = page.get_pixmap(dpi=dpi, alpha=False)
            new_page = out.new_page(width=page.rect.width, height=page.rect.height)
            new_page.insert_image(page.rect, pixmap=pix)
        out.save(dst, garbage=4, deflate=True)
        return len(out)
    finally:
        out.close()
        src_doc.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Original Vitrium/DRM PDF")
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("AS_1391-2020-tensile-test-flattened.pdf"),
        help="Output flattened PDF path",
    )
    parser.add_argument("--dpi", type=int, default=150, help="Rasterization DPI")
    args = parser.parse_args()
    pages = flatten_pdf(args.input, args.output, dpi=args.dpi)
    print(f"Wrote {pages} pages to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
