"""Writes coverage-mixed-parts.docx and coverage-workbook.xlsx: raw OOXML with every place text can hide.

Body paragraphs, a tracked insertion and deletion, a simple field, a text box stored the way
Word stores it (DrawingML choice + VML fallback), a table, a header, a footer, a footnote and
a comment. Run: python3 generate_coverage_fixtures.py (writes next to this script).
"""
import pathlib
import zipfile

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
NS = (f'{W} xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
      'xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" '
      'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
      'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
      'xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" '
      'xmlns:v="urn:schemas-microsoft-com:vml" mc:Ignorable="wps"')
REV = 'w:author="Counterparty" w:date="2026-10-01T00:00:00Z"'


def p(text, bold=False):
    rpr = "<w:rPr><w:b/></w:rPr>" if bold else ""
    return f"<w:p><w:r>{rpr}<w:t xml:space=\"preserve\">{text}</w:t></w:r></w:p>"


TEXT_BOX = "<w:txbxContent><w:p><w:r><w:t>Key terms: the liability cap is twelve months of fees.</w:t></w:r></w:p></w:txbxContent>"
BOX_PARAGRAPH = (
    "<w:p><w:r><w:t xml:space=\"preserve\">Commercial summary follows. </w:t></w:r><w:r><mc:AlternateContent>"
    "<mc:Choice Requires=\"wps\"><w:drawing><wp:anchor><a:graphic><a:graphicData>"
    f"<wps:wsp><wps:txbx>{TEXT_BOX}</wps:txbx></wps:wsp>"
    "</a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>"
    f"<mc:Fallback><w:pict><v:shape><v:textbox>{TEXT_BOX}</v:textbox></v:shape></w:pict></mc:Fallback>"
    "</mc:AlternateContent></w:r></w:p>")

BODY = "".join([
    p("1. Fees", bold=True),
    p("The Customer pays the fees monthly in arrears."),
    "<w:p><w:r><w:t xml:space=\"preserve\">Payment is due within </w:t></w:r>"
    f"<w:ins w:id=\"1\" {REV}><w:r><w:t>thirty</w:t></w:r></w:ins>"
    f"<w:del w:id=\"2\" {REV}><w:r><w:delText>sixty</w:delText></w:r></w:del>"
    "<w:r><w:t xml:space=\"preserve\"> days of invoice.</w:t></w:r></w:p>",
    "<w:p><w:r><w:t xml:space=\"preserve\">Late amounts follow clause </w:t></w:r>"
    "<w:fldSimple w:instr=\" REF _Ref1 \\h \"><w:r><w:t>4.2</w:t></w:r></w:fldSimple>"
    "<w:r><w:t xml:space=\"preserve\"> of this Agreement.</w:t></w:r></w:p>",
    "<w:p><w:r><w:t xml:space=\"preserve\">See page </w:t></w:r><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
    "<w:r><w:instrText xml:space=\"preserve\"> PAGEREF _Toc1 \\h </w:instrText></w:r>"
    "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>7</w:t></w:r>"
    "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r><w:r><w:t xml:space=\"preserve\"> for details.</w:t></w:r></w:p>",
    BOX_PARAGRAPH,
    "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Service credits apply after two outages.</w:t></w:r></w:p></w:tc></w:tr></w:tbl>",
    p("2. Term", bold=True),
    "<w:p><w:r><w:t>This Agreement lasts three years.</w:t></w:r>"
    "<w:r><w:footnoteReference w:id=\"1\"/></w:r></w:p>",
    "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdH\"/>"
    "<w:footerReference w:type=\"default\" r:id=\"rIdF\"/></w:sectPr>",
])

DOCUMENT = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {NS}><w:body>{BODY}</w:body></w:document>'
HEADER = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:hdr {W}>{p("CONFIDENTIAL DRAFT v3")}</w:hdr>'
FOOTER = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:ftr {W}>{p("Northwind order form")}</w:ftr>'
FOOTNOTES = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:footnotes {W}>'
             '<w:footnote w:type="separator" w:id="-1"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>'
             f'<w:footnote w:id="1">{p("Renewal needs written notice from either party.")}</w:footnote></w:footnotes>')
COMMENTS = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:comments {W}>'
            f'<w:comment w:id="0" w:author="Counterparty">{p("Please confirm the invoice cycle.")}</w:comment></w:comments>')

WML = "application/vnd.openxmlformats-officedocument.wordprocessingml."
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
CONTENT_TYPES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    f'<Override PartName="/word/document.xml" ContentType="{WML}document.main+xml"/>'
    f'<Override PartName="/word/header1.xml" ContentType="{WML}header+xml"/>'
    f'<Override PartName="/word/footer1.xml" ContentType="{WML}footer+xml"/>'
    f'<Override PartName="/word/footnotes.xml" ContentType="{WML}footnotes+xml"/>'
    f'<Override PartName="/word/comments.xml" ContentType="{WML}comments+xml"/>'
    '</Types>')
ROOT_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
    '</Relationships>')
DOC_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    f'<Relationship Id="rIdH" Type="{REL}header" Target="header1.xml"/>'
    f'<Relationship Id="rIdF" Type="{REL}footer" Target="footer1.xml"/>'
    f'<Relationship Id="rIdN" Type="{REL}footnotes" Target="footnotes.xml"/>'
    f'<Relationship Id="rIdC" Type="{REL}comments" Target="comments.xml"/>'
    '</Relationships>')


S = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
R = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
SML = "application/vnd.openxmlformats-officedocument.spreadsheetml."
XLSX = {
    "[Content_Types].xml": (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        f'<Override PartName="/xl/workbook.xml" ContentType="{SML}sheet.main+xml"/>'
        f'<Override PartName="/xl/worksheets/sheet1.xml" ContentType="{SML}worksheet+xml"/>'
        f'<Override PartName="/xl/worksheets/sheet2.xml" ContentType="{SML}worksheet+xml"/>'
        f'<Override PartName="/xl/sharedStrings.xml" ContentType="{SML}sharedStrings+xml"/>'
        f'<Override PartName="/xl/comments1.xml" ContentType="{SML}comments+xml"/>'
        '<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>'
        '</Types>'),
    "_rels/.rels": (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        '</Relationships>'),
    # sheet order in the workbook is Positions (sheet2.xml) then Notes (sheet1.xml)
    "xl/workbook.xml": (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook {S} {R}><sheets>'
        '<sheet name="Positions" sheetId="1" r:id="rId2"/><sheet name="Notes" sheetId="2" r:id="rId1"/>'
        '</sheets></workbook>'),
    "xl/_rels/workbook.xml.rels": (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        f'<Relationship Id="rId1" Type="{REL}worksheet" Target="worksheets/sheet1.xml"/>'
        f'<Relationship Id="rId2" Type="{REL}worksheet" Target="worksheets/sheet2.xml"/>'
        f'<Relationship Id="rId3" Type="{REL}sharedStrings" Target="sharedStrings.xml"/>'
        '</Relationships>'),
    "xl/sharedStrings.xml": (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><sst {S} count="4" uniqueCount="4">'
        '<si><t>Topic</t></si><si><t>Preferred position</t></si>'
        '<si><r><t xml:space="preserve">Cap at </t></r><r><rPr><b/></rPr><t>twelve months</t></r>'
        '<r><t xml:space="preserve"> of fees</t></r></si>'
        '<si><t>Use the latest approved template.</t></si></sst>'),
    "xl/worksheets/sheet2.xml": (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet {S} {R}><sheetData>'
        '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>'
        '<row r="2"><c r="A2" t="inlineStr"><is><t>Limitation of liability</t></is></c>'
        '<c r="B2" t="s"><v>2</v></c><c r="C2"><v>12</v></c></row>'
        '</sheetData><drawing r:id="rIdD"/></worksheet>'),
    "xl/worksheets/_rels/sheet2.xml.rels": (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        f'<Relationship Id="rIdC" Type="{REL}comments" Target="../comments1.xml"/>'
        f'<Relationship Id="rIdD" Type="{REL}drawing" Target="../drawings/drawing1.xml"/>'
        '</Relationships>'),
    "xl/worksheets/sheet1.xml": (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet {S}><sheetData>'
        '<row r="1"><c r="A1" t="s"><v>3</v></c></row></sheetData></worksheet>'),
    "xl/comments1.xml": (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><comments {S}><authors><author>Legal</author></authors>'
        '<commentList><comment ref="B2" authorId="0"><text><r><t>Board approval needed above this cap.</t></r></text>'
        '</comment></commentList></comments>'),
    "xl/drawings/drawing1.xml": (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" '
        'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><xdr:twoCellAnchor>'
        '<xdr:from><xdr:col>4</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>'
        '<xdr:to><xdr:col>8</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>6</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>'
        '<xdr:sp><xdr:nvSpPr><xdr:cNvPr id="2" name="TextBox 1"/><xdr:cNvSpPr txBox="1"/></xdr:nvSpPr><xdr:spPr/>'
        '<xdr:txBody><a:bodyPr/><a:p><a:r><a:t>Escalate every walk-away to the General Counsel.</a:t></a:r></a:p></xdr:txBody>'
        '</xdr:sp><xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>'),
}


def main():
    workbook = pathlib.Path(__file__).with_name("coverage-workbook.xlsx")
    with zipfile.ZipFile(workbook, "w", zipfile.ZIP_DEFLATED) as z:
        for name, xml in XLSX.items():
            z.writestr(name, xml)
    print(workbook)
    out = pathlib.Path(__file__).with_name("coverage-mixed-parts.docx")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CONTENT_TYPES)
        z.writestr("_rels/.rels", ROOT_RELS)
        z.writestr("word/document.xml", DOCUMENT)
        z.writestr("word/_rels/document.xml.rels", DOC_RELS)
        z.writestr("word/header1.xml", HEADER)
        z.writestr("word/footer1.xml", FOOTER)
        z.writestr("word/footnotes.xml", FOOTNOTES)
        z.writestr("word/comments.xml", COMMENTS)
    print(out)


if __name__ == "__main__":
    main()
