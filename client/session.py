from __future__ import annotations

from pathlib import Path
from typing import Any, Optional, cast

import requests
from typing_extensions import Self

JSON = dict[str, Any]


class QIsabelleServerError(RuntimeError):
    pass


class QIsabelleSession:
    """A session with a QIsabelle server.

    This is just a simple wrapper around the server's HTTP API.
    For documentation, do see `server/src/QISabelleServer.scala`.
    """

    def __init__(
        self,
        session_name: Optional[str] = None,
        session_roots: Optional[list[Path]] = None,
        theory_path: Optional[Path] = None,
        port: int = 17000,
        debug: bool = True,
    ):
        """
        Either theory_path or (session_name and session_roots) must be provided.
        """
        self.port = port
        self.debug = debug
        if debug:
            print("QIsabelleSession initializing..")
        if theory_path is not None:
            assert (
                session_name is None and session_roots is None
            ), "Cannot use both theory_path or session_name."
            r = self._post(
                "/openIsabelleSessionForTheory",
                {"theoryPath": str(theory_path)},
            )
        else:
            assert (
                session_name is not None and session_roots is not None
            ), "Either theory_path or (session_name and session_roots) must be provided."
            r = self._post(
                "/openIsabelleSession",
                {
                    "sessionName": session_name,
                    "sessionRoots": [str(p) for p in session_roots],
                    "workingDir": "/home/isabelle/",
                },
            )
        assert r == {"success": "success"}, r
        if debug:
            print("QIsabelleSession initialized.")

    def _post(self, path: str, json_data: Optional[dict[str, Any]] = None) -> JSON:
        if self.debug:
            print(f"Request to http://localhost:{self.port}{path} with {json_data}")
        response = requests.post(f"http://localhost:{self.port}{path}", json=json_data or {})
        response.raise_for_status()
        result = response.json()
        assert isinstance(result, dict)
        if "error" in result:
            msg = result["error"]
            if result.get("traceback"):
                msg += "\nTraceback:\n" + result["traceback"]
            raise QIsabelleServerError(msg)
        return cast(JSON, result)

    def __enter__(self) -> Self:
        return self

    def __exit__(self, _exc_type: Any, _exc_value: Any, _traceback: Any) -> None:
        r = self._post("/closeIsabelleSession")
        assert r == {"success": "Closed"}, r

    def new_theory(
        self,
        theory_name: str,
        new_state_name: str,
        imports: list[str] = ["Main"],
        master_dir: Path = Path("/home/isabelle/"),
        only_import_from_session_heap: bool = True,
    ) -> None:
        r = self._post(
            "/newTheory",
            {
                "theoryName": theory_name,
                "newStateName": new_state_name,
                "imports": imports,
                "masterDir": str(master_dir),
                "onlyImportFromSessionHeap": only_import_from_session_heap,
            },
        )
        assert r == {"success": "success"}, r

    def load_theory(
        self, theory_path: Path, until: str, inclusive: bool, new_state_name: str, init_only: bool = False
    ) -> tuple[bool, str]:
        r = self._post(
            "/loadTheory",
            {
                "theoryPath": str(theory_path),
                "until": until,
                "inclusive": inclusive,
                "newStateName": new_state_name,
                "initOnly": init_only,
            },
        )
        return cast(bool, r["proofDone"]), cast(str, r["proofGoals"])

    def describe_state(self, state_name: str) -> str:
        r = self._post("/describeState", {"stateName": state_name})
        return cast(str, r["description"])
    
    def get_mode(self, state_name: str) -> str:
        r = self._post("/getMode", {"stateName": state_name})
        return cast(str, r["description"])
    
    def get_theory(self, state_name: str) -> str:
        r = self._post("/getTheory", {"stateName": state_name})
        return cast(str, r["description"])
    
    def get_proof_state_description(self, state_name: str) -> str:
        r = self._post("/getProofStateDescription", {"stateName": state_name})
        return cast(str, r["description"])

    def execute(self, state_name: str, isar_code: str, new_state_name: str, timeout: int = 0) -> tuple[bool, str]:
        r = self._post(
            "/execute",
            {"stateName": state_name, "isarCode": isar_code, "newStateName": new_state_name, "timeout": timeout},
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
