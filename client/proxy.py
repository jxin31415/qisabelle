from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

import requests
from typing_extensions import Self


class QIsabelleProxy:
    """Class for making calls to QIsabelle server."""
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
                "target": target,
            },
        )
        assert r.json() == "success", r.text
        print("Init done.")
        # r.json()

    def _post(self, path: str, json_data: Optional[dict[str, Any]] = None) -> requests.Response:
        print(f"Request to http://localhost:{self.port}{path} with data={json_data}")
        if json_data is None:
            json_data = {}
        r = requests.post(f"http://localhost:{self.port}{path}", json=json_data)
        r.raise_for_status()
        return r

    def __enter__(self) -> Self:
        return self

    def __exit__(self, _exc_type: Any, _exc_value: Any, _traceback: Any) -> None:
        r = self._post("/exitPisaOS")
        assert r.json() == "Destroyed", r.text

    def step_tls(self, action: str, tls_name: str, new_name: str) -> tuple[str, bool]:
        r = self._post(
            "/step", {"state_name": tls_name, "action": action, "new_state_name": new_name}
        )
        obs_string = r.json()["state_string"]
        if "error" in obs_string:
            raise RuntimeError(obs_string)
        done = r.json()["done"]
        return obs_string, done
