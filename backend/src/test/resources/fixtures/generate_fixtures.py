#!/usr/bin/env python3
"""Generate M1 parser fixture DOCX files as hand-authored minimal OOXML packages."""
import zipfile
from pathlib import Path

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
R_NS = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
OUT = Path(__file__).resolve().parent

CONTENT_TYPES = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
  <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
  <Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>
</Types>'''

RELS = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>'''

STYLES = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {W}>
  <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/></w:style>
  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
  <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
</w:styles>'''


def p(text, style=None, numbering=None):
    ppr = ''
    if style:
        ppr += f'<w:pStyle w:val="{style}"/>'
    if numbering:
        ppr += f'<w:numPr><w:ilvl w:val="0"/><w:numId w:val="{numbering}"/></w:numPr>'
    ppr_xml = f'<w:pPr>{ppr}</w:pPr>' if ppr else ''
    return f'<w:p>{ppr_xml}<w:r><w:t xml:space="preserve">{text}</w:t></w:r></w:p>'


def sect_pr(extra=''):
    return f'<w:sectPr>{extra}</w:sectPr>'


def write_docx(name, document_xml, extra_parts=None, document_rels_extra=''):
    parts = {
        '[Content_Types].xml': CONTENT_TYPES,
        '_rels/.rels': RELS,
        'word/document.xml': document_xml,
        'word/_rels/document.xml.rels': f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{document_rels_extra}
</Relationships>''',
        'word/styles.xml': STYLES,
    }
    if extra_parts:
        parts.update(extra_parts)
    with zipfile.ZipFile(OUT / name, 'w', zipfile.ZIP_DEFLATED) as z:
        for member, content in parts.items():
            z.writestr(member, member if isinstance(member, str) else member)
            z.writestr(member, content)


def write_document(name, document_xml, extra_parts=None, document_rels_extra=''):
    rels = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>{document_rels_extra}
</Relationships>'''
    parts = {
        '[Content_Types].xml': CONTENT_TYPES,
        '_rels/.rels': RELS,
        'word/document.xml': document_xml,
        'word/_rels/document.xml.rels': rels,
        'word/styles.xml': STYLES,
    }
    if extra_parts:
        parts.update(extra_parts)
    with zipfile.ZipFile(OUT / name, 'w', zipfile.ZIP_DEFLATED) as z:
        for member, content in parts.items():
            z.writestr(member, content)


# --- numbered-list.docx: automatic decimal list + bullet list -----------------
numbering = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering {W}>
  <w:abstractNum w:abstractNumId="0">
    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
  </w:abstractNum>
  <w:abstractNum w:abstractNumId="1">
    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#8226;"/></w:lvl>
  </w:abstractNum>
  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
  <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
</w:numbering>'''

body = [
    p('Offer Letter Terms', 'Title'),
    p('The following payment schedule applies to every milestone.', style='Normal'),
    p('Kickoff payment due on signature.', numbering=1),
    p('Milestone payment on acceptance.', numbering=1),
    p('Final payment after support period.', numbering=1),
    p('Bullet one', numbering=2),
    p('Bullet two', numbering=2),
    p('Closing paragraph without numbering.', style='Normal'),
]
doc = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document {W} {R_NS}><w:body>{''.join(body)}{sect_pr()}</w:body></w:document>'''
write_document('numbered-list.docx', doc, extra_parts={
    'word/numbering.xml': numbering,
}, document_rels_extra=(
    '<Relationship Id="rIdNum" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>'
))

# --- with-sdt-and-breaks.docx: sdt container, br/tab, hyperlink, unknown body element
rels = (
    '<Relationship Id="rIdLink" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/" TargetMode="External"/>'
)
body = [
    p('Working Terms', 'Heading1'),
    # an unrecognized element occupying a real body slot between paragraphs
    '<nsx:thing xmlns:nsx="http://example.invalid/schema"/>',
    p('Before the content control.'),
    '<w:sdt><w:sdtPr><w:alias w:val="Scope"/></w:sdtPr><w:sdtContent>'
    + p('Inside content control.')
    + p('Second inside content control.')
    + '</w:sdtContent></w:sdt>',
    '<w:p><w:r><w:t>line one</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>line two</w:t></w:r>'
    '<w:r><w:tab/></w:r><w:r><w:t>after tab</w:t></w:r></w:p>',
    '<w:p><w:hyperlink r:id="rIdLink"><w:r><w:t>visit the site</w:t></w:r></w:hyperlink></w:p>',
]
doc = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document {W} {R_NS}><w:body>{''.join(body)}{sect_pr()}</w:body></w:document>'''
write_document('with-sdt-and-breaks.docx', doc, document_rels_extra=rels)

# --- with-omitted-parts.docx: header/footer with text + a real footnote -------
header = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr {W}><w:p><w:r><w:t>Confidential draft</w:t></w:r></w:p></w:hdr>'''
footer = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr {W}><w:p><w:r><w:t>Page footer</w:t></w:r></w:p></w:ftr>'''
footnotes = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:footnotes {W}>
  <w:footnote w:type="separator" w:id="-1"><w:p/></w:footnote>
  <w:footnote w:type="continuationSeparator" w:id="0"><w:p/></w:footnote>
  <w:footnote w:id="2"><w:p><w:r><w:t>The effective date is defined in section 3.</w:t></w:r></w:p></w:footnote>
</w:footnotes>'''
body = [
    p('Consulting Terms', 'Heading1'),
    '<w:p><w:r><w:t>The rates apply</w:t></w:r><w:r><w:footnoteReference w:id="2"/></w:r>'
    '<w:r><w:t> for the initial term.</w:t></w:r></w:p>',
]
sect = sect_pr(
    '<w:headerReference r:id="rIdHdr"/><w:footerReference r:id="rIdFtr"/>'
    '<w:pgSz w:w="12240" w:h="15840"/>')
doc = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document {W} {R_NS}><w:body>{''.join(body)}{sect}</w:body></w:document>'''
write_document('with-omitted-parts.docx', doc, extra_parts={
    'word/header1.xml': header,
    'word/footer1.xml': footer,
    'word/footnotes.xml': footnotes,
}, document_rels_extra=(
    '<Relationship Id="rIdHdr" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>'
    '<Relationship Id="rIdFtr" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>'
    '<Relationship Id="rIdFtn" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>'
))

# --- interleaved-blocks.docx: paragraphs and tables interleaved at body level,
# plus a nested table inside a table cell (deferred structure, must warn)
def table(rows):
    out = []
    for row_cells in rows:
        cells = ''.join(f'<w:tc><w:tcPr/><w:p><w:r><w:t>{c}</w:t></w:r></w:p></w:tc>'
                        for c in row_cells)
        out.append(f'<w:tr>{cells}</w:tr>')
    return f'<w:tbl><w:tblPr/>{"".join(out)}</w:tbl>'


nested_table_cell = (
    '<w:tc><w:tcPr/>'
    '<w:p><w:r><w:t>Cell with nested content</w:t></w:r></w:p>'
    + table([['nested a', 'nested b']])
    + '</w:tc>')

body = [
    p('Interleaved Structure', 'Heading1'),
    p('First paragraph before any table.'),
    table([['one', 'two'], ['three', 'four']]),
    p('Paragraph between the two tables.'),
    f'<w:tbl><w:tblPr/><w:tr>{nested_table_cell}</w:tr></w:tbl>',
    p('Last paragraph after all tables.'),
]
doc = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document {W} {R_NS}><w:body>{''.join(body)}{sect_pr()}</w:body></w:document>'''
write_document('interleaved-blocks.docx', doc)

print('fixtures written to', OUT)
