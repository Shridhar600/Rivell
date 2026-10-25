#!/usr/bin/env python3
"""Generate the fictional demo scenario documents used by the local demo seed.

These are invented documents for local demonstration only. They contain no real
customer, counterparty or legal content, and they are committed as assets rather
than produced by a Flyway migration so no production schema ever carries sample
business data.

Writing is byte-deterministic: every zip member is rewritten with a fixed
timestamp, so regenerating identical content produces identical bytes. That is
what stops a contributor who reruns the generator from seeing every document
reported as modified.

Run from backend/:  python3 src/main/resources/demo/generate_demo_documents.py
"""
import shutil
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
FIXTURES = Path(__file__).resolve().parents[4] / 'src/test/resources/fixtures'
sys.path.insert(0, str(FIXTURES))
from generate_knowledge_fixtures import p, write_docx  # noqa: E402

OUT = HERE / 'documents'
OUT.mkdir(parents=True, exist_ok=True)

FIXED_TIMESTAMP = (2026, 1, 1, 0, 0, 0)


def normalize_docx(path):
    temporary = path.with_name(path.name + '.tmp')
    with zipfile.ZipFile(path, 'r') as source:
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as target:
            for name in sorted(source.namelist()):
                data = source.read(name)
                info = zipfile.ZipInfo(name, date_time=FIXED_TIMESTAMP)
                info.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(info, data)
    temporary.replace(path)


def demo_docx(name, body):
    write_docx(str(OUT / name), body)
    normalize_docx(OUT / name)


def demo_copy(name, source):
    shutil.copyfile(FIXTURES / source, OUT / name)
    normalize_docx(OUT / name)


def contract(title, sections, parties=()):
    body = p(title, 'Title')
    for heading, clauses in sections:
        body += p(heading, 'Heading1')
        for clause in clauses:
            body += p(clause)
    if parties:
        body += p('Parties', 'Heading1')
        for party in parties:
            body += p(party)
    # The disclaimer goes last on purpose. A body paragraph placed before the first
    # heading becomes a content unit with no heading, and a table region becomes one
    # with no text at all. The semantic compiler used to dereference that absent text
    # when a text-less unit looked like an escalation position.
    body += p('Notice', 'Heading1')
    body += p('This is a fictional demonstration document. It is not a real agreement '
              'and creates no obligation.')
    return body


def nda_body(title):
    return contract(title, [
        ('Purpose', [
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
        ]),
        ('Governing Law', [
            'This Agreement is governed by the laws of the State of New York.',
            'The parties submit to the exclusive jurisdiction of the courts of New York County.',
        ]),
    ], [
        'Disclosing Party: Northwind Analytics Ltd',
        'Receiving Party: Contoso Systems Inc',
    ])


def msa_body(title):
    return contract(title, [
        ('Services', [
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
    ])


def playbook_sections(fallback_cap='2 times'):
    return [
        ('Confidentiality', [
            'Preferred: a confidentiality term of at least three years.',
            'Fallback: two years, extended only for trade secrets.',
            'Prohibited: no confidentiality obligation at all.',
        ]),
        ('Limitation of Liability', [
            'Preferred: total liability should not exceed the fees paid in the preceding 12 '
            'months.',
            f'Fallback: a cap of {fallback_cap} the fees paid in the preceding 12 months is '
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
        ]),
    ]


def playbook_body(title, fallback_cap='2 times'):
    return contract(title, playbook_sections(fallback_cap))


def routine_playbook_body(title):
    # Two guidance units that a reviewer can clear as routine. Each is recognised by the
    # scripted provider as one confident legal concept and expresses no negotiating
    # position: no preferred/fallback/prohibited/escalation/commentary language, no
    # approval gate and no scope narrowing. Under the real categorizer that leaves the
    # review reasons empty, so the candidate is ROUTINE and genuinely bulk-approvable.
    # Nothing here is a state insert - the eligibility is whatever the domain decides.
    return contract(title, [
        ('Confidentiality', [
            'The receiving party must protect the disclosing party confidential '
            'information using the same degree of care it applies to its own.',
        ]),
        ('Indemnification', [
            'The supplier must indemnify the customer against third-party claims '
            'arising from the supplier breach of its obligations.',
        ]),
    ])


def draft_playbook_body(title):
    # Adds one unit that openly contradicts published policy: the published playbook caps
    # liability, this draft proposes uncapped exposure. The contradiction is detected by
    # comparing the real texts, so the candidate earns a genuine conflict signal instead of
    # a fabricated one. It stays unpublished, like the rest of the draft.
    sections = playbook_sections() + [
        ('Liability - Draft Revision', [
            'Preferred: total liability is uncapped and the supplier accepts unlimited '
            'exposure for this engagement.',
        ]),
    ]
    return contract(title, sections)


def clause_library_body(title):
    return contract(title, [
        ('Limitation of Liability - Preferred', [
            'Total aggregate liability shall not exceed the fees paid in the preceding 12 '
            'months.',
        ]),
        ('Limitation of Liability - Fallback', [
            'Total aggregate liability shall not exceed two times the fees paid in the '
            'preceding 12 months.',
        ]),
    ])


def sparse_body(title):
    body = p(title, 'Title')
    body += p('', 'Normal')
    body += p('Sparse Section', 'Heading1')
    body += ('<w:tbl><w:tblGrid><w:gridCol/><w:gridCol/></w:tblGrid>'
             '<w:tr><w:tc><w:p><w:r><w:t>Key</w:t></w:r></w:p></w:tc>'
             '<w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr>'
             '<w:tr><w:tc><w:p><w:r><w:t>Alpha</w:t></w:r></w:p></w:tc>'
             '<w:tc><w:p><w:r><w:t>One</w:t></w:r></w:p></w:tc></w:tr></w:tbl>')
    body += '<w:sectPr/>'
    return body


SCENARIO_CONTRACTS = [
    ('Vendor NDA - Ready for review.docx', nda_body,
     'Mutual Non-Disclosure Agreement - Vendor Review'),
    ('Acme MSA - Review in progress.docx', msa_body,
     'Master Services Agreement - Acme Review'),
    ('Hooli MSA - Escalation blocking.docx', msa_body,
     'Master Services Agreement - Hooli Review'),
    ('Procurement NDA - Ready to finalize.docx', nda_body,
     'Mutual Non-Disclosure Agreement - Procurement'),
    ('Atlas MSA - Finalized.docx', msa_body,
     'Master Services Agreement - Atlas'),
    ('Employee NDA - Finalized, no changes.docx', nda_body,
     'Mutual Non-Disclosure Agreement - Employee'),
    ('Procurement MSA - Verified redline.docx', msa_body,
     'Master Services Agreement - Procurement'),
    ('Umbrella MSA - Analysis failed.docx', msa_body,
     'Master Services Agreement - Umbrella'),
    ('Soylent MSA - Limited analysis.docx', msa_body,
     'Master Services Agreement - Soylent'),
    ('Vandelay MSA - Waiting to start.docx', msa_body,
     'Master Services Agreement - Vandelay'),
]

for filename, body_builder, title in SCENARIO_CONTRACTS:
    demo_docx(filename, body_builder(title))

# The two retry scenarios need a body whose only Findings are absence Findings, so
# resolving them produces INSERT changes: the only change type the semantics-drift guard
# inspects. The existing M5 fixture is exactly that shape and is reused byte-for-byte.
demo_copy('Globex NDA - Redline failed.docx', 'm5-nda-counterparty.docx')
demo_copy('Initech NDA - Retry succeeded.docx', 'm5-nda-counterparty.docx')

demo_docx('Minimal Sparse - Empty structure.docx', sparse_body('Minimal Sparse Structure'))
(OUT / 'Corrupt Upload - Parse failed.docx').write_bytes(
    b'This is not a DOCX package. It is deliberately unreadable.')

demo_docx('Rivell Demo Playbook.docx',
          playbook_body('Negotiation Playbook (Demonstration)'))
demo_docx('Rivell Demo Playbook v2.docx',
          playbook_body('Negotiation Playbook (Demonstration) v2', fallback_cap='2.5 times'))
demo_docx('Rivell Demo Clause Library.docx',
          clause_library_body('Clause Library (Demonstration)'))
demo_docx('Rivell Demo Draft Playbook.docx',
          draft_playbook_body('Negotiation Playbook (Draft)'))
demo_docx('Rivell Demo Routine Playbook.docx',
          routine_playbook_body('Negotiation Playbook (Routine, Demonstration)'))
(OUT / 'Unreadable Playbook.docx').write_bytes(
    b'This is not a DOCX package. It is deliberately unreadable.')

print(f'wrote {len(list(OUT.glob("*.docx")))} demo documents to {OUT}')
