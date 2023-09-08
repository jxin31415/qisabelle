import json
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Extraction:
    thy_file: Path  # Path to theory file relative to "/afp/thys/", like "Valuation/Valuation1.thy".
    working_directory: Path  # Parent of thy_file, like "Valuation".
    lemma_statements: list[str]  # Lemma statements (like "lemma ...", "theorem ...")
    translations: list[tuple[str, str]]  # List of pairs: [proof_state, proof_step].
    # Translations are like:
    # ["", lemma_statement],
    # [proof_state, proof_step], any number of times,
    # ["proof ... No subgoals!", "done"/"qed"],
    # all that any number of times (as a flat list).
    # Though there seems to be a lot of noise, like definitions, comments, others.


def load_extractions(afp_extractions_dir: Path) -> list[Extraction]:
    return list(_load_extraction(p) for p in afp_extractions_dir.glob("**/*.json"))


def _load_extraction(afp_extraction_file: Path) -> Extraction:
    with open(afp_extraction_file) as f:
        o = json.load(f)
    assert isinstance(o, dict) and set(o.keys()) == {
        "file_name",
        "working_directory",
        "problem_names",
        "translations",
    }
    assert isinstance(o["file_name"], str)
    assert isinstance(o["working_directory"], str)
    assert isinstance(o["problem_names"], list)
    assert isinstance(o["translations"], list)

    assert o["file_name"].startswith("/home/qj213/afp-2021-10-22/thys/")
    thy_file = Path(o["file_name"].split("/thys/", maxsplit=1)[1])

    assert o["working_directory"].startswith("/home/qj213/afp-2021-10-22/thys/")
    work_dir = Path(o["file_name"].split("/thys/", maxsplit=1)[1])
    if work_dir.name.endswith(".thy"):
        work_dir = work_dir.parent
    assert work_dir == thy_file.parent, (work_dir, thy_file)

    lemma_statements = o["problem_names"]
    assert all(isinstance(s, str) for s in lemma_statements)
    for s in lemma_statements:
        assert s.startswith(
            (
                "lemma ",
                "theorem ",
                "lemmas ",
                "lemma\n",
                "theorem\n",
                "lemmas\n",
                'lemma"',
                "lemma[",  # [simp], [code abstract], [code_unfold]
                "lemma(in ",
                "lemmas[",
                "lemmas(in ",
                "lemmas_with ",
                "lemmas_with[",
                "lemma(*<*)[simp]:",
                "lemma%",
            )
        ), f"Unusual lemma statement: {s}"

    translations = list[tuple[str, str]]()
    for proof_state, proof_step in o["translations"]:
        assert isinstance(proof_state, str) and isinstance(proof_step, str)
        translations.append((proof_state, proof_step))

    return Extraction(
        thy_file=thy_file,
        working_directory=work_dir,
        lemma_statements=lemma_statements,
        translations=translations,
    )
