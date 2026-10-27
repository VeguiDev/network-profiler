import unittest
from ci_version import resolve


class VersionTest(unittest.TestCase):
    def test_branches_and_pull_requests_use_default(self):
        for ref in ('refs/heads/master', 'refs/heads/v0.9.0', 'refs/pull/12/merge'):
            self.assertEqual(('0.1.0', False), resolve(ref, '0.1.0'))

    def test_tags_set_exact_artifact_version(self):
        self.assertEqual(('1.2.3', False), resolve('refs/tags/v1.2.3', '0.1.0'))
        self.assertEqual(('1.2.3-rc.1+build.5', True), resolve('refs/tags/v1.2.3-rc.1+build.5', '0.1.0'))
        self.assertEqual(('1.2.3+build.5', False), resolve('refs/tags/v1.2.3+build.5', '0.1.0'))

    def test_rejects_malformed_versions_and_script_injection(self):
        for tag in ('1.2.3', 'v1.2', 'v01.2.3', 'v1.2.3-01', 'v1.2.3-', 'v1.2.3$(id)', 'v1.2.3\nversion=evil'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                resolve('refs/tags/' + tag, '0.1.0')


if __name__ == '__main__':
    unittest.main()
