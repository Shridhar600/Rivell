"""Independent redline checks (stdlib only; shares no code with the backend renderer/verifier).

Reads the original DOCX and the tracked-change DOCX as plain OOXML and checks:
  opens      the redline is a readable DOCX with a body
  preserved  "reject all" view equals the original, element by element
  local      every changed original element lies in a clause a REPLACE/DELETE change targets;
             inserted elements outside those clauses only when an INSERT change exists
  applied    "accept all" view contains each approved text; REPLACE/DELETE leave no old body text
             (for a run-in heading, the clause text in the heading paragraph counts as body)

Body elements are indexed like the backend's sourceSpan.bodyIndex: the children of w:body
except the final w:sectPr. Clause regions come from the frozen structure nodes: a heading's
region runs to the next top-level HEADING or TITLE.
"""
import difflib
import io
import re
import zipfile
import xml.etree.ElementTree as ET

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
INSERTED = {W + "ins", W + "moveTo"}
RUN_IN_WORDS = 15  # a heading paragraph longer than this also carries clause text ("5. Term. This…")
DELETED = {W + "del", W + "moveFrom"}


def body_elements(docx_bytes):
    with zipfile.ZipFile(io.BytesIO(docx_bytes)) as z:
        root = ET.fromstring(z.read("word/document.xml"))
    body = root.find(W + "body")
    if body is None:
        raise ValueError("document has no body")
    return [el for el in body if el.tag != W + "sectPr"]


def view_text(element, view):
    """Text of one body element in the 'orig' (reject all) or 'new' (accept all) view."""
    out = []

    def walk(node, in_ins, in_del):
        tag = node.tag
        if tag == W + "rPr" or tag == W + "pPr":
            return  # property changes and paragraph-mark revisions carry no text
        in_ins = in_ins or tag in INSERTED
        in_del = in_del or tag in DELETED
        visible = not in_ins if view == "orig" else not in_del
        if tag in (W + "t", W + "delText") and visible:
            out.append(node.text or "")
        elif tag == W + "tab" and visible:
            out.append("\t")
        elif tag in (W + "br", W + "cr") and visible:
            out.append("\n")
        for child in node:
            walk(child, in_ins, in_del)
        if tag == W + "p":
            out.append("\n")

    walk(element, False, False)
    return "".join(out)


def _mark(element, kind):
    ppr = element.find(W + "pPr")
    rpr = ppr.find(W + "rPr") if ppr is not None else None
    return rpr is not None and rpr.find(W + kind) is not None


def has_change(element):
    return any(n.tag in INSERTED or n.tag in DELETED for n in element.iter()) or \
        (element.tag == W + "p" and (_mark(element, "ins") or _mark(element, "del")))


def is_inserted(element):
    """A whole paragraph the redline added: its mark is inserted and it has no original text."""
    return element.tag == W + "p" and _mark(element, "ins") and view_text(element, "orig").strip() == ""


def deleted_fragments(docx_bytes, min_chars=20):
    """Deleted text runs long enough to recognise in a parse of the redline."""
    frags = []
    for el in body_elements(docx_bytes):
        for node in el.iter():
            if node.tag in DELETED:
                text = norm("".join(t.text or "" for t in node.iter(W + "delText")))
                if len(text) >= min_chars:
                    frags.append(text)
    return frags


def norm(text):
    return re.sub(r"\s+", " ", text or "").strip()


def clause_regions(nodes):
    """heading node id -> set of body indexes in its region (heading included)."""
    top = sorted((n for n in nodes if n.get("sourceSpan") and n["nodeType"] not in ("BODY",)
                  and n["sourceSpan"].get("bodyIndex") is not None),
                 key=lambda n: (n["sourceSpan"]["bodyIndex"], n.get("ordinal") or 0))
    starts = sorted({n["sourceSpan"]["bodyIndex"] for n in top if n["nodeType"] in ("HEADING", "TITLE")})
    last = max((n["sourceSpan"]["bodyIndex"] for n in top), default=-1)
    regions = {}
    for n in top:
        if n["nodeType"] != "HEADING" or n["id"] in regions:
            continue
        start = n["sourceSpan"]["bodyIndex"]
        nxt = next((s for s in starts if s > start), last + 1)
        regions[n["id"]] = set(range(start, nxt))
    return regions


def check(original_bytes, redline_bytes, nodes, changes):
    """changes: approved changes [{actionType, targetHeadingNodeId, approvedLanguage}]."""
    result = {"opens": False, "preserved": False, "local": False, "applied": False, "problems": []}
    problems = result["problems"]
    try:
        original = body_elements(original_bytes)
        redline = body_elements(redline_bytes)
    except (zipfile.BadZipFile, KeyError, ET.ParseError, ValueError) as e:
        problems.append(f"opens: {e}")
        return result
    result["opens"] = True

    # Align redline elements to original body indexes; inserted paragraphs take none.
    aligned, inserted_after = [], []
    index = -1
    for el in redline:
        if is_inserted(el):
            inserted_after.append(index)
        else:
            index += 1
            aligned.append((index, el))

    orig_texts = [view_text(el, "orig") for el in original]
    reject_texts = [view_text(el, "orig") for _, el in aligned]
    if reject_texts == orig_texts:
        result["preserved"] = True
    else:
        diff = difflib.SequenceMatcher(a=orig_texts, b=reject_texts, autojunk=False)
        for op, a1, a2, b1, b2 in diff.get_opcodes():
            if op != "equal":
                problems.append(f"preserved: {op} original[{a1}:{a2}] vs reject-all[{b1}:{b2}] "
                                f"{norm(' | '.join(orig_texts[a1:a2]))[:80]!r} -> "
                                f"{norm(' | '.join(reject_texts[b1:b2]))[:80]!r}")
                if len(problems) > 5:
                    break

    regions = clause_regions(nodes)
    targeted = set()
    for c in changes:
        if c["actionType"] in ("REPLACE", "DELETE"):
            region = regions.get(c.get("targetHeadingNodeId"))
            if region is None:
                problems.append(f"local: change {c.get('ordinal')} targets no known clause region")
            else:
                targeted |= region
    has_insert = any(c["actionType"] == "INSERT" for c in changes)
    local = True
    for i, el in aligned:
        if has_change(el) and i not in targeted:
            local = False
            problems.append(f"local: element {i} changed outside targeted clauses: "
                            f"{norm(view_text(el, 'orig'))[:80]!r}")
    stray = [a for a in inserted_after if a not in targeted and not has_insert]
    if stray:
        local = False
        problems.append(f"local: {len(stray)} inserted paragraph(s) outside targeted clauses "
                        f"with no INSERT change (after elements {sorted(set(stray))[:5]})")
    result["local"] = local

    accept_all = norm(" ".join(view_text(el, "new") for el in redline))
    applied = True
    for c in changes:
        language = norm(c.get("approvedLanguage"))
        if c["actionType"] in ("REPLACE", "INSERT") and language and language not in accept_all:
            applied = False
            problems.append(f"applied: change {c.get('ordinal')} text not in accept-all view: {language[:60]!r}")
        if c["actionType"] in ("REPLACE", "DELETE"):
            region = sorted(regions.get(c.get("targetHeadingNodeId")) or [])
            body = region
            if c["actionType"] == "REPLACE" and region and len(
                    norm(view_text(original[region[0]], "orig")).split()) <= RUN_IN_WORDS:
                body = region[1:]  # a plain heading stays; a run-in heading holds clause text
            def survives(i):
                # a kept run-in heading may keep its heading words, not its clause text
                limit = RUN_IN_WORDS if c["actionType"] == "REPLACE" and i == region[0] else 0
                return any(j == i and len(norm(view_text(el, "new")).split()) > limit for j, el in aligned)
            left = [i for i in body if i < len(original) and norm(view_text(original[i], "orig"))
                    and survives(i)]
            if left:
                applied = False
                problems.append(f"applied: change {c.get('ordinal')} left old text in elements {left[:5]}")
    result["applied"] = applied
    return result
