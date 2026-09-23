#!/usr/bin/env python3
"""Generate M5 contract semantic-enrichment fixtures: representative NDA/MSA
contracts plus adversarial variants for boundary behavior."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_knowledge_fixtures import p, write_docx  # noqa: E402

OUT = Path(__file__).resolve().parent

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
DOCX_STYLES_WITH_H2 = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {W}>
  <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/></w:style>
  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
  <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/></w:style>
  <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
</w:styles>'''


def write_docx_with_h2(name, body):
    import zipfile
    import generate_knowledge_fixtures as g
    path = OUT / name
    parts = {
        '[Content_Types].xml': g.DOCX_CONTENT_TYPES,
        '_rels/.rels': g.DOCX_RELS,
        'word/document.xml': f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document {W}><w:body>{body}</w:body></w:document>''',
        'word/styles.xml': DOCX_STYLES_WITH_H2,
    }
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for member, content in parts.items():
            z.writestr(member, content)
    print('wrote', path.name)


def tbl(rows):
    body_rows = []
    for values in rows:
        cells = ''.join(
            f'<w:tc><w:p><w:r><w:t>{c}</w:t></w:r></w:p></w:tc>' for c in values)
        body_rows.append(f'<w:tr>{cells}</w:tr>')
    grid = ''.join('<w:gridCol/>' for _ in rows[0])
    return ('<w:tbl><w:tblGrid>' + grid + '</w:tblGrid>' + ''.join(body_rows) + '</w:tbl>')


nda_body = (
    p('Mutual Non-Disclosure Agreement', 'Title')
    + p('This Mutual Non-Disclosure Agreement (this "Agreement") is entered into '
        'as of March 15, 2026 (the "Effective Date") by and between Helios Systems Inc., '
        'a Delaware corporation ("Disclosing Party"), and Vertex Analytics Ltd., '
        'a United Kingdom private limited company ("Receiving Party"). Helios Systems '
        'Inc. and Vertex Analytics Ltd. may each be referred to as a "Party" and '
        'together as the "Parties".', 'Normal')
    + p('1. Definitions', 'Heading1')
    + p('1.1 "Confidential Information" means any non-public business, technical, '
        'or commercial information disclosed by the Disclosing Party to the Receiving '
        'Party, whether or not marked as confidential.', 'Normal')
    + p('1.2 "Representatives" means a Party\'s employees, officers, directors, '
        'and professional advisors.', 'Normal')
    + p('2. Confidentiality', 'Heading1')
    + p('2.1 The Receiving Party shall protect the Confidential Information of the '
        'Disclosing Party with at least the same degree of care it uses for its own '
        'confidential information, and in no event less than reasonable care.', 'Normal')
    + p('2.2 The Receiving Party may disclose Confidential Information only to its '
        'Representatives who need to know it for the Purpose, and shall remain '
        'responsible for their compliance with this Section 2.', 'Normal')
    + p('3. Exclusions', 'Heading1')
    + p('3.1 Confidential Information does not include information that is or becomes '
        'public through no fault of the Receiving Party, was lawfully known to the '
        'Receiving Party before disclosure, or is independently developed without use '
        'of the Confidential Information.', 'Normal')
    + p('4. Permitted Disclosure', 'Heading1')
    + p('4.1 Notwithstanding Section 2, the Receiving Party may disclose Confidential '
        'Information to the extent required by law or regulation, provided that the '
        'Receiving Party gives the Disclosing Party prompt written notice where lawful.', 'Normal')
    + p('5. Term and Survival', 'Heading1')
    + p('5.1 This Agreement begins on the Effective Date and continues for three (3) '
        'years unless terminated earlier by either Party on thirty (30) days\' prior '
        'written notice.', 'Normal')
    + p('5.2 The Receiving Party\'s obligations under Section 2 survive termination '
        'of this Agreement for three (3) years from the date of disclosure.', 'Normal')
    + p('6. Return or Destruction', 'Heading1')
    + p('6.1 On termination or on the Disclosing Party\'s written request, the '
        'Receiving Party shall promptly return or destroy all Confidential Information '
        'and certify the destruction in writing.', 'Normal')
    + p('7. Governing Law', 'Heading1')
    + p('7.1 This Agreement is governed by the laws of the State of Delaware, '
        'United States, without regard to conflict-of-law rules.', 'Normal')
    + p('8. Miscellaneous', 'Heading1')
    + p('8.1 This Agreement is the entire agreement between the Parties concerning '
        'its subject matter and supersedes all prior discussions. Any amendment must '
        'be in writing and signed by both Parties. Neither Party may assign this '
        'Agreement without the other Party\'s prior written consent.', 'Normal')
    + p('8.2 Any dispute arising out of this Agreement shall be resolved exclusively '
        'in the state or federal courts located in Delaware as set forth in Section 7.', 'Normal')
    + p('IN WITNESS WHEREOF, the Parties have executed this Agreement as of the '
        'Effective Date.', 'Normal')
    + p('Helios Systems Inc.', 'Heading2')
    + p('By: Maya Chen, Chief Executive Officer', 'Normal')
    + p('Vertex Analytics Ltd.', 'Heading2')
    + p('By: Oliver Grant, Managing Director', 'Normal')
    + '<w:sectPr/>'
)

msa_body = (
    p('Master Services Agreement', 'Title')
    + p('This Master Services Agreement (this "Agreement") is entered into as of '
        'January 1, 2026 (the "Effective Date") between Northwind Customer Group LLC, '
        'a Delaware limited liability company ("Customer"), and Pallas Cloud Services '
        'GmbH, a German company with registered offices in Berlin ("Supplier"). '
        'Northwind Customer Group LLC and Pallas Cloud Services GmbH are each a '
        '"Party" and together the "Parties".', 'Normal')
    + p('1. Definitions', 'Heading1')
    + p('1.1 "Services" means the managed cloud services described in Schedule A '
        'and ordered under an Order Form.', 'Normal')
    + p('1.2 "Fees" means the fees set out in Schedule A or in the applicable '
        'Order Form.', 'Normal')
    + p('1.3 "Deliverables" means the deliverables identified in an Order Form.', 'Normal')
    + p('2. Services', 'Heading1')
    + p('2.1 Supplier shall provide the Services in accordance with Schedule A '
        'and each Order Form executed under this Agreement.', 'Normal')
    + p('2.2 Supplier shall perform the Services with professional skill and care '
        'consistent with generally accepted industry standards.', 'Normal')
    + p('2.3 Customer shall pay the Fees in accordance with Section 3.', 'Normal')
    + p('2.4 Schedule A may be amended only by a written change order signed by '
        'authorized representatives of both Parties.', 'Normal')
    + p('3. Fees and Payment', 'Heading1')
    + p('3.1 Customer shall pay the Fees within thirty (30) days of receipt of a '
        'correct invoice.', 'Normal')
    + p('3.2 Late payments accrue interest at the rate of one percent (1%) per month '
        'as set forth in Section 3.1.', 'Normal')
    + p('4. Term and Renewal', 'Heading1')
    + p('4.1 This Agreement begins on the Effective Date and continues for an initial '
        'term of twenty-four (24) months (the "Initial Term").', 'Normal')
    + p('4.2 This Agreement automatically renews for successive twelve (12) month '
        'periods unless either Party gives notice of non-renewal at least sixty (60) '
        'days before the end of the then-current term.', 'Normal')
    + p('5. Termination', 'Heading1')
    + p('5.1 Either Party may terminate this Agreement for convenience upon sixty (60) '
        'days\' prior written notice to the other Party.', 'Normal')
    + p('5.2 Either Party may terminate this Agreement immediately on written notice '
        'if the other Party commits a material breach that remains uncured thirty (30) '
        'days after written notice of the breach.', 'Normal')
    + p('6. Limitation of Liability', 'Heading1')
    + p('6.1 Subject to Section 6.3, Supplier\'s total aggregate liability arising out '
        'of or related to this Agreement shall not exceed an amount equal to three (3) '
        'times the Fees paid or payable in the twelve (12) months immediately preceding '
        'the event giving rise to the claim.', 'Normal')
    + p('6.2 Subject to Section 6.3, neither Party shall be liable for indirect, '
        'special, incidental, or consequential damages, or for lost profits or lost '
        'data, even if advised of the possibility of those damages.', 'Normal')
    + p('6.3 The exclusions in Section 6.2 do not apply to breaches of Section 8 '
        '(Confidentiality) or to a Party\'s indemnification obligations under Section 7.', 'Normal')
    + p('7. Indemnification', 'Heading1')
    + p('7.1 Supplier shall indemnify and defend Customer against third-party claims '
        'alleging that the Services or Deliverables infringe any patent, copyright, or '
        'trade secret, subject to the limitations in Section 6.', 'Normal')
    + p('7.2 Customer shall indemnify Supplier against third-party claims arising from '
        'data or materials provided by Customer in breach of Section 8.', 'Normal')
    + p('8. Confidentiality', 'Heading1')
    + p('8.1 Each Party shall protect the other Party\'s confidential information with '
        'the same care it uses for its own similar information, and in no event less '
        'than reasonable care.', 'Normal')
    + p('8.2 The obligations in this Section 8 survive termination of this Agreement '
        'for three (3) years.', 'Normal')
    + p('9. Governing Law', 'Heading1')
    + p('9.1 This Agreement and any dispute arising out of it are governed by the '
        'laws of England and Wales, and the courts of England and Wales have exclusive '
        'jurisdiction.', 'Normal')
    + p('10. Survival', 'Heading1')
    + p('10.1 Sections 5 (Termination), 6 (Limitation of Liability), 7 '
        '(Indemnification), 8 (Confidentiality), and 11 (General) survive termination '
        'of this Agreement.', 'Normal')
    + p('11. General', 'Heading1')
    + p('11.1 Neither Party may assign this Agreement without the other Party\'s prior '
        'written consent, except to a successor in a merger or sale of substantially '
        'all assets. Notices must be given in writing to the addresses on the first '
        'Order Form. This Agreement is the entire agreement between the Parties.', 'Normal')
    + p('IN WITNESS WHEREOF, the Parties have executed this Agreement as of the '
        'Effective Date.', 'Normal')
    + p('Northwind Customer Group LLC', 'Heading2')
    + p('By: Dana Whitfield, VP Procurement', 'Normal')
    + p('Pallas Cloud Services GmbH', 'Heading2')
    + p('By: Lars Ohman, Managing Director', 'Normal')
    + p('Schedule A — Services', 'Heading1')
    + p('The table below describes the managed services supplied under each Order Form. '
        'Schedule A forms part of this Agreement and may be amended as set forth in '
        'Section 2.4.', 'Normal')
    + tbl([
        ['Service', 'Description', 'Service Level'],
        ['Managed Hosting', '24x7 managed cloud hosting for the Customer platform', '99.9% monthly uptime'],
        ['Monitoring', 'Continuous monitoring and incident response', '15-minute response'],
    ])
    + '<w:sectPr/>'
)

dual_concept_body = (
    p('Services Amendment', 'Title')
    + p('This amendment is entered into as of June 1, 2026 by and between '
        'Meridian Media Corp ("Customer") and Bluepeak Software Ltd ("Supplier").', 'Normal')
    + p('6. Limitation of Liability', 'Heading1')
    + p('1.1 Supplier\'s aggregate liability shall not exceed the Fees paid in the '
        'preceding twelve (12) months. Supplier shall further indemnify Customer '
        'against third-party intellectual property infringement claims arising from '
        'the licensed software.', 'Normal')
    + p('2. Data Protection', 'Heading1')
    + p('2.1 Each Party shall process personal data only on documented instructions '
        'from the other Party and shall implement appropriate technical and '
        'organizational security measures.', 'Normal')
    + '<w:sectPr/>'
)

ambiguous_party_body = (
    p('Consulting Agreement', 'Title')
    + p('This Consulting Agreement is entered into as of February 2, 2026 by and '
        'between Acme Corp and Beta Ltd (each a "Party", together the "Parties"). '
        'Each Party shall perform its obligations in good faith.', 'Normal')
    + p('1. Fees', 'Heading1')
    + p('1.1 The Client shall pay the Consultant the fees stated in each statement '
        'of work. The terms of this Section 1 apply to all statements of work.', 'Normal')
    + '<w:sectPr/>'
)

reference_hazards_body = (
    p('Reseller Agreement', 'Title')
    + p('This Reseller Agreement is entered into as of April 4, 2026 by and between '
        'Craftly Goods Inc ("Supplier") and Northgate Retail LLC ("Reseller").', 'Normal')
    + p('1. Definitions', 'Heading1')
    + p('1.1 "Products" means the goods listed on Supplier\'s current price list.', 'Normal')
    + p('1.2 "Confidential Information" means any business or commercial information '
        'exchanged between the Parties.', 'Normal')
    + p('1.3 "Confidential Information" means for the purposes of the exclusions in '
        'this Section 1 only, information marked confidential by the disclosing Party.', 'Normal')
    + p('2. Orders and Term', 'Heading1')
    + p('2.1 Reseller may place orders as set forth in Section 99, which does not '
        'exist in this Agreement.', 'Normal')
    + p('2.2 The parties shall comply with the service levels in the Data Processing '
        'Agreement dated November 11, 2025, which is not part of this document.', 'Normal')
    + p('2.2 The handling of returns follows Section 1, and the pricing procedure '
        'follows the Master Distribution Agreement between the Parties.', 'Normal')
    + p('5. Termination', 'Heading1')
    + p('3.1 Either Party may terminate for material breach on thirty (30) days '
        'notice. This Agreement terminates automatically three (3) years after '
        'the Effective Date.', 'Normal')
    + '<w:sectPr/>'
)

write_docx('m5-nda-counterparty.docx', nda_body)
write_docx_with_h2('m5-msa-services.docx', msa_body)
write_docx_with_h2('m5-dual-concept.docx', dual_concept_body)
write_docx_with_h2('m5-ambiguous-party.docx', ambiguous_party_body)
write_docx_with_h2('m5-reference-hazards.docx', reference_hazards_body)
