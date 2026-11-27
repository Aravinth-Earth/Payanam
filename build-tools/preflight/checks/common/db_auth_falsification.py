#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
"""The DB-auth contract's own falsification run: break the spec on purpose, expect the contract to fail.

A contract that cannot fail is decoration. Each mutation below removes or re-adds one thing the flow
guarantees; the contract must report at least one violation for every one of them, and must stay green
on the untouched spec. The mutations are the ones the PowerShell harness performed, now native so the
check runs wherever the preflight runs.
"""
import copy
import json
from pathlib import Path

from checks.common.db_auth_contract import SPEC, _check

NAME = "DB auth contract falsification"


def _mutations(spec: dict) -> list[tuple[str, dict]]:
    def mutate(label: str, change) -> tuple[str, dict]:
        candidate = copy.deepcopy(spec)
        change(candidate)
        return label, candidate

    def drop_plaintext_refusal(candidate: dict) -> None:
        candidate["transitions"] = [
            t for t in candidate["transitions"]
            if not (str(t.get("guard", "")).lower().startswith("plain") and str(t.get("to", "")).startswith("IMPORT_DB_"))
        ]

    def re_add_legacy_disable(candidate: dict) -> None:
        candidate["transitions"].append({"id": "FRC001", "from": "BIOSEC_DISABLE_FLOW", "to": "BIOSEC_DISABLE_VERIFY", "guard": "legacy"})

    def retarget_manual_entry(candidate: dict) -> None:
        for transition in candidate["transitions"]:
            if transition.get("id") == "TAUTH004":
                transition["to"] = "AUTH_AUTO_ENTRY"

    def drop_a_state(candidate: dict) -> None:
        protected = "AUTH_MANUAL_ENTRY"
        candidate["states"] = [s for s in candidate["states"] if str(s.get("id")) != protected]

    def plaintext_state(candidate: dict) -> None:
        candidate["states"].append({"id": "IMPORT_DB_PLAIN_SCREEN", "type": "screen"})

    return [
        mutate("plaintext refusal branch removed", drop_plaintext_refusal),
        mutate("legacy disable route re-added", re_add_legacy_disable),
        mutate("TAUTH004 retargeted away from manual entry", retarget_manual_entry),
        mutate("a state removed while transitions still reference it", drop_a_state),
        mutate("a plaintext import state re-added", plaintext_state),
    ]


def run(ctx) -> tuple[bool, str]:
    path = Path(ctx.root) / SPEC
    if not path.is_file():
        return False, f"DB flow spec not found: {SPEC}"
    spec = json.loads(path.read_text(encoding="utf-8"))
    if _check(spec):
        return False, "the untouched spec does not satisfy the contract - fix that first"
    survivors = [label for label, candidate in _mutations(spec) if not _check(candidate)]
    if survivors:
        return False, f"{len(survivors)} mutation(s) still reported green: " + "; ".join(survivors)
    return True, f"{len(_mutations(spec))} mutation(s) each produced a violation"
