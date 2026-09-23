#!/usr/bin/env python3
"""Generate M4 semantic-compilation fixtures: rich playbook, clause library,
and a playbook-looking executed-precedent source for artifact-type mismatch."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_knowledge_fixtures import p, write_docx  # noqa: E402

OUT = Path(__file__).resolve().parent

m4_playbook_body = (
    p('Rivell Negotiation Playbook', 'Title')
    + p('Master Services Agreements — customer paper, United States. '
        'Effective for agreements signed on or after 2026-01-01.', 'Heading1')
    + p('Guidance in this playbook applies to the MSA contract pack from the customer '
        'perspective on supplier paper.', 'Normal')
    + p('Confidentiality', 'Heading1')
    + p('Preferred: confidentiality obligations must survive termination of the agreement '
        'for three years and cover all business information exchanged between the parties.', 'Normal')
    + p('Fallback: accept a two-year survival period with general counsel approval only when '
        'the counterparty insists during negotiation.', 'Normal')
    + p('Prohibited: confidentiality obligations limited to marked documents only. '
        'Do not accept without general counsel escalation.', 'Normal')
    + p('Limitation of Liability', 'Heading1')
    + p('The preferred position caps supplier liability at fees paid in the preceding '
        'twelve months. No fallback below the fee cap is authorized; exceeding the cap '
        'requires escalation to general counsel before signing.', 'Normal')
    + p('Positions matrix', 'Heading1')
    + p('General guidance: this section collects recurring negotiation positions.', 'Normal')
)

# Rich playbook guidance table: Position | Trigger | Guidance | Approval
m4_playbook_table = (
    '<w:tbl>'
    '<w:tr>'
    + ''.join(f'<w:tc><w:p><w:r><w:t>{t}</w:t></w:r></w:p></w:tc>'
              for t in ('Position', 'Trigger', 'Guidance', 'Approval'))
    + '</w:tbl-props>'
    if False else ''
)
def tbl(rows):
    body_rows = []
    for r_i, values in enumerate(rows):
        cells = ''.join(
            f'<w:tc><w:p><w:r><w:t>{c}</w:t></w:r></w:p></w:tc>' for c in rows[r_i])
        body_rows.append(f'<w:tr>{cells}</w:tr>')
    grid = ''.join('<w:gridCol/>' for _ in rows[0])
    return ('<w:tbl><w:tblGrid>' + grid + '</w:tblGrid>' + ''.join(body_rows) + '</w:tbl>')

m4_positions_table = tbl([
    ['Position', 'Trigger', 'Guidance', 'Approval'],
    ['Preferred', 'Standard cap request', 'Cap supplier liability at fees paid in the preceding 12 months.', 'None'],
    ['Fallback', 'Counterparty insists on higher cap', 'Accept liability cap at 2x annual fees.', 'General counsel approval'],
    ['Prohibited', 'Uncapped liability demand', 'Never accept an uncapped liability position.', 'Not acceptable at any level'],
    ['Escalation', 'Carve-out requests', 'Carve-outs from the liability cap beyond data breaches require escalation.', 'General counsel approval'],
])

m4_playbook_final = (
    p('Rivell Negotiation Playbook', 'Title')
    + p('Scope of application', 'Heading1')
    + p('Master Services Agreements — customer paper and supplier paper, United States. '
        'Effective for agreements signed on or after 2026-01-01.', 'Normal')
    + m4_positions_table
    + p('Confidentiality', 'Heading1')
    + p('Preferred: confidentiality obligations survive termination of the agreement for '
        'three years and cover all business information exchanged between the parties.', 'Normal')
    + p('Prohibited: confidentiality limited to documents marked confidential only. '
        'Escalate to general counsel before agreeing.', 'Normal')
    + p('General guidance', 'Heading1')
    + p('The supplier requires confidentiality of shared business information while limiting '
        'exposure, and governing law questions are handled case by case.', 'Normal')
)

m4_clause_library_body = (
    p('Rivell Approved Clause Library', 'Title')
    + p('Limitation on Damages — Preferred (US)', 'Heading1')
    + p('The total liability of Supplier shall not exceed the fees paid in the preceding 12 months.',
        'Normal')
    + p('Limitation on Damages — Fallback (US)', 'Heading1')
    + p('The total liability of Supplier shall not exceed two times the annual fees payable under this Agreement.',
        'Normal')
    + p('Governing Law — Preferred (US)', 'Heading1')
    + p('This Agreement shall be governed by the laws of the State of Delaware.', 'Normal')
    + p('Governing Law — Preferred (UK)', 'Heading1')
    + p('This Agreement shall be governed by the laws of England and Wales.', 'Normal')
    + p('Confidentiality — Preferred', 'Heading1')
    + p('The supplier shall maintain the confidentiality of the discloser\'s business information '
        'for the duration of this agreement.', 'Normal')
)

m4_misdeclared_body = (
    p('Executed Master Services Agreement — Alpha Manufacturing Ltd and Rivell Technologies', 'Title')
    + p('This Agreement was entered into as of March 3, 2025 by and between Alpha Manufacturing Ltd '
        '("Customer") and Rivell Technologies Inc. ("Supplier").', 'Heading1')
    + p('The parties executed this Agreement on the date first written above following negotiation '
        'of the liability provisions. The final executed limitation of liability was two times the '
        'annual fees payable, reflecting the negotiated compromise between the parties.', 'Normal')
    + p('Signed: A. Executive (Alpha Manufacturing Ltd), R. Counsel (Rivell Technologies Inc.).', 'Normal')
)

if __name__ == '__main__':
    write_docx('m4-playbook.docx', m4_playbook_final)
    write_docx('m4-clause-library.docx', m4_clause_library_body)
    write_docx('m4-executed-precedent-style.docx', m4_misdeclared_body)
    print('wrote m4-playbook.docx, m4-clause-library.docx, m4-executed-precedent-style.docx')
