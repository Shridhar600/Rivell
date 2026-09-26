#!/usr/bin/env python3
"""Generate the labeled M9 deterministic-redline fixture corpus.

Relationship-complete OOXML packages (styles always reachable via document rels) so
docx4j load/save preserves every part; each fixture exercises one M9 behavior.
"""
import base64
import zipfile
from pathlib import Path

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
R_NS = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
OUT = Path(__file__).resolve().parent

DOCX_MAIN = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'
MACRO_MAIN = 'application/vnd.ms-word.document.macroEnabled.main+xml'

STYLES = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {W}>
  <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/></w:style>
  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
  <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
</w:styles>'''

ROOT_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>'''

NUMBERING = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering {W}>
  <w:abstractNum w:abstractNumId="0">
    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
  </w:abstractNum>
  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
</w:numbering>'''

SETTINGS_PROTECTED = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings {W}><w:documentProtection w:edit="trackedChanges" w:enforcement="1"/></w:settings>'''

PNG_1PX = base64.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')


def run(text):
    return f'<w:r><w:t xml:space="preserve">{text}</w:t></w:r>'


def p(text, style=None, numbering=None, runs=None, ppr_extra=''):
    ppr = ''
    if style:
        ppr += f'<w:pStyle w:val="{style}"/>'
    if numbering:
        ppr += f'<w:numPr><w:ilvl w:val="0"/><w:numId w:val="{numbering}"/></w:numPr>'
    ppr += ppr_extra
    ppr_xml = f'<w:pPr>{ppr}</w:pPr>' if ppr else ''
    content = ''.join(run(part) for part in runs) if runs else run(text)
    return f'<w:p>{ppr_xml}{content}</w:p>'


def document(body):
    return f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document {W} {R_NS}><w:body>{body}<w:sectPr/></w:body></w:document>'''


def write(name, body, *, main_content_type=DOCX_MAIN, extra_content_types='',
          extra_rels='', extra_parts=None):
    content_types = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/word/document.xml" ContentType="{main_content_type}"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
{extra_content_types}</Types>'''
    rels = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
{extra_rels}</Relationships>'''
    parts = {
        '[Content_Types].xml': content_types,
        '_rels/.rels': ROOT_RELS,
        'word/document.xml': document(body),
        'word/_rels/document.xml.rels': rels,
        'word/styles.xml': STYLES,
    }
    if extra_parts:
        parts.update(extra_parts)
    path = OUT / name
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for member, content in parts.items():
            z.writestr(member, content)
    print('wrote', path.name)


# A — simple REPLACE target: one clause, simple runs ---------------------------
write('m9-simple-replace.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Services', 'Heading1')
    + p('Supplier shall provide the services described in Schedule A.')
    + p('2. Limitation of Liability', 'Heading1')
    + p('Supplier liability shall be unlimited.')
    + p('3. Confidentiality', 'Heading1')
    + p('Each party shall keep the other party information confidential.')
    + p('4. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
))

# B — fragmented-run REPLACE target -------------------------------------------
write('m9-fragmented-run.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Services', 'Heading1')
    + p('Supplier shall provide the services described in Schedule A.')
    + p('2. Limitation of Liability', 'Heading1')
    + p(None, runs=['Supplier ', 'liability ', 'shall ', 'be ', 'unlimited.'])
    + p('3. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
))

# C — DELETE target with two body paragraphs ----------------------------------
write('m9-delete.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Services', 'Heading1')
    + p('Supplier shall provide the services described in Schedule A.')
    + p('2. Non-Solicitation', 'Heading1')
    + p('Neither party shall solicit the employees of the other party.')
    + p('This restriction survives for twelve months after termination.')
    + p('3. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
))

# D — INSERT target: missing limitation of liability after indemnification ------
write('m9-insert.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Definitions', 'Heading1')
    + p('Capitalized terms have the meanings given in this Agreement.')
    + p('2. Indemnification', 'Heading1')
    + p('Each party shall indemnify the other party against third-party claims.')
    + p('3. Confidentiality', 'Heading1')
    + p('Each party shall keep the other party information confidential.')
    + p('4. Term and Survival', 'Heading1')
    + p('This Agreement continues until terminated in accordance with its terms.')
    + p('5. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
))

# E — multiple independent changes: replace + delete + insert anchor ----------
write('m9-multi-change.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Limitation of Liability', 'Heading1')
    + p('Supplier liability shall be unlimited.')
    + p('2. Non-Solicitation', 'Heading1')
    + p('Neither party shall solicit the employees of the other party.')
    + p('3. Indemnification', 'Heading1')
    + p('Each party shall indemnify the other party against third-party claims.')
    + p('4. Confidentiality', 'Heading1')
    + p('Each party shall keep the other party information confidential.')
    + p('5. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
))

# F — multi-paragraph replacement target ---------------------------------------
write('m9-multi-paragraph.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Limitation of Liability', 'Heading1')
    + p('First paragraph of the original cap language.')
    + p('Second paragraph of the original cap language.')
    + p('Third paragraph of the original cap language.')
    + p('2. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
))

# G — numbered clauses (literal text + automatic numbering) --------------------
write('m9-numbered.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Services', 'Heading1')
    + p('The following payment schedule applies to every milestone.',
        style='Normal')
    + p('Kickoff payment due on signature.', numbering=1)
    + p('Milestone payment on acceptance.', numbering=1)
    + p('2. Limitation of Liability', 'Heading1')
    + p('Supplier liability shall be unlimited.')
    + p('3. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
), extra_content_types=(
    '  <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>\n'
), extra_rels=(
    '<Relationship Id="rIdNum" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>\n'
), extra_parts={'word/numbering.xml': NUMBERING})

# H — clause whose body is a table (unsupported target) ------------------------
write('m9-table-target.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Pricing', 'Heading1')
    + '<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Fee</w:t></w:r></w:p></w:tc>'
    + '<w:tc><w:p><w:r><w:t>Amount</w:t></w:r></w:p></w:tc></w:tr></w:tbl>'
    + p('2. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
))

# I — hyperlink inside the target clause body ----------------------------------
write('m9-hyperlink-target.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Limitation of Liability', 'Heading1')
    + '<w:p><w:r><w:t xml:space="preserve">Supplier liability is limited as described at </w:t></w:r>'
    + '<w:hyperlink r:id="rIdLink"><w:r><w:t>the liability page</w:t></w:r></w:hyperlink>'
    + '<w:r><w:t xml:space="preserve">.</w:t></w:r></w:p>'
    + p('2. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
), extra_rels=(
    '<Relationship Id="rIdLink" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/liability" TargetMode="External"/>\n'
))

# J — pre-existing tracked revisions (typed failure) ---------------------------
write('m9-preexisting-revisions.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Limitation of Liability', 'Heading1')
    + p('Supplier liability shall be unlimited.')
    + p('2. Governing Law', 'Heading1')
    + '<w:p><w:ins w:id="900" w:author="Counterparty" w:date="2026-09-01T00:00:00Z">'
    + '<w:r><w:t>This Agreement is governed by the laws of Delaware.</w:t></w:r></w:ins></w:p>'
))

# M — relationship-complete package with header/footer/image -------------------
IMAGE_PARAGRAPH = (
    '<w:p><w:r><w:drawing>'
    '<wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">'
    '<wp:extent cx="914400" cy="914400"/><wp:docPr id="1" name="Picture 1"/>'
    '<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
    '<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
    '<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">'
    '<pic:nvPicPr><pic:cNvPr id="1" name="image1.png"/><pic:cNvPicPr/></pic:nvPicPr>'
    '<pic:blipFill><a:blip r:embed="rIdImage"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
    '<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="914400"/></a:xfrm>'
    '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>'
    '</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>'
)
write('m9-with-parts.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Limitation of Liability', 'Heading1')
    + p('Supplier liability shall be unlimited.')
    + p('2. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of Delaware.')
    + p('3. Miscellaneous', 'Heading1')
    + IMAGE_PARAGRAPH
), extra_content_types=(
    '  <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>\n'
    '  <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>\n'
), extra_rels=(
    '<Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>\n'
    '<Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>\n'
    '<Relationship Id="rIdImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>\n'
), extra_parts={
    'word/header1.xml': f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr {W}><w:p><w:r><w:t>Confidential draft</w:t></w:r></w:p></w:hdr>''',
    'word/footer1.xml': f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr {W}><w:p><w:r><w:t>Page footer</w:t></w:r></w:p></w:ftr>''',
    'word/media/image1.png': PNG_1PX,
})

# N — protected document (typed failure) ---------------------------------------
write('m9-protected.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Limitation of Liability', 'Heading1')
    + p('Supplier liability shall be unlimited.')
), extra_content_types=(
    '  <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>\n'
), extra_rels=(
    '<Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>\n'
), extra_parts={'word/settings.xml': SETTINGS_PROTECTED})

# O — macro-enabled main part (typed failure) ----------------------------------
write('m9-macro.docx', (
    p('Master Services Agreement', 'Title')
    + p('1. Limitation of Liability', 'Heading1')
    + p('Supplier liability shall be unlimited.')
), main_content_type=MACRO_MAIN)

# P — M9 multi-change source: liability deviation + survival gap + missing
# indemnification in one review (REPLACE + DELETE + INSERT).
write('m9-multi-change-source.docx', (
    p('Master Services Agreement', 'Title')
    + p('This Master Services Agreement is entered into as of January 1, 2026 by and '
        'between Northwind Customer Group LLC ("Customer") and Pallas Cloud Services '
        'GmbH ("Supplier").')
    + p('1. Limitation of Liability', 'Heading1')
    + p("Supplier's aggregate liability shall not exceed two times the fees paid in "
        "the preceding 12 months.")
    + p('2. Confidentiality', 'Heading1')
    + p('Each Party shall protect the other Party\'s confidential information with '
        'reasonable care and use it only for the purposes of this Agreement.')
    + p('3. Termination', 'Heading1')
    + p('Either Party may terminate this Agreement for convenience upon sixty (60) '
        'days prior written notice to the other Party.')
    + p('4. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of the State of Delaware.')
))
