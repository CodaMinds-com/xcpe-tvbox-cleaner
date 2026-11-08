#!/usr/bin/env python3
"""Verify locale string files against the English sources: names, placeholders, plurals, XML validity."""
import os, re, sys, xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(HERE, 'res')
FILES = ['strings.xml', 'strings_scan.xml', 'strings_remove.xml']
PH = re.compile(r'%(\d+\$)?[sd]')
def load(path):
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for el in root:
        if el.tag == 'string':
            if el.get('translatable', 'true') == 'false': continue
            strings[el.get('name')] = ''.join(el.itertext())
        elif el.tag == 'plurals':
            plurals[el.get('name')] = {i.get('quantity'): ''.join(i.itertext()) for i in el}
    return strings, plurals
def phset(s): return sorted(set(m.group(0) for m in PH.finditer(s)))
en = {f: load(os.path.join(RES, 'values', f)) for f in FILES}
locales = sorted(d for d in os.listdir(RES) if d.startswith('values-'))
if len(sys.argv) > 1: locales = [l for l in locales if l in sys.argv[1:]]
problems = 0
for loc in locales:
    for f in FILES:
        p = os.path.join(RES, loc, f)
        if not os.path.exists(p):
            print(f'{loc}/{f}: MISSING'); problems += 1; continue
        try: s, pl = load(p)
        except ET.ParseError as e:
            print(f'{loc}/{f}: XML ERROR {e}'); problems += 1; continue
        es, epl = en[f]
        for name, val in es.items():
            if name not in s: print(f'{loc}/{f}: missing string {name}'); problems += 1; continue
            if phset(val) != phset(s[name]): print(f'{loc}/{f}: placeholders differ in {name}: {phset(val)} vs {phset(s[name])}'); problems += 1
            if s[name].strip() == '': print(f'{loc}/{f}: empty {name}'); problems += 1
            if val.startswith('"') != s[name].startswith('"') and '  ' in val: print(f'{loc}/{f}: {name} lost its double-quote wrapping'); problems += 1
        for name in s:
            if name not in es: print(f'{loc}/{f}: unknown string {name}'); problems += 1
        for name, forms in epl.items():
            if name not in pl: print(f'{loc}/{f}: missing plurals {name}'); problems += 1; continue
            if 'other' not in pl[name]: print(f'{loc}/{f}: plurals {name} has no "other"'); problems += 1
            for q, v in pl[name].items():
                want = phset(forms.get(q, forms['other']))
                if q == 'other' and phset(v) != want: print(f'{loc}/{f}: plurals {name}/{q} placeholders differ'); problems += 1
                elif q != 'other' and not set(phset(v)) <= set(phset(forms['other'])): print(f'{loc}/{f}: plurals {name}/{q} uses a placeholder the English has not'); problems += 1
    # untranslated check: count values identical to English (tokens-only strings are allowed)
    same = 0; total = 0
    for f in FILES:
        p = os.path.join(RES, loc, f)
        if not os.path.exists(p): continue
        s, _ = load(p); es, _ = en[f]
        for n, v in s.items():
            if n in es:
                total += 1
                if v.strip() == es[n].strip() and re.search(r'[A-Za-z]{4,}', re.sub(r'%\d\$[sd]|XCPE|Play Protect|MECOOL|Videostrong|com\.[a-z.]+|/oem', '', v)): same += 1
    if total and same / total > 0.15: print(f'{loc}: {same}/{total} values identical to English (looks untranslated)'); problems += 1
print('OK' if problems == 0 else f'{problems} problem(s)')
sys.exit(1 if problems else 0)
