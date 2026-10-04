#!/usr/bin/env python3
"""
Writes the desktop app's translation tables and its R class from strings.tsv
beside this script: one row per key, one column per language.

The table is the source; the generated files are not edited by hand:

    python3 app/l10n/generate_strings.py

Outputs
  app/src/main/resources/i18n/strings_<lang>.json   one table per language,
                                                    read by res/Strings.kt
  app/src/main/kotlin/tf/monochrome/desktop/R.kt     R.string.*, R.plurals.*,
                                                    R.font.*: the ids the ported
                                                    screens were written against

Every key must have every language filled in — the script refuses to write a
file with a hole in it, because a missing string would fall back to English
and nobody would notice until a user in that language does. Rows whose key
ends in `#one`, `#other` (and so on) are plural quantities grouped into one
plurals entry per key. Format arguments are Java-style positional (`%1$s`),
formatted at runtime with String.format, exactly as Android's getString does.

The Android app's copy of this script writes values-*/strings.xml instead; the
TSV is shared between the two, so translations are edited once.
"""
import csv, json, os, sys, re

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, '..')
I18N = os.path.join(APP, 'src', 'main', 'resources', 'i18n')
R_KT = os.path.join(APP, 'src', 'main', 'kotlin', 'tf', 'monochrome', 'desktop', 'R.kt')
FONTS = os.path.join(APP, 'src', 'main', 'resources', 'fonts')
DRAWABLES = os.path.join(APP, 'src', 'main', 'composeResources', 'drawable')
LANGS = ['en', 'zh', 'ja', 'fr', 'es', 'tr', 'de']
# Plural categories each language actually distinguishes (CLDR). A plurals
# entry must give exactly these: an English-style one/other pair in Japanese is
# harmless but a missing "many" in French or Spanish is a wrong number.
PLURAL_FORMS = {
    'en': ['one', 'other'], 'zh': ['other'], 'ja': ['other'], 'tr': ['one', 'other'],
    'fr': ['one', 'many', 'other'], 'es': ['one', 'many', 'other'], 'de': ['one', 'other'],
}
NON_TRANSLATABLE = {'app_name'}

def french_spacing(s):
    """French puts a space before : ; ? ! and it has to be non-breaking, or a
    narrow window wraps the mark onto a line of its own. Written as a plain
    space in the table, it becomes U+00A0 here, so nobody has to type it."""
    return re.sub(r' ([:;?!])', ' \\1', s)

def runtime_text(s):
    """The Android generator escaped for aapt; here the text is used as-is by
    String.format, so only the TSV's literal `\\n` needs to become a newline."""
    return s.replace('\\n', '\n')

def kotlin_ident(name):
    return name if not re.match(r'^\d', name) else '_' + name

def main():
    rows = list(csv.DictReader(open(os.path.join(HERE, 'strings.tsv'), encoding='utf-8'), delimiter='\t'))
    errors = []
    plurals = {}
    for r in rows:
        key = r['key']
        if '#' in key:
            base, q = key.split('#')
            plurals.setdefault(base, {})[q] = r
        for lang in LANGS:
            if '#' in key or (key in NON_TRANSLATABLE and lang != 'en'):
                continue  # plurals are checked per form below
            if not (r.get(lang) or '').strip():
                errors.append(f'{key}: no {lang}')
            if lang != 'en':
                # Every placeholder the English uses must survive translation.
                want = sorted(re.findall(r'%\d+\$[sdf]', r['en']))
                got = sorted(re.findall(r'%\d+\$[sdf]', r.get(lang) or ''))
                if want != got:
                    errors.append(f'{key}: {lang} placeholders {got} != en {want}')
    for base, forms in plurals.items():
        want = sorted(re.findall(r'%\d+\$[sdf]', forms['other']['en']))
        for lang, needed in PLURAL_FORMS.items():
            for q in needed:
                if q not in forms or not (forms[q].get(lang) or '').strip():
                    errors.append(f'{base}: {lang} plural "{q}" missing')
                    continue
                got = sorted(re.findall(r'%\d+\$[sdf]', forms[q][lang]))
                # "one" may leave the number out ("Afficher le titre"); no
                # form may invent a placeholder or drop one in "other".
                ok = set(got) <= set(want) if q == 'one' else got == want
                if not ok:
                    errors.append(f'{base}: {lang} plural "{q}" placeholders {got} != {want}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        sys.exit(1)

    os.makedirs(I18N, exist_ok=True)
    string_keys, plural_keys = [], []
    for r in rows:
        key = r['key']
        if '#' in key:
            base = key.split('#')[0]
            if base not in plural_keys:
                plural_keys.append(base)
        else:
            string_keys.append(key)
    for lang in LANGS:
        strings, plural_table = {}, {}
        for r in rows:
            key = r['key']
            if '#' in key:
                continue
            text = r['en'] if key in NON_TRANSLATABLE else r[lang]
            if lang == 'fr':
                text = french_spacing(text)
            strings[key] = runtime_text(text)
        for base in plural_keys:
            forms = {}
            for q in PLURAL_FORMS[lang]:
                text = plurals[base][q][lang]
                if lang == 'fr':
                    text = french_spacing(text)
                forms[q] = runtime_text(text)
            plural_table[base] = forms
        with open(os.path.join(I18N, f'strings_{lang}.json'), 'w', encoding='utf-8') as f:
            json.dump({'lang': lang, 'strings': strings, 'plurals': plural_table}, f,
                      ensure_ascii=False, indent=0, sort_keys=True)
            f.write('\n')

    fonts = sorted(f for f in os.listdir(FONTS) if f.endswith('.ttf')) if os.path.isdir(FONTS) else []
    drawables = sorted(os.path.splitext(f)[0] for f in os.listdir(DRAWABLES)
                       if f.endswith(('.xml', '.png', '.webp', '.jpg'))) if os.path.isdir(DRAWABLES) else []
    out = ['// Generated by app/l10n/generate_strings.py from app/l10n/strings.tsv and the',
           '// resources directories. Do not edit.',
           '//',
           '// The desktop build\'s R class: the ids the screens ported from Android were',
           '// written against. Strings and plurals resolve through res/Strings.kt,',
           '// fonts through res/StringResources.kt (Font(R.font.x, …)), drawables through',
           '// Compose Multiplatform\'s painterResource(R.drawable.x).',
           '@file:Suppress("ObjectPropertyName", "unused")',
           '',
           'package tf.monochrome.desktop',
           '',
           'import tf.monochrome.desktop.res.FontKey',
           'import tf.monochrome.desktop.res.PluralKey',
           'import tf.monochrome.desktop.res.StringKey',
           '// Compose Multiplatform generates Res.drawable.<name> as extension properties,',
           '// one per resource, so they have to be imported by name or by wildcard.',
           'import tf.monochrome.desktop.res.*',
           '',
           'object R {',
           '    object string {']
    for key in string_keys:
        out.append(f'        val {kotlin_ident(key)} = StringKey("{key}")')
    out.append('    }')
    out.append('')
    out.append('    object plurals {')
    for key in plural_keys:
        out.append(f'        val {kotlin_ident(key)} = PluralKey("{key}")')
    out.append('    }')
    out.append('')
    out.append('    object font {')
    for f in fonts:
        out.append(f'        val {kotlin_ident(os.path.splitext(f)[0])} = FontKey("{f}")')
    out.append('    }')
    out.append('')
    out.append('    object drawable {')
    for d in drawables:
        out.append(f'        val {kotlin_ident(d)} get() = Res.drawable.{kotlin_ident(d)}')
    out.append('    }')
    out.append('}')
    os.makedirs(os.path.dirname(R_KT), exist_ok=True)
    with open(R_KT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')
    print(f'wrote {len(string_keys)} strings and {len(plural_keys)} plurals into {len(LANGS)} languages, '
          f'{len(fonts)} fonts, {len(drawables)} drawables')

main()
