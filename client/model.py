
from abc import ABC, abstractmethod


class Model(ABC):
    @abstractmethod
    def __call__(
        self,
        context: str,
        proof_state: str,
        ground_truth: str = "",
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
        ground_truth: str = "",
        temperature: float = 1.2,
        max_expansion: int = 32,
    ) -> list[tuple[str, float]]:
        return [("normalhammer", 0.1)]


class DummyGTModel(Model):
    """Dummy model that always answers whatever the ground-truth is."""
    def __call__(
        self,
        context: str,
        proof_state: str,
        ground_truth: str = "",
        temperature: float = 1.2,
        max_expansion: int = 32,
    ) -> list[tuple[str, float]]:
        return [(ground_truth, 0.1)]
