#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The canonical DB-auth flow keeps the rules the app's security depends on.

The spec (`docs/db/db-flow-boot-entry-flows.json`) is the flow's source of truth, so a rule removed
there silently un-specifies behaviour the code still implements. The rules below are the ones the
Android gate enforced; they move here unchanged now that both platforms run them.

Two failure modes get their own guard, because both once read green while covering nothing: a
collection that is absent (iterating @($null) is ONE element in PowerShell, zero here) and a rule that
loops over zero edges.
"""
import json
from pathlib import Path

NAME = "DB auth flow contract"
SPEC = "docs/db/db-flow-boot-entry-flows.json"
PLAINTEXT_TOKENS = "plain|unencrypt|cleartext"
REFUSAL_STATES = ("IMPORT_DB_UNUSABLE", "IMPORT_DB_BAD")
ENCRYPTED_VERIFIED = ("IMPORT_ENCRYPTED_DETECTED", "IMPORT_DB_ENC_SCREEN")
DOORS = ("IMPORT_FORMAT_DETECT", "IMPORT_DB_FORMAT")
SINKS = (
    ("IMPORT_DB_HANDOFF", "IMPORT_DB_ENC_PASS"),
    ("IMPORT_PREP_BOOT_STATE", "IMPORT_COPY_ENCRYPTED_RESULT"),
    ("IMPORT_DB_ENC_PASS", "IMPORT_DB_ENC_SCREEN"),
)


def _check(spec: dict) -> list[str]:
    import re

    violations: list[str] = []
    transitions = spec.get("transitions") or []
    states = [state for state in (spec.get("states") or []) if state is not None]
    state_ids = [str(state.get("id")) for state in states]
    by_id = {str(transition.get("id")): transition for transition in transitions if transition.get("id") is not None}

    if not state_ids:
        violations.append("spec exposes no states: every node-level rule would silently cover nothing")

    def require(transition_id: str) -> dict | None:
        if transition_id not in by_id:
            violations.append(f"missing transition {transition_id}")
            return None
        return by_id[transition_id]

    transition = require("TAUTH004")
    if transition:
        if str(transition.get("to")) != "AUTH_MANUAL_ENTRY":
            violations.append("TAUTH004 must route to AUTH_MANUAL_ENTRY")
        if "biometric not enabled" not in str(transition.get("guard", "")).lower():
            violations.append("TAUTH004 guard must explicitly require manual entry when biometric is not enabled")
    transition = require("TAUTH027")
    if transition and "manual passphrase unlock" not in str(transition.get("guard", "")).lower():
        violations.append("TAUTH027 guard must require manual passphrase unlock for biometric offer eligibility")
    transition = require("TBIOSEC005")
    if transition and "setUserAuthenticationRequired=true" not in str(transition.get("action", "")):
        violations.append("TBIOSEC005 action must reference biometric-required Keystore wrapping")
    transition = require("TBIOSEC013")
    if transition and str(transition.get("to")) != "BIOSEC_DELETE_KEY":
        violations.append("TBIOSEC013 must route directly to BIOSEC_DELETE_KEY (no disable passphrase prompt)")

    if any(str(t.get("from")) == "BIOSEC_DISABLE_FLOW" and str(t.get("to")) == "BIOSEC_DISABLE_VERIFY" for t in transitions):
        violations.append("legacy disable verification route BIOSEC_DISABLE_FLOW -> BIOSEC_DISABLE_VERIFY must not exist")
    if "BIOSEC_DISABLE_VERIFY" in state_ids:
        violations.append("legacy node BIOSEC_DISABLE_VERIFY must not exist in the canonical flow")

    plain = re.compile(PLAINTEXT_TOKENS, re.IGNORECASE)
    for door in DOORS:
        if door not in state_ids:
            violations.append(f"import door {door} is missing from the spec; the classification step cannot be checked")
    for state in states:
        state_id = str(state.get("id"))
        if re.search(r"^IMPORT_.*(DETECT|FORMAT)$", state_id) and str(state.get("type")) == "decision" and state_id not in DOORS:
            violations.append(f"state {state_id} looks like an import classification door but is not declared in this contract")
    for state in states:
        state_id = str(state.get("id"))
        if state_id.startswith("IMPORT_") and plain.search(state_id):
            violations.append(f"state {state_id} is a plaintext import state; only the encrypted database may be imported")

    for transition in transitions:
        target = str(transition.get("to"))
        if target.startswith("IMPORT_") and plain.search(str(transition.get("guard", ""))):
            if target not in REFUSAL_STATES:
                violations.append(
                    f"transition {transition.get('id')} routes a plaintext-guarded step to {target}; "
                    f"it must refuse (allowed: {', '.join(REFUSAL_STATES)})"
                )

    for door in DOORS:
        if door not in state_ids:
            continue
        outbound = [t for t in transitions if str(t.get("from")) == door]
        for transition in outbound:
            target = str(transition.get("to"))
            if target not in ENCRYPTED_VERIFIED and target not in REFUSAL_STATES:
                violations.append(
                    f"import door {door} branches to {target} via {transition.get('id')}; a door may branch only to "
                    f"an encrypted-verified state ({', '.join(ENCRYPTED_VERIFIED)}) or a refusal ({', '.join(REFUSAL_STATES)})"
                )
        if not any(plain.search(str(t.get("guard", ""))) and str(t.get("to")) in REFUSAL_STATES for t in outbound):
            violations.append(f"import door {door} has no plaintext refusal branch")

    for sink, source in SINKS:
        inbound = [t for t in transitions if str(t.get("to")) == sink]
        if not inbound:
            violations.append(f"import sink {sink} has no inbound transition at all")
            continue
        for transition in inbound:
            if str(transition.get("from")) != source:
                violations.append(f"import sink {sink} is reachable from {transition.get('from')} via {transition.get('id')}; only {source} may reach it")

    for transition in transitions:
        for endpoint in (str(transition.get("from")), str(transition.get("to"))):
            if endpoint not in state_ids:
                violations.append(f"transition {transition.get('id')} references state '{endpoint}', which does not exist")

    for refusal in REFUSAL_STATES:
        if refusal in state_ids and not any(str(t.get("from")) == refusal for t in transitions):
            violations.append(f"refusal state {refusal} has no outbound transition; a user refused there could not leave")
    return violations


def run(ctx) -> tuple[bool, str]:
    path = Path(ctx.root) / SPEC
    if not path.is_file():
        return False, f"DB flow spec not found: {SPEC}"
    try:
        spec = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return False, f"could not parse {SPEC}: {error}"
    violations = _check(spec)
    if violations:
        return False, f"{len(violations)} issue(s): " + " | ".join(violations[:6])
    return True, f"{len(spec.get('states') or [])} states, {len(spec.get('transitions') or [])} transitions in step"
