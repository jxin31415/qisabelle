""" Loading test cases from PISA json files."""
import json
import re
from dataclasses import dataclass
from pathlib import Path


@dataclass
class TestCase:
    name: str  # Name of test, like "quick_test_name_599" or "test_name_2999".
    thy_file: Path  # Path to theory .thy file, relative to "/afp/thys/".
    lemma_statement: str


def load_all_test_cases(pisa_test_dir: Path) -> list[TestCase]:
    """Load all 3600 test cases from PISA ('universal_test_theorems/')."""
    test_files = sorted(pisa_test_dir.glob("*.json"), key=_numeric_sort_key)
    return [load_test_case(p) for p in test_files]


def load_quick_test_cases(pisa_test_dir: Path) -> list[TestCase]:
    """Load 600 "quick" test cases from PISA ('universal_test_theorems/')."""
    test_files = sorted(pisa_test_dir.glob("quick*.json"), key=_numeric_sort_key)
    return [load_test_case(p) for p in test_files]


def load_test_case(test_file: Path) -> TestCase:
    with open(test_file) as f:
        o = json.load(f)
    assert isinstance(o, list) and len(o) == 1
    o = o[0]
    assert isinstance(o, list) and len(o) == 2
    thy_file, lemma_statement = o
    assert isinstance(thy_file, str) and isinstance(lemma_statement, str)
    thy_file = thy_file.split("/thys/", maxsplit=1)[1]
    if not lemma_statement.startswith(("lemma ", "theorem ", "lemma[")):
        print(f"Unusual test case lemma statement: {lemma_statement}")
    return TestCase(name=test_file.stem, thy_file=Path(thy_file), lemma_statement=lemma_statement)


def _numeric_sort_key(s: Path) -> tuple[str | int, ...]:
    result: list[str | int] = []
    for x in re.split(r"(\d+)", str(s)):
        try:
            result.append(int(x))
        except ValueError:
            result.append(x)
    return tuple(result)
