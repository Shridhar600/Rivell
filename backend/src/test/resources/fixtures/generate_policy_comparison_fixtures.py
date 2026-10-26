#!/usr/bin/env python3
"""Policy-comparison review fixtures: a multi-topic playbook table, a clause
library with one fallback variant, and a contract with matched/unmatched clauses."""
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
    p('Helios General Negotiation Playbook', 'Title')
    + table([
        ['Topic', 'Preferred', 'Fallback', 'Prohibited'],
        ['Limitation of Liability',
         'The total liability of Supplier shall not exceed the fees paid in the '
         'preceding 12 months.',
         'The total liability of Supplier shall not exceed two times the fees paid '
         'in the preceding 12 months.',
         'Uncapped or unlimited liability is never acceptable.'],
        ['Cap Exceptions',
         'The liability cap does not apply to fraud or willful misconduct.',
         'The liability cap may apply to third-party claims.',
         ''],
        ['Confidentiality',
         'Confidentiality obligations survive termination for three years.',
         'Confidentiality obligations survive termination for one year.',
         ''],
        ['Termination',
         'Either party may terminate for convenience on 30 days notice.',
         'Either party may terminate for convenience on 60 days notice.',
         ''],
    ])
    + '<w:sectPr/>'
)

library_body = (
    p('Helios Approved Clause Library', 'Title')
    + p('Limitation of Liability — Fallback', 'Heading1')
    + p('The total liability of Supplier shall not exceed two times the fees paid '
        'in the preceding 12 months.')
    + p('Confidentiality — Preferred', 'Heading1')
    + p('Each Party shall protect confidential information of the other Party with '
        'reasonable care.')
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
    + p('2. Aggregate Cap', 'Heading1')
    + p("Supplier's total liability shall not exceed two times the fees paid in the "
        'preceding 12 months.')
    + p('3. Confidentiality', 'Heading1')
    + p("Each Party shall protect the other Party's confidential information with "
        'reasonable care. The obligations in this Section survive termination for '
        'three (3) years.')
    + p('4. Termination', 'Heading1')
    + p('Either Party may terminate this Agreement for convenience upon thirty (30) '
        "days' prior written notice to the other Party.")
    + p('5. Governing Law', 'Heading1')
    + p('This Agreement is governed by the laws of the State of Delaware.')
    + '<w:sectPr/>'
)

# A clause whose main subject is termination but which also speaks to liability: the
# liability position matches only a topic the clause touches, so its wording is never offered.
related_topic_contract_body = (
    p('Master Services Agreement', 'Title')
    + p('This Master Services Agreement is entered into as of January 1, 2026 by and '
        'between Northwind Customer Group LLC ("Customer") and Pallas Cloud Services '
        'GmbH ("Supplier").')
    + p('1. Termination', 'Heading1')
    + p('Either Party may terminate this Agreement for convenience upon thirty (30) '
        "days' prior written notice to the other Party. The liability of Supplier under "
        'this Agreement for breach of this Section is uncapped and unlimited.')
    + '<w:sectPr/>'
)

write_docx('pc-playbook.docx', playbook_body)
write_docx('pc-clause-library.docx', library_body)
write_docx('pc-contract.docx', contract_body)
write_docx('pc-related-topic-contract.docx', related_topic_contract_body)
print('policy comparison fixtures written')
