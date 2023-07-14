import json
from pathlib import Path
from typing import TypedDict, cast

from .model import Model


def do_train(model: Model, afp_extractions_dir: Path) -> None:
    for p in afp_extractions_dir.glob("**/*.json"):
        with open(p) as f:
            o = cast(Extraction, json.load(f))
            assert isinstance(o, dict) and "translations" in o
            for proof_state, proof_step in o["translations"]:
                print("=" * 50, "\n", proof_state, "\n", "-" * 50, "\n", proof_step, "\n")
            return


class Extraction(TypedDict):
    file_name: str  # like "/home/qj213/afp-2021-10-22/thys/Valuation/Valuation1.thy"
    working_directory: str  # like "/home/qj213/afp-2021-10-22/thys/Valuation"
    problem_names: list[str]  # list of lemma_statements (strings like "lemma ...")

    # List of pairs:
    # ["", lemma_statement],
    # [proof_state, proof_step], any number of times,
    # ["proof ... No subgoals!", "done"/"qed"],
    # all that any number of times (as a flat list).
    # Though there seems to be a lot of noise, like definitions, comments, others.
    translations: list[list[str]]
