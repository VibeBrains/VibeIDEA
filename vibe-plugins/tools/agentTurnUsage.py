# Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
"""What Claude Code spent in each session of a project, from its own transcripts.

One measurement for every product that launches Claude Code: VibeIDEA, VibeIDE and the terminal all start it with
the project root as cwd, so all their sessions land in one folder, `$CLAUDE_CONFIG_DIR/projects/<cwd with every
non-alphanumeric replaced by '-'>/`. The numbers are the ones the API billed, per model call; a call split over
several transcript lines is counted once, by its message id. Subagents of a session are summed apart from it.

    python3 vibe-plugins/tools/agentTurnUsage.py /path/to/project [--last 5]
"""
import argparse
import glob
import json
import os
import re

FIELDS = ('cache_creation_input_tokens', 'cache_read_input_tokens', 'input_tokens', 'output_tokens')
HEADERS = ('write', 'read', 'input', 'output')
SNIPPET = 60


def text_of(content):
    """The text of a user message: a plain string from the terminal, text blocks from an ACP client."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return ' '.join(block.get('text', '') for block in content if isinstance(block, dict) and block.get('type') == 'text')
    return ''


def calls(path):
    """The model calls of one transcript, by message id: model name and usage."""
    seen = {}
    first = ''
    with open(path, encoding='utf-8') as lines:
        for line in lines:
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            message = entry.get('message') or {}
            if not first and entry.get('type') == 'user':
                first = text_of(message.get('content'))
            if entry.get('type') == 'assistant' and message.get('id') and message.get('usage'):
                seen[message['id']] = (message.get('model', '?'), message['usage'])
    return first, list(seen.values())


def total(found):
    return [sum(usage.get(field, 0) for _, usage in found) for field in FIELDS]


def row(label, found):
    numbers = total(found)
    models = sorted({model for model, _ in found})
    return '%-14s calls %3d  %s  = %d  %s' % (
        label, len(found), '  '.join('%s %d' % pair for pair in zip(HEADERS, numbers)), sum(numbers), ','.join(models))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('project', help='the project root the agent was started in')
    parser.add_argument('--last', type=int, default=5, help='how many of the newest sessions to show')
    args = parser.parse_args()
    config = os.environ.get('CLAUDE_CONFIG_DIR') or os.path.join(os.path.expanduser('~'), '.claude')
    folder = os.path.join(config, 'projects', re.sub(r'[^A-Za-z0-9]', '-', os.path.realpath(args.project)))
    sessions = sorted(glob.glob(os.path.join(folder, '*.jsonl')), key=os.path.getmtime)[-args.last:]
    if not sessions:
        print('no transcripts in %s' % folder)
        return 1
    for path in sessions:
        session = os.path.basename(path)[:-len('.jsonl')]
        first, main_calls = calls(path)
        print('%s  «%s»' % (session, ' '.join(first.split())[:SNIPPET]))
        print('  ' + row('main', main_calls))
        helpers = []
        for sub in sorted(glob.glob(os.path.join(folder, session, 'subagents', '*.jsonl'))):
            helpers.extend(calls(sub)[1])
        if helpers:
            print('  ' + row('subagents', helpers))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
