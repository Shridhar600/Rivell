#!/usr/bin/env python3
"""Generate M2 knowledge fixtures: playbook/clause-library DOCX and a knowledge XLSX."""
import zipfile
from pathlib import Path

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
OUT = Path(__file__).resolve().parent

SS = 'xmlns:ss="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
SS_RAW = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'

DOCX_CONTENT_TYPES = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>'''

DOCX_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>'''

DOCX_STYLES = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {W}>
  <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/></w:style>
  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
  <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
</w:styles>'''


def p(text, style=None):
    ppr = f'<w:pPr><w:pStyle w:val="{style}"/></w:pPr>' if style else ''
    return f'<w:p>{ppr}<w:r><w:t xml:space="preserve">{text}</w:t></w:r></w:p>'


def cell(text, ref, style=None):
    s = f' s="{style}"' if style else ''
    return (f'<c r="{ref}"{s} t="inlineStr"><is><t>{text}</t></is></c>')


def row(index, cells):
    return f'<row r="{index}">{"".join(cells)}</row>'


XLSX_CONTENT_TYPES = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>'''

XLSX_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>'''

XLSX_WORKBOOK_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>'''

XLSX_WORKBOOK = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Positions" sheetId="1" r:id="rId1"/>
    <sheet name="Notes" sheetId="2" r:id="rId2"/>
  </sheets>
</workbook>'''

XLSX_STYLES = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
  <borders count="1"><border/></borders>
  <cellStyleXfs count="1"><xf/></cellStyleXfs>
  <cellXfs count="1"><xf xfId="0"/></cellXfs>
</styleSheet>'''


def write_zip(name, parts):
    path = OUT / name
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for member, content in parts.items():
            z.writestr(member, content)
    print('wrote', path.name)


def write_docx(name, body):
    write_zip(name, {
        '[Content_Types].xml': DOCX_CONTENT_TYPES,
        '_rels/.rels': DOCX_RELS,
        'word/document.xml': f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document {W}><w:body>{body}</w:body></w:document>''',
        'word/styles.xml': DOCX_STYLES,
    })


playbook_body = (
    p('Negotiation Playbook 2026', 'Title')
    + p('Limitation of Liability', 'Heading1')
    + p('Liability caps below 1x annual fees are preferred.')
    + p('Anything above 2x annual fees requires GC approval.')
    + '<w:tbl><w:tr>'
    + '<w:tc><w:p><w:r><w:t>Clause</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>Preferred</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>Fallback</w:t></w:r></w:p></w:tc>'
    + '</w:tr><w:tr>'
    + '<w:tc><w:p><w:r><w:t>Liability cap</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>1x annual fees</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>2x annual fees</w:t></w:r></w:p></w:tc>'
    + '</w:tr></w:tbl>'
    + p('Termination', 'Heading1')
    + p('Termination for convenience requires 30 days notice.')
    + '<w:sectPr/>'
)

clause_library_body = (
    p('Approved Clause Library', 'Title')
    + p('Liability', 'Heading1')
    + p('The total liability of Supplier shall not exceed the fees paid in the preceding 12 months.')
    + '<w:sectPr/>'
)

# A table whose header row labels two different columns "Cap". Both cells carry a different
# value, so a payload keyed by header label silently drops one of them.
duplicate_header_playbook_body = (
    p('Duplicate Header Playbook', 'Title')
    + p('Caps', 'Heading1')
    + p('Two columns share the header label Cap.')
    + '<w:tbl><w:tr>'
    + '<w:tc><w:p><w:r><w:t>Clause</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>Cap</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>Cap</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>Note</w:t></w:r></w:p></w:tc>'
    + '</w:tr><w:tr>'
    + '<w:tc><w:p><w:r><w:t>Liability cap</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>1x annual fees</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>3x annual fees</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>first tier</w:t></w:r></w:p></w:tc>'
    + '</w:tr><w:tr>'
    + '<w:tc><w:p><w:r><w:t>Indemnity cap</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>2x annual fees</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>4x annual fees</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>second tier</w:t></w:r></w:p></w:tc>'
    + '</w:tr></w:tbl>'
    + '<w:sectPr/>'
)

sheet1 = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1"><c r="A1" t="inlineStr"><is><t>Clause</t></is></c><c r="B1" t="inlineStr"><is><t>Preferred</t></is></c><c r="C1" t="inlineStr"><is><t>Fallback</t></is></c></row>
    <row r="2"><c r="A2" t="inlineStr"><is><t>Liability cap</t></is></c><c r="B2" t="inlineStr"><is><t>1x annual fees</t></is></c><c r="C2" t="inlineStr"><is><t>2x annual fees</t></is></c></row>
    <row r="3"><c r="A3" t="inlineStr"><is><t>Indemnification</t></is></c><c r="B3" t="inlineStr"><is><t>Supplier indemnifies</t></is></c><c r="C3" t="inlineStr"><is><t>Mutual indemnity</t></is></c></row>
  </sheetData>
</worksheet>'''

sheet2 = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1"><c r="A1" t="inlineStr"><is><t>Escalation rules apply to all contract families</t></is></c></row>
    <row r="2"><c r="A2" t="inlineStr"><is><t>Escalation</t></is></c><c r="C2" t="inlineStr"><is><t>GC approval above 2x</t></is></c></row>
  </sheetData>
  <mergeCells count="1"><mergeCell ref="A1:C1"/></mergeCells>
</worksheet>'''

if __name__ == '__main__':
    write_docx('knowledge-playbook.docx', playbook_body)
    write_docx('clause-library.docx', clause_library_body)
    write_docx('duplicate-header-playbook.docx', duplicate_header_playbook_body)
    write_zip('knowledge-matrix.xlsx', {
        '[Content_Types].xml': XLSX_CONTENT_TYPES,
        '_rels/.rels': XLSX_RELS,
        'xl/workbook.xml': XLSX_WORKBOOK,
        'xl/_rels/workbook.xml.rels': XLSX_WORKBOOK_RELS,
        'xl/styles.xml': XLSX_STYLES,
        'xl/worksheets/sheet1.xml': sheet1,
        'xl/worksheets/sheet2.xml': sheet2,
    })
    (OUT / 'invalid-bytes.xlsx').write_bytes(b'this is not a zip package')
    print('wrote invalid-bytes.xlsx')
