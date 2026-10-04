#!/usr/bin/env python3
"""Generate the fictional demo documents used by the Home demo seed.

These are invented documents for local demonstration only. They contain no real
customer, counterparty or legal content, and they are committed as assets rather
than produced by a Flyway migration so no production schema ever carries sample
business data.

Run from backend/:  python3 src/main/resources/demo/generate_demo_documents.py
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(Path(__file__).resolve().parents[4]
                       / 'src/test/resources/fixtures'))
from generate_knowledge_fixtures import p, write_docx  # noqa: E402

OUT = HERE / 'documents'
OUT.mkdir(parents=True, exist_ok=True)


def contract(title, parties, sections, schedules=()):
    body = p(title, 'Title')
    for heading, clauses in sections:
        body += p(heading, 'Heading1')
        for clause in clauses:
            body += p(clause)
    if parties:
        body += p('Parties', 'Heading1')
        for party in parties:
            body += p(party)
    for heading, rows in schedules:
        body += p(heading, 'Heading1')
        body += ('<w:tbl><w:tr>'
                 '<w:tc><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc>'
                 '<w:tc><w:p><w:r><w:t>Detail</w:t></w:r></w:p></w:tc></w:tr>')
        for left, right in rows:
            body += ('<w:tr>'
                     f'<w:tc><w:p><w:r><w:t>{left}</w:t></w:r></w:p></w:tc>'
                     f'<w:tc><w:p><w:r><w:t>{right}</w:t></w:r></w:p></w:tc></w:tr>')
        body += '</w:tbl>'
    # The disclaimer goes last on purpose. A body paragraph placed before the first
    # heading becomes a content unit with no heading, and a table region becomes one with
    # no text at all. The semantic compiler used to dereference that absent text when a
    # text-less unit looked like an escalation position.
    body += p('Heading1' and 'Notice', 'Heading1')
    body += p('This is a fictional demonstration document. It is not a real agreement '
              'and creates no obligation.')
    return body


write_docx(str(OUT / 'Atlas Mutual NDA.docx'), contract(
    'Mutual Non-Disclosure Agreement',
    ['Disclosing Party: Atlas Analytics Ltd',
     'Receiving Party: Northstar Systems Inc'],
    [('Purpose', [
        'The parties wish to explore a potential commercial relationship and may '
        'disclose confidential information to each other for that limited purpose.',
    ]),
     ('Confidential Information', [
        'Confidential Information means non-public information disclosed by either '
        'party that is marked confidential or would reasonably be understood to be '
        'confidential.',
        'Confidential Information excludes information that is independently developed, '
        'publicly available through no breach, or already known without restriction.',
    ]),
     ('Term and Termination', [
        'This Agreement continues for two years from the Effective Date.',
        'Either party may terminate this Agreement on thirty days written notice.',
        'Confidentiality obligations survive termination for three years.',
    ]),
     ('Governing Law', [
        'This Agreement is governed by the laws of the State of New York.',
        'The parties submit to the exclusive jurisdiction of the courts of New York County.',
    ])]))

write_docx(str(OUT / 'Northstar Vendor MSA.docx'), contract(
    'Master Services Agreement',
    ['Customer: Northstar Systems Inc',
     'Supplier: Atlas Analytics Ltd'],
    [('Services', [
        'Supplier will provide the analytics services described in each Statement of Work.',
        'Each Statement of Work is incorporated into this Agreement by reference.',
    ]),
     ('Fees and Payment', [
        'Fees are stated in each Statement of Work and invoiced monthly in arrears.',
        'Undisputed invoices are payable within thirty days of receipt.',
    ]),
     ('Limitation of Liability', [
        'Supplier total aggregate liability arising out of or related to this Agreement '
        'shall not exceed 3 times the fees paid in the preceding 12 months.',
        'A party shall not be liable for indirect or consequential loss.',
        'This limitation applies to all obligations under this Agreement, including '
        'indemnification obligations.',
    ]),
     ('Indemnification', [
        'Supplier shall defend Customer against third-party claims without regard to any '
        'limitation of liability in this Agreement.',
    ]),
     ('Confidentiality', [
        'Each party will protect the other party confidential information with at least '
        'reasonable care and use it only to perform this Agreement.',
    ]),
     ('Termination', [
        'Either party may terminate for material breach that remains uncured thirty days '
        'after written notice.',
        'On termination each party returns or destroys the other party Confidential '
        'Information.',
    ]),
     ('Governing Law', [
        'This Agreement is governed by the laws of the State of Delaware.',
    ]),
     ('Survival', [
        'Limitation of Liability, Confidentiality and Governing Law survive termination.',
    ])]))

write_docx(str(OUT / 'Beacon Data Processing Addendum.docx'), contract(
    'Data Processing Addendum',
    ['Controller: Northstar Systems Inc',
     'Processor: Beacon Data Services LLC'],
    [('Scope', [
        'This Addendum governs processing of personal data by Processor on behalf of '
        'Controller in connection with the services.',
    ]),
     ('Processing Instructions', [
        'Processor will process personal data only on documented instructions from '
        'Controller.',
        'Processor will not sell personal data or use it for its own purposes.',
    ]),
     ('Security', [
        'Processor will maintain appropriate technical and organisational measures.',
        'Processor will notify Controller without undue delay on becoming aware of a '
        'personal data breach.',
    ]),
    ('Subprocessors', [
        'Processor may engage subprocessors with prior general written authorisation.',
        'Processor remains responsible for each subprocessor performance.',
    ]),
     ('Data Subject Rights', [
        'Processor will assist Controller in responding to data subject requests.',
    ]),
     ('Return and Deletion', [
        'On termination Processor will return or delete personal data within thirty days.',
    ]),
     ('Governing Law', [
        'This Addendum is governed by the laws of the State of New York.',
    ])]))

write_docx(str(OUT / 'Rivell Demo Playbook.docx'), contract(
    'Negotiation Playbook (Demonstration)',
    [],
    [('Confidentiality', [
        'Preferred: a confidentiality term of at least three years.',
        'Fallback: two years, extended only for trade secrets.',
        'Prohibited: no confidentiality obligation at all.',
    ]),
     ('Limitation of Liability', [
        'Preferred: total liability should not exceed the fees paid in the preceding 12 '
        'months.',
        'Fallback: a cap of 2 times the fees paid in the preceding 12 months is '
        'acceptable.',
        'Prohibited: uncapped liability is not acceptable in any master services '
        'agreement.',
        'Escalate to general counsel for any cap above the preferred position.',
    ]),
     ('Termination', [
        'Termination for convenience should require thirty days notice.',
        'Termination for material breach should allow a thirty day cure period.',
    ]),
     ('Governing Law', [
        'New York or Delaware governing law is acceptable.',
        'Exclusive jurisdiction in the governing law state is preferred.',
    ])],
    ))

write_docx(str(OUT / 'Rivell Demo Clause Library.docx'), contract(
    'Clause Library (Demonstration)',
    [],
    [('Limitation of Liability - Preferred', [
        'Total aggregate liability shall not exceed the fees paid in the preceding 12 '
        'months.',
    ]),
     ('Limitation of Liability - Fallback', [
        'Total aggregate liability shall not exceed two times the fees paid in the '
        'preceding 12 months.',
    ])]))

print(f'wrote {len(list(OUT.glob("*.docx")))} demo documents to {OUT}')
