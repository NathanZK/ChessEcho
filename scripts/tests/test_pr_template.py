import pathlib
import re
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
TEMPLATE_PATH = REPOSITORY_ROOT / ".github" / "PULL_REQUEST_TEMPLATE.md"


class PullRequestTemplateTest(unittest.TestCase):
    def test_template_defines_reusable_human_facing_contract(self):
        template = TEMPLATE_PATH.read_text(encoding="utf-8")
        headings = re.findall(r"^##\s+(.+?)\s*$", template, flags=re.MULTILINE)

        self.assertEqual(["What", "Why", "Testing"], headings)
        self.assertIn("what changed", template.lower())
        self.assertIn("motivation", template.lower())
        self.assertIn("optional", template.lower())
        self.assertIn("what was tested", template.lower())
        self.assertIn("not applicable", template.lower())
        self.assertNotIn("workflow artifact", template.lower())
        self.assertNotIn("approval artifact", template.lower())


if __name__ == "__main__":
    unittest.main()
