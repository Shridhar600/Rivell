#!/usr/bin/env python3
"""Walk-away absence fixtures: a playbook whose walk-away states an absent right and a contract
that does not grant it, with no termination-for-convenience right."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_knowledge_fixtures import p, write_docx  # noqa: E402


def tc(text):
    return f'<w:tc><w:p><w:r><w:t xml:space="preserve">{text}</w:t></w:r></w:p></w:tc>'


def tr(cells):
    return f'<w:tr>{"".join(tc(cell) for cell in cells)}</w:tr>'


def table(rows):
    return f'<w:tbl>{"".join(tr(row) for row in rows)}</w:tbl>'


playbook_body = (
    p('Helios Walk-Away Playbook', 'Title')
    + table([
        ['Topic', 'Preferred', 'Fallback', 'Prohibited'],
        ['Limitation of Liability',
         'The total liability of Supplier shall not exceed the fees paid in the '
         'preceding 12 months.',
         'The total liability of Supplier shall not exceed two times the fees paid '
         'in the preceding 12 months.',
         'Uncapped or unlimited liability is never acceptable'],
        ['Termination for convenience',
         'Either party may terminate for convenience on 30 days notice.',
         'Either party may terminate for convenience on 60 days notice.',
         'No termination for convenience'],
    ])
    + '<w:sectPr/>'
)

contract_body = (
    p('Master Services Agreement', 'Title')
    + p('This Master Services Agreement is entered into as of January 1, 2026 by and '
        'between Northwind Customer Group LLC ("Customer") and Pallas Cloud Services '
        'GmbH ("Supplier").')
    + p('1. Limitation of Liability', 'Heading1')
    + p('The liability of Supplier under this Agreement is uncapped and unlimited, '
        'however arising.')
    + p('2. Confidentiality', 'Heading1')
    + p("Each Party shall protect the other Party's confidential information with "
        'reasonable care.')
    + p('3. Termination', 'Heading1')
    + p('Either Party may terminate this Agreement for material breach on thirty (30) '
        "days' notice if the breach is not remedied.")
    + p('4. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of the State of Delaware.')
    + '<w:sectPr/>'
)

write_docx('walk-away-playbook.docx', playbook_body)
write_docx('walk-away-contract.docx', contract_body)
print('walk-away fixtures written')
