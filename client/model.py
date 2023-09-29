from abc import ABC, abstractmethod


class Model(ABC):
    @abstractmethod
    def __call__(
        self,
        context: str,
        proof_state: str,
        known_solution: str = "",
        temperature: float = 1.2,
        max_expansion: int = 32,
    ) -> list[tuple[str, float]]:
        pass


class DummyHammerModel(Model):
    """Dummy model that always answers "normalhammer"."""

    def __call__(
        self,
        context: str,
        proof_state: str,
        known_solution: str = "",
        temperature: float = 1.2,
        max_expansion: int = 32,
    ) -> list[tuple[str, float]]:
        return [("normalhammer", 0.1)]


class DummyKnownSolutionModel(Model):
    """Dummy model that always answers whatever the ground-truth is."""

    def __call__(
        self,
        context: str,
        proof_state: str,
        known_solution: str = "",
        temperature: float = 1.2,
        max_expansion: int = 32,
    ) -> list[tuple[str, float]]:
        return [(known_solution, 0.1)]
