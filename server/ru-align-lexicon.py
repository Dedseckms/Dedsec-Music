#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare a Russian MFA lexicon without English-specific phone substitutions.

Format reference: k2-fsa/sherpa-onnx scripts/mfa; model and dictionary are
MontrealCorpusTools/russian_mfa (CC BY 4.0), pinned independently by the operator.
No phone inference, English remapping, training or timestamp generation occurs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import unicodedata

DICTIONARY_SHA = 'b15d7ad6545d228c66fb818d2c66ade035fe50d41c5cd6bba6530d6ac06c4091'


def numeric(value):
    try: float(value); return True
    except ValueError: return False


def pronunciation(parts, allowed):
    phones = [value.replace('\u02c8', '').replace('\u02cc', '') for value in parts if not numeric(value)]
    phones = [value for value in phones if value and value != '.']
    if not phones or any(phone not in allowed for phone in phones):
        raise ValueError('Unsupported Russian dictionary phone')
    return phones


def convert(dictionary, phone_table, output):
    allowed = {line.split()[0] for line in phone_table.read_text(encoding='utf-8').splitlines() if line.strip()}
    digest = hashlib.sha256(); rows = 0
    with dictionary.open('rb') as source, output.open('xb') as target:
        for raw in source:
            digest.update(raw); fields = raw.decode('utf-8').split()
            if len(fields) < 2 or fields[0].startswith(('<', '[')): continue
            word = unicodedata.normalize('NFC', fields[0]).lower()
            phones = pronunciation(fields[1:], allowed)
            target.write((word + ' ' + ' '.join(phones) + '\n').encode('utf-8')); rows += 1
        target.flush(); os.fsync(target.fileno())
    if digest.hexdigest() != DICTIONARY_SHA: raise ValueError('Pinned dictionary hash mismatch')
    output.chmod(0o600)
    return {'pronunciations': rows, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest()}


def check_words(text, lexicon):
    """Reject unsupported words before invoking the aligner; never invent phones."""
    import re
    words = re.findall(r"[\w]+(?:['’-][\w]+)*", unicodedata.normalize('NFC', text).lower(), flags=re.UNICODE)
    if not words: raise ValueError('Empty alignment transcript')
    remaining = set(words)
    with lexicon.open(encoding='utf-8') as source:
        for line in source:
            if line.strip(): remaining.discard(line.split(maxsplit=1)[0])
            if not remaining: return words
    raise ValueError('Alignment transcript contains unsupported words')


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dictionary', type=Path, required=True)
    parser.add_argument('--phones', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(convert(args.dictionary, args.phones, args.output)))
