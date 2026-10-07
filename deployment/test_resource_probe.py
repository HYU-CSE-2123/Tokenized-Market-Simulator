"""Pure measurement helper checks: no Docker/cloud/provider invocation."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('probe', Path(__file__).with_name('resource-probe.py'))
probe = importlib.util.module_from_spec(spec); spec.loader.exec_module(probe)


class ResourceProbeTest(unittest.TestCase):
    def test_binary_and_decimal_units_are_not_confused(self):
        self.assertEqual(probe.mib('1GiB'), 1024)
        self.assertEqual(probe.mib('512KiB'), .5)
        self.assertAlmostEqual(probe.mib('100MB'), 95.367431640625)
        self.assertEqual(probe.mib('1048576B'), 1)

    def test_unknown_units_fail_instead_of_faking_measurements(self):
        with self.assertRaises(ValueError): probe.mib('5unknown')


if __name__ == '__main__': unittest.main()
