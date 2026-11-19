#!/usr/bin/env python3
"""Draws terminal output (text with ANSI colour codes) as an SVG picture of a terminal window.

Usage: json-table --color=always --date-only sample.json | docs/ansi2svg.py 'json-table --date-only sample.json' > docs/example.svg

Every word is placed at its exact column and the table borders are drawn as lines,
so the picture stays aligned whatever monospace font the viewer has.
"""
import re
import sys
from html import escape

CELL_W, LINE_H, FONT = 8.6, 21, 14
PAD_X, PAD_TOP, PAD_BOTTOM = 18, 48, 16
BACKGROUND, BAR, TEXT, BRIGHT, YELLOW, DIM, PROMPT = "#0d1117", "#161b22", "#c9d1d9", "#ffffff", "#e3b341", "#484f58", "#3fb950"
SGR = re.compile(r"\x1b\[([0-9;]*)m")


def parse(line):
    """Yields (character, bold, dim, yellow) for one line."""
    bold = dim = yellow = False
    position = 0
    for match in SGR.finditer(line):
        for char in line[position:match.start()]:
            yield char, bold, dim, yellow
        for code in (match.group(1) or "0").split(";"):
            if code in ("", "0"):
                bold = dim = yellow = False
            elif code == "1":
                bold = True
            elif code == "2":
                dim = True
            elif code == "33":
                yellow = True
        position = match.end()
    for char in line[position:]:
        yield char, bold, dim, yellow


def draw_line(chars, row, out, last=False):
    top = PAD_TOP + row * LINE_H
    middle = top + LINE_H / 2
    baseline = top + LINE_H * 0.7
    word, word_start, word_style = "", 0, None
    rule_start, rule_end = None, 0.0

    def flush():
        nonlocal word
        if word:
            bold, _, yellow = word_style
            fill = YELLOW if yellow else BRIGHT if bold else TEXT
            weight = ' font-weight="700"' if bold else ""
            x = PAD_X + word_start * CELL_W
            out.append(f'<text x="{x:.1f}" y="{baseline:.1f}" fill="{fill}"{weight} '
                       f'textLength="{len(word) * CELL_W:.1f}" lengthAdjust="spacingAndGlyphs">{escape(word)}</text>')
            word = ""

    def end_rule():   # consecutive horizontal border characters become one line
        nonlocal rule_start
        if rule_start is not None:
            out.append(f'<line x1="{rule_start:.1f}" y1="{middle:.1f}" x2="{rule_end:.1f}" y2="{middle:.1f}"/>')
            rule_start = None

    for column, (char, bold, dim, yellow) in enumerate(chars):
        x = PAD_X + column * CELL_W
        if char in "│─┼":
            flush()
            if char in "─┼":
                if rule_start is None:
                    rule_start = x
                rule_end = x + CELL_W
            if char in "│┼":
                bottom = middle if last and char == "┼" else top + LINE_H   # no stubs under the last rule
                out.append(f'<line x1="{x + CELL_W / 2:.1f}" y1="{top}" x2="{x + CELL_W / 2:.1f}" y2="{bottom:.1f}"/>')
        elif char == " " or (word and word_style != (bold, dim, yellow)):
            flush()
            end_rule()
            if char != " ":
                word, word_start, word_style = char, column, (bold, dim, yellow)
        else:
            end_rule()
            if not word:
                word_start, word_style = column, (bold, dim, yellow)
            word += char
    flush()
    end_rule()


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    lines = sys.stdin.read().rstrip("\n").split("\n")
    rows = [list(parse(line)) for line in lines]
    if command:
        prompt = [("$", False, False, False), (" ", False, False, False)] + [(c, True, False, False) for c in command]
        rows = [prompt, []] + rows
    columns = max(len(row) for row in rows)
    width = round(columns * CELL_W + 2 * PAD_X)
    height = PAD_TOP + len(rows) * LINE_H + PAD_BOTTOM
    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" height="{height}" '
        f'role="img" aria-label="Terminal: {escape(command, quote=True)}">',
        f'<rect width="{width}" height="{height}" rx="10" fill="{BACKGROUND}"/>',
        f'<path d="M0 10a10 10 0 0 1 10-10h{width - 20}a10 10 0 0 1 10 10v22H0z" fill="{BAR}"/>',
        '<circle cx="20" cy="16" r="6" fill="#ff5f56"/><circle cx="40" cy="16" r="6" fill="#ffbd2e"/>'
        '<circle cx="60" cy="16" r="6" fill="#27c93f"/>',
        f'<g font-family="ui-monospace, SFMono-Regular, Menlo, Consolas, \'Liberation Mono\', monospace" '
        f'font-size="{FONT}" stroke="{DIM}" stroke-width="1.2" xml:space="preserve">',
    ]
    body = []
    for row, chars in enumerate(rows):
        draw_line(chars, row, body, last=row == len(rows) - 1)
    # Text must not inherit the border stroke.
    body = [item.replace("<text ", '<text stroke="none" ') for item in body]
    if command:
        body[0] = body[0].replace(f'fill="{TEXT}"', f'fill="{PROMPT}"', 1)
    out += body + ["</g>", "</svg>"]
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
