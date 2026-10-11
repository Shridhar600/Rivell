"""Unit tests for the independent redline checker. Run: python3 -m unittest scripts/eval/test_redline_check.py"""
import io
import unittest
import zipfile

from redline_check import check

NS = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
INS = '<w:ins w:id="{i}" w:author="R" w:date="2026-10-11T00:00:00Z">{body}</w:ins>'
DEL = '<w:del w:id="{i}" w:author="R" w:date="2026-10-11T00:00:00Z">{body}</w:del>'


def docx(*paragraphs):
    xml = f'<w:document {NS}><w:body>{"".join(paragraphs)}<w:sectPr/></w:body></w:document>'
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/document.xml", xml)
    return buf.getvalue()


def p(text):
    return f"<w:p><w:r><w:t>{text}</w:t></w:r></w:p>"


def deleted_p(text):
    mark = '<w:pPr><w:rPr><w:del w:id="9" w:author="R" w:date="2026-10-11T00:00:00Z"/></w:rPr></w:pPr>'
    return f"<w:p>{mark}{DEL.format(i=1, body=f'<w:r><w:delText>{text}</w:delText></w:r>')}</w:p>"


def inserted_p(text):
    mark = '<w:pPr><w:rPr><w:ins w:id="8" w:author="R" w:date="2026-10-11T00:00:00Z"/></w:rPr></w:pPr>'
    return f"<w:p>{mark}{INS.format(i=2, body=f'<w:r><w:t>{text}</w:t></w:r>')}</w:p>"


def node(nid, kind, body_index):
    return {"id": nid, "nodeType": kind, "ordinal": body_index, "sourceSpan": {"bodyIndex": body_index}}


ORIGINAL = docx(p("1. Liability"), p("Cap is fees paid."), p("2. Law"), p("New York law."))
NODES = [node("h1", "HEADING", 0), node("p1", "PARAGRAPH", 1), node("h2", "HEADING", 2), node("p2", "PARAGRAPH", 3)]
REPLACE = [{"ordinal": 1, "actionType": "REPLACE", "targetHeadingNodeId": "h1",
            "approvedLanguage": "Cap is twice the fees paid."}]


class RedlineCheckTest(unittest.TestCase):
    def test_a_correct_replacement_passes_every_check(self):
        redline = docx(p("1. Liability"), deleted_p("Cap is fees paid."), inserted_p("Cap is twice the fees paid."),
                       p("2. Law"), p("New York law."))
        result = check(ORIGINAL, redline, NODES, REPLACE)
        self.assertEqual(result["problems"], [])
        self.assertTrue(all(result[k] for k in ("opens", "preserved", "local", "applied")))

    def test_a_tracked_edit_outside_the_target_clause_is_not_local(self):
        redline = docx(p("1. Liability"), deleted_p("Cap is fees paid."), inserted_p("Cap is twice the fees paid."),
                       p("2. Law"), deleted_p("New York law."))
        result = check(ORIGINAL, redline, NODES, REPLACE)
        self.assertFalse(result["local"])
        self.assertTrue(result["preserved"])

    def test_an_untracked_text_change_breaks_preservation(self):
        redline = docx(p("1. Liability"), deleted_p("Cap is fees paid."), inserted_p("Cap is twice the fees paid."),
                       p("2. Law"), p("Delaware law."))
        result = check(ORIGINAL, redline, NODES, REPLACE)
        self.assertFalse(result["preserved"])

    def test_a_replacement_that_keeps_the_old_body_is_not_applied(self):
        redline = docx(p("1. Liability"), p("Cap is fees paid."), inserted_p("Cap is twice the fees paid."),
                       p("2. Law"), p("New York law."))
        result = check(ORIGINAL, redline, NODES, REPLACE)
        self.assertFalse(result["applied"])

    def test_an_unreadable_file_does_not_open(self):
        result = check(ORIGINAL, b"not a zip", NODES, REPLACE)
        self.assertFalse(result["opens"])


if __name__ == "__main__":
    unittest.main()
