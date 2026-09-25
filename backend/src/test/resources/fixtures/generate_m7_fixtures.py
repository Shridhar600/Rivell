#!/usr/bin/env python3
"""Generate M7 review fixtures: minimal MSA variants for bounded liability checks."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_knowledge_fixtures import p, write_docx  # noqa: E402


def msa(title, liability_text, confidentiality_survival=True):
    body = p(title, 'Title')
    body += p('This Master Services Agreement is entered into as of January 1, 2026 '
              'by and between Northwind Customer Group LLC ("Customer") and Pallas '
              'Cloud Services GmbH ("Supplier").', 'Normal')
    body += p('1. Limitation of Liability', 'Heading1')
    body += p(liability_text, 'Normal')
    body += p('2. Confidentiality', 'Heading1')
    body += (p('2.1 Each Party shall protect the other Party\'s confidential '
               'information with reasonable care.', 'Normal')
             + p('2.2 The obligations in this Section 2 survive termination of this '
                 'Agreement for three (3) years.', 'Normal')
             if confidentiality_survival else
             p('2.1 Each Party shall protect the other Party\'s confidential '
               'information with reasonable care.', 'Normal'))
    body += p('3. Termination', 'Heading1')
    body += p('3.1 Either Party may terminate this Agreement for convenience upon '
              'sixty (60) days\' prior written notice to the other Party.', 'Normal')
    body += p('4. Governing Law', 'Heading1')
    body += p('4.1 This Agreement is governed by the laws of the State of Delaware.',
              'Normal')
    return body


write_docx('m7-msa-preferred.docx', msa(
    'Master Services Agreement',
    'Supplier\'s total aggregate liability arising out of or related to this '
    'Agreement shall not exceed an amount equal to the fees paid in the twelve (12) '
    'months immediately preceding the event giving rise to the claim.'))

write_docx('m7-msa-fallback.docx', msa(
    'Master Services Agreement',
    'Supplier\'s total aggregate liability arising out of or related to this '
    'Agreement shall not exceed two times the fees paid in the preceding 12 months.'))

write_docx('m7-msa-uncapped.docx', msa(
    'Master Services Agreement',
    'The liability of Supplier under this Agreement is uncapped and unlimited, '
    'however arising.'))


def cross_clause(title, liability_text, confidentiality_text, indemnification_text):
    body = p(title, 'Title')
    body += p('This Master Services Agreement is entered into as of January 1, 2026 '
              'by and between Northwind Customer Group LLC ("Customer") and Pallas '
              'Cloud Services GmbH ("Supplier").', 'Normal')
    body += p('1. Limitation of Liability', 'Heading1')
    body += p(liability_text, 'Normal')
    body += p('2. Indemnification', 'Heading1')
    body += p(indemnification_text, 'Normal')
    body += p('3. Confidentiality', 'Heading1')
    body += p(confidentiality_text, 'Normal')
    body += p('4. Termination', 'Heading1')
    body += p('4.1 Either Party may terminate this Agreement for convenience upon '
              'sixty (60) days\' prior written notice to the other Party.', 'Normal')
    body += p('5. Governing Law', 'Heading1')
    body += p('5.1 This Agreement is governed by the laws of the State of Delaware.',
              'Normal')
    return body


write_docx('m7-msa-survival-gap.docx', cross_clause(
    'Master Services Agreement',
    'Supplier\'s aggregate liability shall not exceed two times the fees paid in '
    'the preceding 12 months.',
    'Each Party shall protect the other Party\'s confidential information with '
    'reasonable care and use it only for the purposes of this Agreement.',
    'Supplier shall defend Customer against third-party intellectual property '
    'claims arising from the Services, subject to the limitations in Section 1.'))

write_docx('m7-msa-carveout-conflict.docx', cross_clause(
    'Master Services Agreement',
    'Supplier\'s aggregate liability shall not exceed two times the fees paid in '
    'the preceding 12 months, and this limitation applies to all obligations under '
    'this Agreement, including indemnification obligations under Section 2.',
    'Each Party shall protect the other Party\'s confidential information with '
    'reasonable care. The obligations in this Section 3 survive termination of this '
    'Agreement for three (3) years.',
    'Supplier shall indemnify and defend Customer against third-party claims '
    'without regard to any limitation of liability in this Agreement.'))

write_docx('m7-msa-carveout-consistent.docx', cross_clause(
    'Master Services Agreement',
    'Supplier\'s aggregate liability shall not exceed two times the fees paid in '
    'the preceding 12 months, and the limitations in this Section 1 do not apply to '
    'indemnification obligations under Section 2.',
    'Each Party shall protect the other Party\'s confidential information with '
    'reasonable care. The obligations in this Section 3 survive termination of this '
    'Agreement for three (3) years.',
    'Supplier shall indemnify and defend Customer against third-party claims '
    'without regard to any limitation of liability in this Agreement.'))

write_docx('m7-msa-exotic.docx', msa(
    'Master Services Agreement',
    'Supplier\'s aggregate liability shall not exceed the insurance proceeds '
    'actually recovered by Supplier in respect of the claim.'))

print('m7 review fixtures written')
