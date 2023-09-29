from pathlib import Path


def read_env_dict(p: Path) -> dict[str, str]:
    """Read an .env file as a dictionary (no quotes handling, no bash expressions)."""
    lines = [line.strip() for line in p.read_text().splitlines() if line.strip()]
    return dict(line.split("=", maxsplit=1) for line in lines)
