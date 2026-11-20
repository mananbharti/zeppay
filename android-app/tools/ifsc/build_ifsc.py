#!/usr/bin/env python3
"""
Builds ZepPay's offline bank + IFSC directory (app/src/main/assets/ifsc/) from a published
IFSC list. Run it with `python3 -I`; it reads only the files you pass and writes only the
assets folder.

Input (from https://github.com/razorpay/ifsc/releases, which compiles RBI's NEFT/RTGS IFSC
lists and NPCI member lists):
  --csv        IFSC.csv  (columns BANK,IFSC,BRANCH,CENTRE,DISTRICT,STATE,...,CITY,...)
  --names      banknames.json  (4-letter bank code -> bank name, from the repo's src/)
  --source     human-readable source line for the About text
  --release    dataset release tag, e.g. v2.0.62
  --rbi-date   "RBI Update Date" from the release notes, e.g. 2026-08-15

Output, all plain UTF-8 text (the APK's zip compression shrinks it):
  meta.tsv             key<TAB>value: source, dates, counts and the limits ZepPay states in the app
  banks.tsv            code<TAB>name<TAB>branch count, sorted by name
  b/<CODE>.tsv         ifsc<TAB>branch<TAB>centre<TAB>district<TAB>state, one file per bank
Addresses, phone numbers and MICR codes are left out to keep the APK small.
"""
import argparse
import csv
import json
import os
import re
import shutil
import sys
from collections import defaultdict

IFSC_RE = re.compile(r"^[A-Z]{4}0[A-Z0-9]{6}$")


def clean(s: str) -> str:
    s = (s or "").replace("\t", " ").replace("\r", " ").replace("\n", " ")
    s = re.sub(r"\s+", " ", s).strip()
    return "" if s.upper() in ("NA", "N/A", "NULL", "-") else s


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--names", required=True)
    ap.add_argument("--out", required=True, help="assets/ifsc folder (replaced)")
    ap.add_argument("--source", required=True)
    ap.add_argument("--source-url", required=True)
    ap.add_argument("--licence", required=True)
    ap.add_argument("--release", required=True)
    ap.add_argument("--rbi-date", required=True)
    a = ap.parse_args()

    with open(a.names, encoding="utf-8") as f:
        names = {k.upper(): clean(v) for k, v in json.load(f).items()}

    rows = defaultdict(list)
    bad = 0
    seen = set()
    with open(a.csv, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            code = clean(r.get("IFSC", "")).upper()
            if not IFSC_RE.match(code) or code in seen:
                bad += 1
                continue
            seen.add(code)
            centre = clean(r.get("CENTRE", "")) or clean(r.get("CITY", ""))
            rows[code[:4]].append((code, clean(r.get("BRANCH", "")), centre, clean(r.get("DISTRICT", "")), clean(r.get("STATE", ""))))

    if os.path.isdir(a.out):
        shutil.rmtree(a.out)
    os.makedirs(os.path.join(a.out, "b"))
    banks = []
    for bank, items in rows.items():
        items.sort()
        with open(os.path.join(a.out, "b", f"{bank}.tsv"), "w", encoding="utf-8", newline="\n") as f:
            for it in items:
                f.write("\t".join(it) + "\n")
        banks.append((bank, names.get(bank) or bank, len(items)))
    banks.sort(key=lambda b: b[1].lower())
    with open(os.path.join(a.out, "banks.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for b in banks:
            f.write(f"{b[0]}\t{b[1]}\t{b[2]}\n")

    meta = {
        "source": a.source,
        "sourceUrl": a.source_url,
        "licence": a.licence,
        "release": a.release,
        "rbiDate": a.rbi_date,
        "branches": sum(b[2] for b in banks),
        "banks": len(banks),
        "skippedRows": bad,
        "limits": "A copy of published lists on the date above. Not every branch may be listed: branches open, merge and close, and many co-operative banks use another bank's IFSC (listed under that bank). A code being listed or well-formed doesn't prove it is live. Check the branch with the person you're paying.",
    }
    with open(os.path.join(a.out, "meta.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for k, v in meta.items():
            f.write(f"{k}\t{clean(str(v))}\n")
    print(f"{meta['branches']} branches, {meta['banks']} banks, {bad} rows skipped -> {a.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
