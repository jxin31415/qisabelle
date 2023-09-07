#!/usr/bin/env python3
import heapq
import re
import time
from collections import defaultdict
from pathlib import Path

from .extractions import load_extractions
from .model import DummyGTModel, DummyHammerModel, Model  # noqa: F401
from .proxy import QIsabelleProxy
from .test_cases import TestCase, load_test_cases

ROOT_DIR = Path("/home/mwrochna/projects/play/")

MAX_RPC_MESSAGE_LENGTH = 100 * 1024 * 1024


def main() -> None:
    if False:
        extractions_dir = ROOT_DIR / "afp_extractions"
        print(f"Loading extractions from {ROOT_DIR / 'afp_extractions'}...")
        extractions = load_extractions(extractions_dir, afp_dir=ROOT_DIR / "afp-2023-03-16")
        print(f"Loaded {len(extractions)} extractions.")

    tests_dir = ROOT_DIR / "Portal-to-ISAbelle" / "universal_test_theorems"
    print(f"Loading tests from {tests_dir}...")
    test_files = sorted(tests_dir.glob("quick*.json"), key=_numeric_sort_key)
    # test_files = test_files[:1]
    tests = load_test_cases(test_files, afp_dir=ROOT_DIR / "afp-2023-03-16")
    print(f"Loaded {len(tests)} tests.")

    if False:
        test_qisabelle_client()
        return

    evaluate_model(DummyHammerModel(), tests, server_afp_dir=Path("/afp/"))


def test_qisabelle_client() -> None:
    p = Path("/afp/thys/Real_Impl/Real_Impl_Auxiliary.thy")
    with QIsabelleProxy(theory_path=p) as proxy:
        print("load theory".center(100, "-"))
        proof_done, proof_state = proxy.load_theory(p, "", False, "state0")
        assert proof_done and not proof_state

        print(proxy.describe_state("state0"))

        print("lemma".center(100, "-"))
        lemma = 'lemma primes_infinite: "\\<not> (finite {(p::nat). prime p})"'
        proof_done, proof_state = proxy.execute("state0", lemma, "state1")
        print(f"{proof_done=}, {proof_state=}")
        assert not proof_done
        assert proof_state.startswith("proof (prove)\ngoal (1 subgoal):\n")

        print("call hammer".center(100, "-"))
        proof = proxy.hammer("state1")
        print(f"{proof=}")

        print("execute proof".center(100, "-"))
        proof_done, proof_state = proxy.execute("state1", proof, "state2")
        assert proof_done and not proof_state


def evaluate_model(model: Model, tests: list[TestCase], server_afp_dir: Path) -> None:
    summary: dict[str, int] = defaultdict(int)
    for i, test_case in enumerate(tests):
        print("\n\n\n" + "%" * 100)
        print(f"%%% Test case {test_case.name}, thy file: {test_case.thy_file}")
        print(" Lemma statement ".center(100, "%"))
        print("\t" + test_case.lemma_statement.strip().replace("\n", "\n\t"))
        print()

        theory_path = server_afp_dir / "thys" / test_case.thy_file
        try:
            print(" Server init ".center(100, "%"))
            with QIsabelleProxy(theory_path=theory_path) as proxy:
                r = run_model_on_test_case(model, theory_path, test_case.lemma_statement, proxy)
            result = "success" if r else "failure"
        except Exception as e:
            print(" Exception ".center(100, "%"))
            print("\t" + str(e).replace("\n", "\n\t"))
            if "undefined entry for theory" in repr(e):
                result = "undefined"
            elif "did not find the text" in repr(e):
                result = "not_found"
            elif "No such file" in repr(e):
                result = "no_such_file"
            elif "NoSuchFileException" in repr(e):
                result = "no_such_file2"
            elif "Sledgehammer timeout: Timed out" in repr(e):
                result = "timeout-soft"
            elif "Sledgehammer timeout: Mid timeout exceeded" in repr(e):
                result = "timeout-mid"
            elif "Sledgehammer timeout: Hard timeout exceeded" in repr(e):
                result = "timeout-hard"
            elif "IsabelleMLException: Timeout" in repr(e):
                result = "execution-timeout"
            else:
                # %%% Test case quick_test_name_298, thy file: Splay_Tree/Splay_Tree.thy
                # %%% Test case quick_test_name_219, thy file: Word_Lib/Word_Lemmas.thy
                # %%% Test case quick_test_name_201, thy file: Native_Word/Uint8.thy

                # RuntimeError('Error during step: de.unruh.isabelle.control.IsabelleMLException: Timeout after 2.810s')
                result = "exception"

        summary[result] += 1
        print(f" {result} ".center(100, "$"))
        print(f"Did {i + 1} / {len(tests)} tests so far:", dict(summary.items()))
    print(f"End of evaluation: {summary} / {len(tests)}")


def run_model_on_test_case(
    model: Model, theory_path: Path, lemma_statement: str, proxy: QIsabelleProxy
) -> bool:
    # BestFS, following https://arxiv.org/pdf/2009.03393.pdf:
    # They actually retry everything 4 times.
    PROOF_SEARCH_MAX_TIME = 500.0  # float seconds
    MAX_N_EXPANSIONS = 128
    MAX_QUEUE_LEN = 32
    # TODO limit number of queries to model: 300?

    print(" Load theory ".center(100, "%"))
    try:
        proof_done, proof_state = proxy.load_theory(theory_path, lemma_statement, True, "s")
        assert not proof_done
    except RuntimeError as e:
        raise
        # print(e)
        # return False
    print(" Proof state ".center(100, "%"))
    print("\t" + proof_state.strip().replace("\n", "\n\t"))

    # Priority queue where scores are cumulative minus-log-probs
    # (the smaller the score, the larger the probability predicted for the state).
    pqueue: list[tuple[float, str, str, str]] = [(0, lemma_statement, proof_state, "s")]
    n_expansions_left = MAX_N_EXPANSIONS

    end_time = time.time() + PROOF_SEARCH_MAX_TIME
    while pqueue and n_expansions_left > 0 and time.time() < end_time:
        n_expansions_left -= 1
        # Pop:
        score, prev_proof_step, proof_state, state_name = heapq.heappop(pqueue)
        print(" Pop ".center(100, "%"))
        print(f"{score=}, {state_name=}")

        # Expand:
        generated_steps = model(prev_proof_step, proof_state)
        for i, (proof_step, subscore) in enumerate(generated_steps):
            print(f" Model gave (with {subscore=}) ".center(100, "%"))
            print("\t" + proof_step.strip().replace("\n", "\n\t"))
            new_state_name = f"{state_name}.{i}"
            try:
                if proof_step.strip() == "normalhammer":
                    proof_step = proxy.hammer(state_name)
                    print(" Hammer gave ".center(100, "%"))
                    print("\t" + proof_step.strip().replace("\n", "\n\t"))
                proof_done, new_proof_state = proxy.execute(state_name, proof_step, new_state_name)
            except RuntimeError as e:
                raise
                # print(e)
                # continue
            print(f" Proof state ({proof_done=}) ".center(100, "%"))
            print(f"{new_state_name=}")
            if new_proof_state == proof_state:
                print("Unchanged :(")
                continue
            else:
                print("\t" + new_proof_state.strip().replace("\n", "\n\t"))
            if proof_done:
                return True
            if len(pqueue) < MAX_QUEUE_LEN:
                heapq.heappush(
                    pqueue, (score + subscore, proof_step, new_proof_state, new_state_name)
                )
    return False


def _numeric_sort_key(s: Path) -> tuple[str | int, ...]:
    result: list[str | int] = []
    for x in re.split(r"(\d+)", str(s)):
        try:
            result.append(int(x))
        except ValueError:
            result.append(x)
    return tuple(result)


if __name__ == "__main__":
    main()
