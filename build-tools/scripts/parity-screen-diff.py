#!/usr/bin/env python3
"""Cross-platform screen parity check for Payanam (Android vs Linux desktop).

The Android app and the desktop app run the SAME shared Jetpack Compose code, so a
pixel-by-pixel frame comparison is dominated by font rendering and display density
(dpi) differences -- it produces noise, not signal. Instead, both platforms emit the
SAME normalised geometry format: every labelled node's bounds as a fraction of the
window (0..1). That is robust to dpi and font metrics, so the two sides compare
directly.

The Android status bar pushes the whole app layout DOWN by a roughly constant
fraction of the window height; the desktop has no system status bar. `--inset`
(default 0.085) compensates: top is compared as abs((androidT - inset) - desktopT).
Left is compared raw. Usage:
  python3 build-tools/scripts/parity-screen-diff.py \\
      --android <logcat-file> --desktop <desktop-run-directory> \\
      [--tol 0.02] [--inset 0.085] [--sweep <name>]
Exit code: 1 if a screen is missing on either side, a label is missing on either
side, or any matched node diverges beyond tolerance; otherwise 0. Pure stdlib.
"""

import argparse
import re
import sys
from pathlib import Path

# A geometry node line: label (padded to 55), click flag, then normalised L/T/R/B/w/h.
NODE_RE = re.compile(
    r'^(?P<label>.{0,55}?)\s+click=(?P<click>true|false)'
    r'\s+L=(?P<l>-?\d+(?:\.\d+)?)\s+T=(?P<t>-?\d+(?:\.\d+)?)'
    r'\s+R=(?P<r>-?\d+(?:\.\d+)?)\s+B=(?P<b>-?\d+(?:\.\d+)?)'
    r'\s+w=(?P<w>-?\d+(?:\.\d+)?)\s+h=(?P<h>-?\d+(?:\.\d+)?)\s*$'
)
ROUTE_RE = re.compile(r'geometry\s+route=(?P<route>\S+)\s+(?P<kind>begin|end)\b')


def make_node(m):
    """Build a node dict from a NODE_RE match."""
    g = m.group
    return {'label': g('label').strip(), 'l': float(g('l')), 't': float(g('t')),
            'r': float(g('r')), 'b': float(g('b')), 'w': float(g('w')), 'h': float(g('h'))}


def parse_android(path):
    """Parse the Android logcat geometry dump into {screen: [node, ...]}.

    Each node line is logged twice (stdout + app logger); identical payloads
    within a route are deduplicated.
    """
    screens, current, seen = {}, None, set()
    with open(path, encoding='utf-8', errors='replace') as fh:
        for line in fh:
            rm = ROUTE_RE.search(line)
            if rm:
                if rm.group('kind') == 'begin':
                    current, seen = rm.group('route'), set()
                else:
                    current = None
                continue
            if current is None:
                continue
            idx = line.find('geometry ')
            if idx < 0:
                continue
            nm = NODE_RE.match(line[idx + len('geometry '):])
            if not nm:
                continue
            key = nm.group(0)
            if key in seen:
                continue
            seen.add(key)
            screens.setdefault(current, []).append(make_node(nm))
    return screens


def parse_desktop(run_dir, sweep=None):
    """Parse desktop geometry files into {screen: [node, ...]}.

    A run directory may hold several sweep subdirectories (e.g. tabs-fs0.8/);
    `--sweep` picks one, else the first subdir whose name starts with 'tabs-'.
    """
    run = Path(run_dir)
    if sweep:
        base = run / sweep
    elif list(run.glob('*.geometry.txt')):
        # A FLAT directory: one file per screen, named for the screen. This is what a caller assembles when
        # the probe's per-scene artefacts live in separate run directories.
        base = run
    else:
        cands = sorted(d for d in run.iterdir() if d.is_dir() and d.name.startswith('tabs-'))
        if not cands:
            return {}
        base = cands[0]
    screens = {}
    if not base.is_dir():
        return screens
    for f in sorted(base.glob('*.geometry.txt')):
        nodes = []
        for line in f.read_text(encoding='utf-8').splitlines():
            nm = NODE_RE.match(line.strip())
            if nm:
                nodes.append(make_node(nm))
        screens[f.name[:-len('.geometry.txt')]] = nodes
    return screens


def group_by_label(nodes):
    """Group nodes by label, preserving order of appearance per label."""
    groups = {}
    for n in nodes:
        groups.setdefault(n['label'], []).append(n)
    return groups


def compare_screen(a_nodes, d_nodes, tol, inset, align='top'):
    """Compare one screen; returns (report_lines, divergence_count, worst_key).

    Alignment: with `align='top'` (the default) each side's topmost node is treated as the origin, which
    cancels the Android SYSTEM BARS (a status bar at the top AND a navigation bar at the bottom) — a single
    `--inset` cannot, because the two windows have different content areas. With `align='inset'` the Android
    top is shifted up by `--inset` instead. Lefts are always raw.

    worst_key is (divergence_count, max_deviation) for picking the worst screen.
    """
    a_by, d_by = group_by_label(a_nodes), group_by_label(d_nodes)
    only_a = [l for l in a_by if l not in d_by]
    only_d = [l for l in d_by if l not in a_by]
    if align == 'top':
        a_base = min((n['t'] for n in a_nodes), default=0.0)
        d_base = min((n['t'] for n in d_nodes), default=0.0)
    else:
        a_base, d_base = inset, 0.0
    matched, extra = [], 0
    for label in a_by:
        if label not in d_by:
            continue
        a_list, d_list = a_by[label], d_by[label]
        for a, d in zip(a_list, d_list):
            matched.append((label, a, d, abs(a['l'] - d['l']),
                            abs((a['t'] - a_base) - (d['t'] - d_base))))
        extra += abs(len(a_list) - len(d_list))
    divergent = [m for m in matched if m[3] > tol or m[4] > tol]
    max_dl = max((m[3] for m in matched), default=0.0)
    max_dt = max((m[4] for m in matched), default=0.0)
    top5 = sorted(matched, key=lambda m: max(m[3], m[4]), reverse=True)[:5]
    max_dev = max((max(m[3], m[4]) for m in matched), default=0.0)

    lines = [f"  nodes: android={len(a_nodes)} desktop={len(d_nodes)}"]
    if only_a:
        lines.append(f"  only on android: {', '.join(only_a)}")
    if only_d:
        lines.append(f"  only on desktop: {', '.join(only_d)}")
    lines.append(f"  max |dL| = {max_dl:.3f}   max |dT-inset| = {max_dt:.3f}")
    if top5:
        lines.append("  top diverging nodes:")
        for label, a, d, dl, dt in top5:
            lines.append(f"    {label:<30} A({a['l']:.3f},{a['t']:.3f})  "
                         f"D({d['l']:.3f},{d['t']:.3f})  dL={dl:.3f} dT={dt:.3f}")
    div_count = len(divergent) + len(only_a) + len(only_d) + extra
    return lines, div_count, (div_count, max_dev)


def main(argv=None):
    """CLI entry point; returns the process exit code."""
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--android', required=True, help='Android logcat file')
    ap.add_argument('--desktop', required=True, help='Desktop run directory')
    ap.add_argument('--tol', type=float, default=0.02, help='divergence tolerance')
    ap.add_argument('--inset', type=float, default=0.085, help='Android top inset (align=inset only)')
    ap.add_argument('--align', choices=['top', 'inset'], default='top',
                    help="'top' cancels the system bars by using each side's topmost node as origin")
    ap.add_argument('--sweep', default=None, help='desktop sweep subdirectory')
    args = ap.parse_args(argv)

    a_screens = parse_android(args.android)
    d_screens = parse_desktop(args.desktop, args.sweep)
    common = sorted(set(a_screens) & set(d_screens))
    missing = sorted(set(a_screens) ^ set(d_screens))
    for name in missing:
        print(f"== {name} == MISSING on {'desktop' if name in a_screens else 'android'}")

    total_div, worst_name, worst_key = 0, '', (0, 0.0)
    for name in common:
        lines, div, key = compare_screen(a_screens[name], d_screens[name],
                                         args.tol, args.inset, args.align)
        print(f"== {name} ==")
        for ln in lines:
            print(ln)
        total_div += div
        if key > worst_key:
            worst_name, worst_key = name, key

    print(f"SUMMARY screens={len(common)} divergences={total_div} worst={worst_name or '-'}")
    return 1 if (missing or total_div > 0) else 0


if __name__ == '__main__':
    sys.exit(main())
