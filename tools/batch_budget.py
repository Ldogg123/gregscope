"""Worst-case Horizon-QA suite time, counted mechanically from the @GameTest annotations.

Horizon-QA runs batches one after another and gives each batch its slowest test's ``timeoutTicks`` before it gives
up, so the worst case for the whole suite is the sum over batches of the largest ``timeoutTicks`` in each. That
number has to stay inside the CI step timeout (600 s since 774c55e), which is why every ticket recounts it.

Run from the repo root::

    python tools/batch_budget.py

It resolves ``batch`` and ``timeoutTicks`` constants across all game-test files, including a qualified reference to
another class's constant (``OpenOsComputer.BATCH_TIMEOUT_TICKS``), and fails loudly on one it cannot resolve rather
than silently charging the default - an unresolved constant once made the whole budget come out 700 ticks short.
"""

import collections
import glob
import io
import os
import re
import sys

DEFAULT_TIMEOUT = 100  # Horizon-QA's @GameTest default timeoutTicks
TICKS_PER_SECOND = 20.0
CI_STEP_SECONDS = 600  # .github/workflows/build-and-test.yml
GAMETESTS = 'src/gametest/java/io/github/ldogg123/gregscope/gametest/*.java'

STRING_CONST = re.compile(r'String\s+(\w+)\s*=\s*"([^"]+)"')
INT_CONST = re.compile(r'int\s+(\w+)\s*=\s*(\d+)\s*;')
GAMETEST = re.compile(r'@GameTest\s*\(')
BATCH_ARG = re.compile(r'batch\s*=\s*([A-Za-z_"][\w"./]*)')
TICKS_ARG = re.compile(r'timeoutTicks\s*=\s*([A-Za-z_0-9.]+)')


def _annotation_body(src, start):
    """The text inside the @GameTest(...) whose '(' the match ended on, balancing nested parentheses."""
    i = start
    depth = 1
    while depth:
        if src[i] == '(':
            depth += 1
        elif src[i] == ')':
            depth -= 1
        i += 1
    return src[start:i - 1]


def collect():
    paths = sorted(glob.glob(GAMETESTS))
    if not paths:
        sys.exit('no game-test sources under %s; run this from the repo root' % GAMETESTS)

    # Only QUALIFIED constants (Class.NAME) go in the shared map, so a test may use another holder's constant the way
    # HubExampleScriptTests uses OpenOsComputer's. Bare names must stay file-local: nearly every holder declares its
    # own `BATCH`, so a shared bare map lets one file's value silently answer for every other file's tests.
    qualified_strings, qualified_ints = {}, {}
    for path in paths:
        owner = os.path.splitext(os.path.basename(path))[0]
        src = io.open(path, encoding='utf-8').read()
        for name, value in STRING_CONST.findall(src):
            qualified_strings['%s.%s' % (owner, name)] = value
        for name, value in INT_CONST.findall(src):
            qualified_ints['%s.%s' % (owner, name)] = int(value)

    batches = collections.defaultdict(list)
    tests = 0
    for path in paths:
        src = io.open(path, encoding='utf-8').read()
        strings = dict(qualified_strings)
        strings.update(STRING_CONST.findall(src))
        ints = dict(qualified_ints)
        ints.update((name, int(value)) for name, value in INT_CONST.findall(src))
        for match in GAMETEST.finditer(src):
            body = _annotation_body(src, match.end())
            tests += 1

            found = BATCH_ARG.search(body)
            raw = found.group(1) if found else '<none>'
            if raw.startswith('"'):
                batch = raw.strip('"')
            elif raw in strings:
                batch = strings[raw]
            else:
                sys.exit('unresolved batch %s in %s' % (raw, path))

            found = TICKS_ARG.search(body)
            if found is None:
                ticks = DEFAULT_TIMEOUT
            elif found.group(1).isdigit():
                ticks = int(found.group(1))
            elif found.group(1) in ints:
                ticks = ints[found.group(1)]
            else:
                sys.exit('unresolved timeoutTicks %s in %s' % (found.group(1), path))
            batches[batch].append(ticks)
    return tests, batches


def main():
    tests, batches = collect()
    worst = 0
    for name in sorted(batches):
        slowest = max(batches[name])
        worst += slowest
        print('%-44s %3d tests  worst %4d ticks' % (name, len(batches[name]), slowest))
    seconds = worst / TICKS_PER_SECOND
    print('')
    print('%d @GameTest methods in %d batches' % (tests, len(batches)))
    print(
        'note: a @MethodSource method registers one test per argument, so the server runs more tests than there are'
        ' methods. The budget is unaffected: Horizon-QA starts every test of a batch together, so a batch costs its'
        ' slowest test\'s timeout however many tests it holds.')
    print('worst-case suite time: %d ticks = %.1f s' % (worst, seconds))
    print(
        'CI step timeout %d s, so %.1f s of headroom before Gradle configuration and Forge boot'
        % (CI_STEP_SECONDS, CI_STEP_SECONDS - seconds))


if __name__ == '__main__':
    main()
