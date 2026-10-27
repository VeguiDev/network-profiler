#!/usr/bin/env python3
"""Resolve CI versions without evaluating tag text as shell or Gradle code."""
import os
from pathlib import Path
import re

NUMBER = r"(?:0|[1-9][0-9]*)"
IDENTIFIER = r"(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
SEMVER = re.compile(rf"{NUMBER}\.{NUMBER}\.{NUMBER}(?:-({IDENTIFIER}(?:\.{IDENTIFIER})*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")


def resolve(ref: str, default: str) -> tuple[str, bool]:
    version = ref.removeprefix('refs/tags/v') if ref.startswith('refs/tags/') else default
    if ref.startswith('refs/tags/') and not ref.startswith('refs/tags/v'):
        raise ValueError('Release tags must start with v')
    match = SEMVER.fullmatch(version)
    if match is None:
        raise ValueError('Expected a semantic version, e.g. v0.2.0 or v0.2.0-rc.1')
    return version, match.group(1) is not None


if __name__ == '__main__':
    properties = dict(line.split('=', 1) for line in Path('gradle.properties').read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    version, prerelease = resolve(os.environ['BUILD_REF'], properties['vnpVersion'])
    output = f'version={version}\nprerelease={str(prerelease).lower()}\n'
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as target:
        target.write(output)
    print(f'Build version: {version}; prerelease: {prerelease}')
