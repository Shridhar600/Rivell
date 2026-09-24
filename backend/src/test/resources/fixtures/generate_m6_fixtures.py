#!/usr/bin/env python3
"""Generate M6 retrieval fixture artifacts: policy playbooks, clause libraries,
and executed-precedent agreements for the labeled evidence corpus."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_knowledge_fixtures import p, write_docx  # noqa: E402


def agreement(title, counterparty_line, body_pairs):
    body = p(title, 'Title') + p(counterparty_line)
    for heading, text in body_pairs:
        body += p(heading, 'Heading1') + p(text)
    return body


# The applicable policy for the main corpus (MSA / Customer / Third-Party / US).
main_policy_body = (
    p('Helios MSA Negotiation Playbook', 'Title')
    + p('Limitation of Liability', 'Heading1')
    + p('Preferred: The total liability of Supplier shall not exceed the fees paid '
        'in the preceding 12 months.')
    + p('Prohibited: uncapped liability is not acceptable in any MSA.')
    + p('Escalate to general counsel before agreeing to any cap above the preferred position.')
    + p('Confidentiality', 'Heading1')
    + p('Confidentiality obligations must survive termination of the agreement.')
)

uk_policy_body = (
    p('Helios UK MSA Negotiation Playbook', 'Title')
    + p('Limitation of Liability', 'Heading1')
    + p('Preferred: The total liability of Supplier shall not exceed twice the fees '
        'paid in the preceding 12 months under United Kingdom law.')
)

nda_policy_body = (
    p('Helios NDA Negotiation Playbook', 'Title')
    + p('Limitation of Liability', 'Heading1')
    + p('Preferred: The total liability of Supplier shall not exceed the fees paid '
        'in the preceding 12 months for non-disclosure agreements.')
)

supplier_policy_body = (
    p('Helios Supplier-Side Negotiation Playbook', 'Title')
    + p('Limitation of Liability', 'Heading1')
    + p('Preferred: As Supplier, cap the total liability at the fees paid in the '
        'preceding 12 months and exclude consequential damages.')
)

future_policy_body = (
    p('Helios 2027 MSA Negotiation Playbook', 'Title')
    + p('Limitation of Liability', 'Heading1')
    + p('Preferred: The total liability of Supplier shall not exceed half the fees '
        'paid in the preceding 12 months.')
)

superseded_policy_body = (
    p('Helios MSA Negotiation Playbook 2025', 'Title')
    + p('Limitation of Liability', 'Heading1')
    + p('Preferred: The total liability of Supplier shall not exceed the fees paid '
        'in the preceding 12 months, evaluated per the 2025 policy standard.')
)

ambiguity_policy_body = (
    p('Helios MSA Negotiation Playbook (Procurement Addendum)', 'Title')
    + p('Limitation of Liability', 'Heading1')
    + p('Fallback: a liability cap of two times the fees paid in the preceding '
        '12 months applies for procurement engagements.')
)

library_body = (
    p('Helios Approved Clause Library', 'Title')
    + p('Limitation of Liability — Preferred', 'Heading1')
    + p('The total liability of Supplier shall not exceed the fees paid in the '
        'preceding 12 months.')
    + p('Limitation of Liability — Fallback', 'Heading1')
    + p('The total liability of Supplier shall not exceed two times the fees paid '
        'in the preceding 12 months.')
    + p('Confidentiality — Preferred', 'Heading1')
    + p('Each Party shall protect confidential information of the other Party with '
        'reasonable care.')
)

uk_library_body = (
    p('Helios Approved Clause Library (United Kingdom)', 'Title')
    + p('Limitation of Liability — Preferred', 'Heading1')
    + p('The total liability of Supplier shall not exceed the fees paid in the '
        'preceding 12 months under the law of England and Wales.')
)

precedent_relevant_body = agreement(
    'Master Services Agreement (Executed 2026-02-10)',
    'Between Helios Systems Inc. and Vertex Analytics Ltd., governed by the laws '
    'of the State of Delaware.',
    [
        ('Limitation of Liability',
         'The total liability of Supplier shall not exceed the fees paid in the '
         'preceding 12 months.'),
        ('Confidentiality',
         'Each Party shall protect confidential information with reasonable care.'),
    ])

precedent_lexical_body = agreement(
    'Services Agreement (Executed 2025-11-02)',
    'Between Helios Systems Inc. and Northwind Data Corp., governed by the laws '
    'of the State of New York.',
    [
        ('Limitation of Liability',
         'The total liability of Supplier shall not exceed the fees paid during '
         'the preceding 12 months, as measured at the time of the claim.'),
        ('Indemnification',
         'Supplier shall defend Customer against third-party intellectual '
         'property claims.'),
    ])

precedent_paraphrase_body = agreement(
    'Subscription Agreement (Executed 2026-01-15)',
    'Between Helios Systems Inc. and Cobalt Retail Group, governed by the laws '
    'of the State of Delaware.',
    [
        ('Liability',
         "Supplier's overall exposure for damages is capped at one year of the "
         'amounts invoiced to Customer.'),
        ('Confidentiality',
         'Confidential information must be safeguarded at all times.'),
    ])

precedent_incompatible_body = agreement(
    'Consulting Agreement (Executed 2026-03-20)',
    'Between Helios Systems Inc. and Orion Partners LLP, governed by the laws '
    'of the State of Delaware.',
    [
        ('Limitation of Liability',
         'The liability of Supplier under this Agreement is uncapped and '
         'unlimited, however arising.'),
        ('Governing Law',
         'This Agreement is governed by the laws of the State of Delaware.'),
    ])

precedent_governing_law_body = agreement(
    'Distribution Agreement (Executed 2025-06-30)',
    'Between Helios Systems Inc. and Meridian Supply Co., governed by the laws '
    'of the State of Delaware.',
    [
        ('Governing Law',
         'This Agreement is governed by the laws of the State of Delaware, and '
         'the parties submit to exclusive jurisdiction of its courts.'),
    ])

write_docx('m6-policy-playbook.docx', main_policy_body)
write_docx('m6-policy-uk-playbook.docx', uk_policy_body)
write_docx('m6-policy-nda-playbook.docx', nda_policy_body)
write_docx('m6-policy-supplier-playbook.docx', supplier_policy_body)
write_docx('m6-policy-future-playbook.docx', future_policy_body)
write_docx('m6-policy-superseded-playbook.docx', superseded_policy_body)
write_docx('m6-policy-ambiguity-playbook.docx', ambiguity_policy_body)
write_docx('m6-clause-library.docx', library_body)
write_docx('m6-clause-library-uk.docx', uk_library_body)
write_docx('m6-precedent-relevant.docx', precedent_relevant_body)
write_docx('m6-precedent-lexical.docx', precedent_lexical_body)
write_docx('m6-precedent-paraphrase.docx', precedent_paraphrase_body)
write_docx('m6-precedent-incompatible.docx', precedent_incompatible_body)
write_docx('m6-precedent-governing-law.docx', precedent_governing_law_body)
print('m6 retrieval fixtures written')
