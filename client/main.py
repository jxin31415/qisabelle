import heapq
import json
import re
import time
from pathlib import Path
from dataclasses import dataclass
from typing import Iterable
from collections import defaultdict

from .model import Model, DummyGTModel, DummyHammerModel  # noqa: F401
from .proxy import QIsabelleProxy

ROOT_DIR = Path("/home/mwrochna/projects/play/")

MAX_RPC_MESSAGE_LENGTH = 100 * 1024 * 1024


def main() -> None:
    tests_dir = ROOT_DIR / "Portal-to-ISAbelle" / "universal_test_theorems"
    test_files = sorted(tests_dir.glob("quick*.json"), key=_numeric_sort_key)
    tests = list(load_tests(test_files, afp_dir=ROOT_DIR / "afp-2023-03-16"))
    print(f"Loaded {len(tests)} tests.")

    if True:
        test_qisabelle_client()
        return

    evaluate_model(
        DummyGTModel(),
        tests,
        server_afp_dir=Path("/afp/"),
    )


@dataclass
class TestCase:
    name: str  # Name of test, like "quick_test_name_599" or "test_name_2999".
    thy_file: Path  # Path to theory .thy file, relative to "/afp/thys/".
    lemma_statement: str


def test_qisabelle_client() -> None:
    p = Path("/afp/thys/Real_Impl/Real_Impl_Auxiliary.thy")
    # p = Path("/afp/thys/Valuation/Valuation1.thy")
    proxy = QIsabelleProxy(working_directory=p, context_file=p.parent, target="end")
    print("-" * 50, "A")
    print(
        proxy.step_tls(
            'lemma primes_infinite: "\\<not> (finite {(p::nat). prime p})"', "default", "test"
        )
    )
    print("-" * 50, "B")
    print(proxy.step_tls("sledgehammer", "test", "test1"))
    print("-" * 50, "C")
    print(proxy.step_tls("normalhammer", "test", "test1"))
    # print(proxy.step_tls('delhammer primes_infinite', 'test', 'test2'))
    # print(proxy.step_tls('delhammer primes_infinite,bigger_prime', 'test', 'test3'))


def load_tests(test_files: Iterable[Path], afp_dir: Path) -> Iterable[TestCase]:
    for p in test_files:
        with open(p) as f:
            o = json.load(f)
        assert isinstance(o, list) and len(o) == 1
        o = o[0]
        assert isinstance(o, list) and len(o) == 2
        thy_file, lemma_statement = o
        assert isinstance(thy_file, str) and isinstance(lemma_statement, str)
        thy_file = thy_file.split("/thys/", maxsplit=1)[1]
        if not lemma_statement.startswith(("lemma ", "theorem ")):
            print(f"Unusual test case lemma statement: {lemma_statement}")
        if not (afp_dir / "thys" / thy_file).exists():
            print(f"No such theory file: {thy_file}")
        yield TestCase(name=p.stem, thy_file=Path(thy_file), lemma_statement=lemma_statement)


def evaluate_model(model: Model, tests: list[TestCase], server_afp_dir: Path) -> None:
    summary: dict[str, int] = defaultdict(int)
    for test_case in tests:
        print("\n\n\n", "%" * 100)
        print(f"%%% Test case {test_case.name}, thy file: {test_case.thy_file}")
        print("%", test_case.lemma_statement.strip().replace("\n", "\n% "))
        print()

        thy_file = server_afp_dir / "thys" / test_case.thy_file
        try:
            with QIsabelleProxy(
                working_directory=thy_file.parent,
                context_file=thy_file,
                target=test_case.lemma_statement.strip(),
            ) as proxy:
                r = run_model_on_test_case(model, test_case.lemma_statement, proxy)
            result = "success" if r else "failure"
        except Exception as e:
            print(repr(e))
            result = "exception"

        summary[result] += 1
        print("$" * 100, f"{result} ({summary} / {len(tests)}).")
    print(f"End of evaluation: {summary} / {len(tests)}")


def run_model_on_test_case(model: Model, lemma_statement: str, proxy: QIsabelleProxy) -> bool:
    # BestFS, following https://arxiv.org/pdf/2009.03393.pdf:
    # They actually retry everything 4 times.
    try:
        state, _ = proxy.step_tls(lemma_statement, "default", "s")
    except RuntimeError as e:
        print(e)
        return False
    print("%" * 100, f"Starting proof search with {lemma_statement=}, {state=}")
    # Priority queue where scores are cumulative minus-log-probs
    # (the smaller the score, the larger the probability predicted for the state).
    pqueue: list[tuple[float, str, str, str]] = [(0, lemma_statement, state, "s")]
    max_n_expansions = 128
    # TODO timeout 10s on proof step (in PISA?)
    # TODO limit number of queries to model: 300?
    PROOF_SEARCH_MAX_TIME = 500.0  # float seconds
    start_time = time.time()
    while pqueue and max_n_expansions > 0 and time.time() - start_time < PROOF_SEARCH_MAX_TIME:
        max_n_expansions -= 1
        score, prev_proof_step, state, state_name = heapq.heappop(pqueue)
        print("%" * 100, f"Pop {score=}, {prev_proof_step=}, {state=}")
        # Expand:
        for i, (proof_step, subscore) in enumerate(model(prev_proof_step, state)):
            print("%" * 100, f"Model gave {proof_step=}, {subscore=}")
            new_state_name = f"{state_name}.{i}"
            try:
                new_state, done = proxy.step_tls(proof_step, state_name, new_state_name)
            except RuntimeError as e:
                print(e)
                continue
            if new_state == state:
                print("%" * 100, "Which yields same state.")
                continue
            else:
                print("%" * 100, f"Which yields {new_state=}, {done=}")
            if done:
                return True
            if len(pqueue) < 32:
                heapq.heappush(pqueue, (score + subscore, proof_step, new_state, new_state_name))
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
