"""Import-resolution regressions for the repository's local tools package."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[1]


class ToolsPackageImportTests(unittest.TestCase):
    def test_repository_tools_package_wins_over_site_package_collision(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            site_packages = Path(temporary) / "site-packages"
            collision = site_packages / "tools"
            collision.mkdir(parents=True)
            (collision / "__init__.py").write_text(
                'PACKAGE_ORIGIN = "unrelated-installed-tools"\n',
                encoding="utf-8",
            )
            code = """
import pathlib
import sys
import tools
import tools.export_portable
import tools.freeze_library
root = pathlib.Path(sys.argv[1]).resolve()
expected = root / "tools"
assert pathlib.Path(tools.__file__).resolve() == expected / "__init__.py"
assert pathlib.Path(tools.export_portable.__file__).resolve() == expected / "export_portable.py"
assert pathlib.Path(tools.freeze_library.__file__).resolve() == expected / "freeze_library.py"
"""
            environment = os.environ.copy()
            existing_pythonpath = environment.get("PYTHONPATH")
            environment["PYTHONPATH"] = str(site_packages) + (
                os.pathsep + existing_pythonpath if existing_pythonpath else ""
            )
            result = subprocess.run(
                [sys.executable, "-c", code, str(PROJECT_ROOT)],
                cwd=str(PROJECT_ROOT),
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
