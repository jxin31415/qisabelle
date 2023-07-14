from __future__ import annotations

import heapq
import json
import re
import time
from pathlib import Path
from typing import List, Optional, Tuple

import requests
from typing_extensions import Self

ROOT_DIR = Path("/home/mwrochna/projects/play/")

MAX_RPC_MESSAGE_LENGTH = 100 * 1024 * 1024
THEOREM_SEPARATOR = "<THM_SEP>"


def main():
    model = Model()

    if False:
        test_qisabelle_client(
            working_directory=Path("/afp/thys/Real_Impl"),
            context_file=Path("/afp/thys/Real_Impl/Real_Impl_Auxiliary.thy"),
        )
        return
    if False:
        p = Path("/afp/thys/Valuation/Valuation1.thy")
        test_qisabelle_client(
            working_directory=p.parent,
            context_file=p,
        )

    do_test(
        model,
        tests_dir=ROOT_DIR / "Portal-to-ISAbelle" / "universal_test_theorems",
        client_afp_dir=ROOT_DIR / "afp-2023-03-16",
        server_afp_dir=Path("/afp/"),
    )
    # do_train(model, afp_extractions_dir=ROOT_DIR / "afp_extractions")


def _numeric_sort_key(s: Path) -> tuple[str | int, ...]:
    result: list[str | int] = []
    for x in re.split(r"(\d+)", str(s)):
        try:
            result.append(int(x))
        except ValueError:
            result.append(x)
    return tuple(result)


def do_test(model: Model, tests_dir: Path, client_afp_dir: Path, server_afp_dir: Path) -> None:
    tests = sorted(tests_dir.glob("quick*.json"), key=_numeric_sort_key)
    print(len(tests))
    results: dict[Path, bool] = {}
    ok = 0
    fail = 0
    unk = 0
    for p in tests:
        print("\n\n\n", "%" * 100, f"Test case {p=}")
        with open(p) as f:
            o = json.load(f)
            assert isinstance(o, list) and len(o) == 1
            o = o[0]
            assert isinstance(o, list) and len(o) == 2
            thy_file, lemma_statement = o
            assert isinstance(thy_file, str) and isinstance(lemma_statement, str)
            thy_file = thy_file.split("/thys/", maxsplit=1)[1]
            try:
                print("%" * 100, f"Loading test case from {thy_file=}, {lemma_statement=}")
                assert (client_afp_dir / "thys" / thy_file).exists(), f"No such file: {thy_file}"
                assert lemma_statement.startswith(
                    ("lemma ", "theorem ")
                ), f"Unusual statement: {lemma_statement}"
                with QIsabelleClient(
                    working_directory=(server_afp_dir / "thys" / thy_file).parent,
                    context_file=server_afp_dir / "thys" / thy_file,
                    target=lemma_statement.strip()
                ) as proxy:
                    result = run_model_on_test(model, lemma_statement, proxy)
            except Exception as e:
                print(repr(e))
                unk += 1
                result = False
            results[p] = result
            if result:
                ok += 1
            else:
                fail += 1
            print("$" * 100, f"result={result} ({ok} + {unk} + {fail} / {len(tests)}).")
            # return
    print(results)
    print(f"{ok} + {unk} + {fail} / {len(tests)}")  # 547 + 51 / 600 for quick_test_name_*


def run_model_on_test(model: Model, lemma_statement: str, proxy: QIsabelleClient) -> bool:
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
    pqueue: List[Tuple[float, str, str, str]] = [(0, lemma_statement, state, "s")]
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


class Model:
    def __call__(
        self,
        context: str,
        proof_state: str,
        temperature: float = 1.2,
        max_expansion: int = 32,
    ) -> List[Tuple[str, float]]:
        """Returns (proof_step, score), where score is min-log-prob of the single step."""
        # TODO get a list with scores from GPT
        # TODO set temp=1.2

        return [("normalhammer", 0.1)]


def test_qisabelle_client(working_directory: Path, context_file: Path) -> None:
    proxy = QIsabelleClient(
        working_directory=working_directory,
        context_file=context_file,
        target="end"
    )
    print("-" * 50, "A")
    print(proxy.step_tls('lemma primes_infinite: "\\<not> (finite {(p::nat). prime p})"',
                       "default", "test"))
    print("-" * 50, "B")
    print(proxy.step_tls("sledgehammer", "test", "test1"))
    print("-" * 50, "C")
    print(proxy.step_tls("normalhammer", "test", "test1"))
    # print(proxy.step_tls('delhammer primes_infinite', 'test', 'test2'))
    # print(proxy.step_tls('delhammer primes_infinite,bigger_prime', 'test', 'test3'))


class QIsabelleClient:
    def __init__(
        self,
        context_file: Path,
        working_directory: Path,
        target: str,
        port: int = 17000,
        debug: bool = True,
    ):
        self.port = port
        self.debug = debug
        # TODO timeout 10 on initialize?
        print("Init..")
        r = self._post(
            "/initializePisaOS",
            {
                "workingDir": str(working_directory),
                "theoryPath": str(context_file),
                "target": target
            },
        )
        assert r.json() == "success", r.text
        print("Init done.")
        # r.json()

    def _post(self, path: str, json_data: Optional[dict] = None) -> requests.Response:
        print(f"Request to http://localhost:{self.port}{path} with data={json_data}")
        if json_data is None:
            json_data = {}
        r = requests.post(f"http://localhost:{self.port}{path}", json=json_data)
        r.raise_for_status()
        return r

    def __enter__(self) -> Self:
        return self

    def __exit__(self, _exc_type, _exc_value, _traceback) -> None:
        r = self._post("/exitPisaOS")
        assert r.json() == "Destroyed", r.text

    def step_tls(self, action: str, tls_name: str, new_name: str) -> Tuple[str, bool]:
        r = self._post(
            "/step", {"state_name": tls_name, "action": action, "new_state_name": new_name}
        )
        obs_string = r.json()["state_string"]
        if "error" in obs_string:
            raise RuntimeError(obs_string)
        done = r.json()["done"]
        return obs_string, done


if __name__ == "__main__":
    main()
