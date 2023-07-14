import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass
class TestCase:
    name: str  # Name of test, like "quick_test_name_599" or "test_name_2999".
    thy_file: Path  # Path to theory .thy file, relative to "/afp/thys/".
    lemma_statement: str


def load_test_cases(test_files: Iterable[Path], afp_dir: Path) -> list[TestCase]:
    return [_load_test_case(p, afp_dir) for p in test_files]


def _load_test_case(test_file: Path, afp_dir: Path) -> TestCase:
    with open(test_file) as f:
        o = json.load(f)
    assert isinstance(o, list) and len(o) == 1
    o = o[0]
    assert isinstance(o, list) and len(o) == 2
    thy_file, lemma_statement = o
    assert isinstance(thy_file, str) and isinstance(lemma_statement, str)
    thy_file = thy_file.split("/thys/", maxsplit=1)[1]
    if not lemma_statement.startswith(("lemma ", "theorem ", "lemma[simp] ")):
        print(f"Unusual test case lemma statement: {lemma_statement}")
    if not (afp_dir / "thys" / thy_file).exists():
        print(f"No such theory file: {thy_file}")
    return TestCase(name=test_file.stem, thy_file=Path(thy_file), lemma_statement=lemma_statement)
