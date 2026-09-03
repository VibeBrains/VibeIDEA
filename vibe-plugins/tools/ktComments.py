"""Kotlin source with the comments removed — one implementation for the whole i18n gate.

Naive `//`-stripping cannot be used here: it also cuts everything after `https://` inside a string
literal, which silently deletes real `t("...")` calls that sit on the same line. And NOT stripping
comments is just as wrong in the other direction: a comment that quotes a call invents a key that
does not exist in the code, and the gate then demands a string for a quotation.

So the scan tracks string literals and drops only what is genuinely a comment.
"""


def strip_comments(text: str) -> str:
    out = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '"':
            # Raw string: no escapes inside, ends at the next triple quote.
            if text.startswith('"""', i):
                end = text.find('"""', i + 3)
                end = n if end == -1 else end + 3
                out.append(text[i:end])
                i = end
                continue
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == '\\' else 1
            out.append(text[i:min(j + 1, n)])
            i = j + 1
            continue
        if text.startswith('//', i):
            end = text.find('\n', i)
            i = n if end == -1 else end
            continue
        if text.startswith('/*', i):
            end = text.find('*/', i + 2)
            i = n if end == -1 else end + 2
            continue
        out.append(ch)
        i += 1
    return ''.join(out)
