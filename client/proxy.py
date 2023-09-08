from __future__ import annotations

from pathlib import Path
from typing import Any, Optional, cast

import requests
from typing_extensions import Self

JSON = dict[str, Any]


class QIsabelleProxy:
    """A proxy object for a QIsabelle server: a class for making calls to the server."""

    def __init__(
        self,
        theory_path: Path,
        working_directory: Optional[Path] = None,
        port: int = 17000,
        debug: bool = True,
    ):
        self.port = port
        self.debug = debug
        if debug:
            print("QIsabelleProxy initializing..")
        r = self._post(
            "/openIsabelleSession",
            {
                "workingDir": str(working_directory or theory_path.parent),
                "theoryPath": str(theory_path),
            },
        )
        assert r == {"success": "success"}, r
        if debug:
            print("QIsabelleProxy initialized.")

    def _post(self, path: str, json_data: Optional[dict[str, Any]] = None) -> JSON:
        if self.debug:
            print(f"Request to http://localhost:{self.port}{path} with {json_data}")
        if json_data is None:
            json_data = {}
        response = requests.post(f"http://localhost:{self.port}{path}", json=json_data)
        response.raise_for_status()
        result = response.json()
        assert isinstance(result, dict)
        if "error" in result:
            raise RuntimeError(result["error"] + "\nTraceback:\n" + result["traceback"])
        return cast(JSON, result)

    def __enter__(self) -> Self:
        return self

    def __exit__(self, _exc_type: Any, _exc_value: Any, _traceback: Any) -> None:
        r = self._post("/closeIsabelleSession")
        assert r == {"success": "Closed"}, r

    def load_theory(
        self, theory_path: Path, until: str, inclusive: bool, new_state_name: str
    ) -> tuple[bool, str]:
        r = self._post(
            "/loadTheory",
            {
                "theoryPath": str(theory_path),
                "until": until,
                "inclusive": inclusive,
                "newStateName": new_state_name,
            },
        )
        return cast(bool, r["proofDone"]), cast(str, r["proofGoals"])

    def describe_state(self, state_name: str) -> str:
        r = self._post("/describeState", {"stateName": state_name})
        return cast(str, r["description"])

    def execute(self, state_name: str, isar_code: str, new_state_name: str) -> tuple[bool, str]:
        r = self._post(
            "/execute",
            {"stateName": state_name, "isarCode": isar_code, "newStateName": new_state_name},
        )
        return cast(bool, r["proofDone"]), cast(str, r["proofGoals"])

    def forget_state(self, state_name: str) -> None:
        r = self._post("/forgetState", {"stateName": state_name})
        assert r == {"success": "success"}, r

    def forget_all_states(self) -> None:
        r = self._post("/forgetAllStates")
        assert r == {"success": "success"}, r

    def hammer(
        self, state_name: str, added_facts: list[str] = [], deleted_facts: list[str] = []
    ) -> str:
        r = self._post(
            "/hammer",
            {"stateName": state_name, "addedFacts": added_facts, "deletedFacts": deleted_facts},
        )
        return cast(str, r["proof"])


def get_exception_kind(e: Exception) -> str:
    s = repr(e)
    if "Transition not found" in s:
        return "not-found"
    elif "NoSuchFileException" in s:
        return "no-such-file"
    elif "Sledgehammer timeout: Timed out" in s:
        return "timeout-soft"
    elif "Sledgehammer timeout: Mid timeout exceeded" in s:
        return "timeout-mid"
    elif "Sledgehammer timeout: Hard timeout exceeded" in s:
        return "timeout-hard"
    elif "IsabelleMLException: Timeout" in s:
        return "execution-timeout"
    elif "Failed to apply initial proof method" in s:
        return "failed-proof"
    else:
        return "unknown"
