# DocPrep – shrink big documents for Claude, on your phone

DocPrep turns a large **PDF, Word (.docx) or Excel (.xlsx)** file into **ZIP parts small enough to
upload to Claude**, without losing the document's structure. All processing happens on the phone –
the app has **no internet permission**, so nothing leaves the device unless you share the ZIPs.

**Download:** [`apk/DocPrep.apk`](../apk/DocPrep.apk) (≈2.4 MB, Android 7.0+).

## How to use
1. Open DocPrep, choose the limits (defaults: 10 MB and ~150k tokens per ZIP) and image mode.
2. Tap **Choose document & process** (or "Open with → DocPrep" from a file manager).
3. Tap **Share** on a part (e.g. straight to the Claude app) or **Save all to a folder**.
4. Upload part 1 to Claude and say what you need; upload further parts when Claude asks for them.

## What's inside each ZIP
| File | Purpose |
|---|---|
| `INDEX.md` | Map of the **whole** document (same in every part): which part holds which pages, table of contents from bookmarks/headings, where each Figure/Table/Drawing is captioned and mentioned, every instrument tag / equipment / document number and the pages it appears on, processing notes. Also tells Claude how to read the package. |
| `document.md` | This part's text in original reading order with `## Page N` / `## Section N` markers. Starts with a "continues from part N" note and the current section so context carries across parts. |
| `images/` | Page images of every page that has a drawing, table, picture or scan, referenced by a `**[FIGURE pN]**` marker placed exactly where the page starts – so the image and the words of the same page stay together. Word figures are placed at their exact paragraph, with alt text. |
| `manifest.json` | SHA-256 fingerprint of the original file and of every file in the ZIP, to detect tampering. |

## How the size comes down without losing context
- **Text instead of the raw PDF**; repeated headers/footers (title blocks, "Page 3 of 90") are removed and listed once in the index.
- **Only pages with graphics become images**, sized to 1568 px (the most detail Claude uses) as JPEG.
- **High detail mode** adds four overlapping zoom tiles per drawing for small print (P&IDs, wiring diagrams).
- **Excel** sheets become CSV blocks of 300 rows, each repeating the header row and keeping original row numbers.
- Parts are cut only **between** pages/sections, never in the middle of one.

## Tested
The processing engine was run on an Android 11 (x86_64) emulator with the real Android PDF renderer,
image encoder and XML parser, using a 120-page controls spec (P&ID-style vector drawings, a photo, grid tables,
bookmarks, repeated header/footer), a Word control narrative (headings, table, figures with alt text) and a
1,200-row Excel I/O list:
- PDF: 41/41 graphic pages detected and imaged; header + "Page # of #" removed, numbered body lines kept;
  split into parts at page boundaries; SHA-256 manifest verified; high-detail zoom tiles readable.
- Word: figures placed between their caption and following paragraph with alt text; tables kept as Markdown.
- Excel: 300-row blocks with header row repeated; tag series and cross-sheet links (PT-1001…1020 in both the
  I/O list and the interlock sheet) captured in the index.
The phone UI (file picker, Share, Save) could not be exercised on the emulator and is untested.

## Limits
- Old `.doc` / `.xls` and PowerPoint: save as PDF, .docx or .xlsx first.
- Password-protected PDFs need to be unlocked first. Word drawings in EMF/WMF format can't be decoded on Android (marked in the text).
- Excel dates appear as numbers; formulas show their last calculated value.

## Build
```bash
ANDROID_HOME=/path/to/android-sdk ./build-apk.sh   # build-tools 35, platform 34, JDK 17+; downloads PDFBox into libs-dl/
```
Or open this folder in Android Studio (Gradle files included). `signing.jks` (password `android`) keeps updates installable.
