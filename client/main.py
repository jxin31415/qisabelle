import time
from collections import defaultdict
from pathlib import Path

from .extractions import load_extractions
from .model import DummyGTModel, DummyHammerModel, Model  # noqa: F401
from .proxy import QIsabelleProxy, get_exception_kind
from .test_cases import TestCase, load_quick_test_cases

ROOT_DIR = Path("/home/mwrochna/projects/play/")
AFP_DIR = ROOT_DIR / "afp-2023-03-16"
PISA_TEST_DIR = ROOT_DIR / "Portal-to-ISAbelle" / "universal_test_theorems"


def main() -> None:
    if False:
        test_qisabelle_basics()
        return

    if False:
        test_extractions(ROOT_DIR / "afp_extractions", AFP_DIR)
        return

    print("Loading tests from", PISA_TEST_DIR)
    tests = load_quick_test_cases(PISA_TEST_DIR)
    print(f"Loaded {len(tests)} tests.")

    for test in tests:
        thy_file = AFP_DIR / "thys" / test.thy_file
        if not thy_file.exists():
            print(f"Warning ({test.name}): no such theory file: {thy_file}")

    evaluate_model(DummyHammerModel(), tests, server_afp_dir=Path("/afp/"))


def test_qisabelle_basics() -> None:
    p = Path("/afp/thys/Real_Impl/Real_Impl_Auxiliary.thy")
    with QIsabelleProxy(theory_path=p) as proxy:
        # Load all the theory until the end, without ending it, save as "state0".
        print(header("load theory"))
        proof_done, proof_goals = proxy.load_theory(p, "", False, "state0")
        assert proof_done and not proof_goals

        print(proxy.describe_state("state0"))

        # Execute a lemma statement, save as "state1".
        print(header("lemma"))
        lemma = 'lemma primes_infinite: "\\<not> (finite {(p::nat). prime p})"'
        print(indent(lemma))
        proof_done, proof_goals = proxy.execute("state0", lemma, "state1")
        print(header("proof state"))
        print(indent(proof_goals))
        assert not proof_done
        assert proof_goals.startswith("proof (prove)\ngoal (1 subgoal):\n")

        # Call hammer on "state1" to find a proof.
        print(header("hammer"))
        proof = proxy.hammer("state1")
        print(indent(proof))

        # Executre the proof and check it proved the lemma.
        print(header("execute proof"))
        proof_done, proof_goals = proxy.execute("state1", proof, "state2")
        assert proof_done and not proof_goals
        print("OK")


def evaluate_model(model: Model, tests: list[TestCase], server_afp_dir: Path) -> None:
    summary: dict[str, int] = defaultdict(int)
    for i, test_case in enumerate(tests):
        print(header(f"Test case {test_case.name}, thy file: {test_case.thy_file}"))
        print(header("Lemma statement"))
        print(indent(test_case.lemma_statement))

        theory_path = server_afp_dir / "thys" / test_case.thy_file
        try:
            print(header("Server init"))
            with QIsabelleProxy(theory_path=theory_path) as proxy:
                r = run_model_greedily(model, theory_path, test_case.lemma_statement, proxy)
            result = "success" if r else "failure"
        except Exception as e:
            print(header("Exception"))
            print(indent(str(e)))
            result = get_exception_kind(e)

        summary[result] += 1
        print(header(result, "$"))
        print(f"Did {i + 1} / {len(tests)} tests so far:", dict(summary.items()))
        print("\n\n\n")
    print(f"Finished evaluation. Results:\n    {dict(summary.items())} / {len(tests)}")


def run_model_greedily(
    model: Model, theory_path: Path, lemma_statement: str, proxy: QIsabelleProxy
) -> bool:
    PROOF_SEARCH_MAX_TIME = 500.0  # float seconds
    print(" Load theory ".center(100, "%"))
    state_name = "s"
    proof_done, proof_goals = proxy.load_theory(theory_path, lemma_statement, True, state_name)
    assert not proof_done

    prev_proof_step = lemma_statement

    end_time = time.time() + PROOF_SEARCH_MAX_TIME
    while time.time() < end_time:
        print(header("Proof state"))
        print(indent(proof_goals))

        generated_steps = model(prev_proof_step, proof_goals)
        if not generated_steps:
            return False
        proof_step, subscore = generated_steps[0]
        print(header(f"Model gave (with {subscore=})"))
        print(indent(proof_step))
        new_state_name = f"{state_name}.0"

        if proof_step.strip() == "normalhammer":
            proof_step = proxy.hammer(state_name)
            print(header("Hammer gave"))
            print(indent(proof_step))
        proof_done, new_proof_state = proxy.execute(state_name, proof_step, new_state_name)

        if new_proof_state == proof_goals:
            print("Proof state unchanged :(")
            return False

        if proof_done:
            return True
        prev_proof_step = proof_step
        state_name = new_state_name

    return False


def test_extractions(extractions_dir: Path, afp_dir: Path) -> None:
    print(f"Loading extractions from {extractions_dir}...")
    extractions = load_extractions(extractions_dir)
    print(f"Loaded {len(extractions)} extractions.")
    for e in extractions:
        if not (afp_dir / "thys" / e.thy_file).exists():
            print(f"Warning (extraction): no such theory file: {afp_dir / 'thys' / e.thy_file}")


def indent(text: str, indentation: str = "\t") -> str:
    """Indend text with tabs, strip the final newline."""
    return indentation + text.strip().replace("\n", "\n" + indentation)


def header(title: str, fill_char: str = "%") -> str:
    """Center a string in % chars."""
    return (" " + title + " ").center(100, fill_char)


if __name__ == "__main__":
    main()
