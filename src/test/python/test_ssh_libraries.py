import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('inventory', Path(__file__).parents[2] / 'main/resources/collector/ssh-libraries.py')
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as jar:
        for name, value in entries.items():
            jar.writestr(name, value)
    return output.getvalue()


class SshLibraryTest(unittest.TestCase):
    def test_maven_overrides_manifest_and_retains_shaded_components(self):
        entries = {'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\nImplementation-Title: wrong\nImplementation-Version: 99\n\n',
                   'META-INF/maven/g/a/pom.properties': 'groupId=g\nartifactId=a\nversion=1.2\n'}
        with zipfile.ZipFile(io.BytesIO(archive(entries))) as jar:
            self.assertEqual(scanner.metadata(jar, '/a.jar'), [('g:a', '1.2', '/a.jar', 'Maven pom.properties')])
        entries['META-INF/maven/g/b/pom.properties'] = 'groupId=g\nartifactId=b\nversion=2.3\n'
        with zipfile.ZipFile(io.BytesIO(archive(entries))) as jar:
            rows = scanner.metadata(jar, '/shaded.jar')
        self.assertEqual([r[0] for r in rows], ['wrong', 'g:a', 'g:b'])
        self.assertEqual(len(set(r[2] for r in rows)), 3)

    def test_boot_and_war_nested_coordinates(self):
        nested = archive({'META-INF/maven/g/a/pom.properties': 'groupId=g\nartifactId=a\nversion=1.2\n'})
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'app.jar'
            jar.write_bytes(archive({'BOOT-INF/lib/a.jar': nested, 'WEB-INF/lib/a.jar': nested}))
            rows = list(scanner.inspect(jar, '/app.jar'))
        self.assertEqual([r[0] for r in rows], ['app.jar', 'g:a', 'g:a'])
        self.assertEqual(rows[-1][2], '/app.jar!/WEB-INF/lib/a.jar')

    def test_wildcard_uses_distinct_paths_and_continues_after_corrupt_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc = root / '123'
            libs = proc / 'root/lib'
            libs.mkdir(parents=True)
            (proc / 'comm').write_text('java\n')
            (proc / 'cmdline').write_bytes(b'java\0-Dsecret=NEVER-EXPORT\0-cp\0/lib/*\0Main\0')
            (libs / 'a-corrupt.jar').write_bytes(b'invalid')
            (libs / 'library-1.2.jar').write_bytes(archive({}))
            (libs / 'other-2.3.jar').write_bytes(archive({}))
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                scanner.collect(str(root))
            rows = [json.loads(line.split('\t', 1)[1]) for line in output.getvalue().splitlines() if line.startswith('LIBRARY\t')]
            self.assertEqual([r['path'] for r in rows], ['/lib/library-1.2.jar', '/lib/other-2.3.jar'])
            self.assertIn('LIBISSUE', output.getvalue())
            self.assertNotIn('NEVER-EXPORT', output.getvalue())

    def test_unavailable_metadata_and_limits(self):
        with zipfile.ZipFile(io.BytesIO(archive({}))) as jar:
            self.assertEqual(scanner.metadata(jar, '/library-1.2.jar')[0][3], 'filename inference')
        with zipfile.ZipFile(io.BytesIO(archive({'META-INF/maven/g/a/pom.properties': 'x' * 65537}))) as jar:
            with self.assertRaises(ValueError):
                scanner.metadata(jar, '/a.jar')


if __name__ == '__main__':
    unittest.main()
