"""Configuration loading and validation for the local agent harness."""

from dataclasses import dataclass
import ipaddress
import json
import math
from pathlib import Path
from typing import Optional
from urllib.parse import urlsplit


DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1"


class ConfigError(ValueError):
    """Raised when the harness configuration is missing or invalid."""


@dataclass(frozen=True)
class AppConfig:
    """Validated settings used by the Phase 1 harness."""

    base_url: str
    model: str
    timeout_seconds: float
    data_dir: Path
    config_path: Path


def _validate_base_url(value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ConfigError("base_url must be a non-empty local HTTP URL")
    candidate = value.strip().rstrip("/")
    try:
        parsed = urlsplit(candidate)
        hostname = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise ConfigError("base_url is not a valid URL") from exc

    if (
        parsed.scheme != "http"
        or hostname is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ConfigError(
            "base_url must use HTTP on localhost or a loopback IP without credentials"
        )
    if hostname.lower() != "localhost":
        try:
            address = ipaddress.ip_address(hostname)
        except ValueError as exc:
            raise ConfigError(
                "base_url host must be localhost or a loopback IP address"
            ) from exc
        if not address.is_loopback:
            raise ConfigError("base_url host must be localhost or a loopback IP address")
    if port is not None and not 1 <= port <= 65535:
        raise ConfigError("base_url port must be between 1 and 65535")
    if not parsed.path.startswith("/"):
        raise ConfigError("base_url path must begin with '/'")
    return candidate


def load_config(
    config_path: Optional[Path] = None,
    *,
    base_url_override: Optional[str] = None,
    model_override: Optional[str] = None,
) -> AppConfig:
    """Load JSON settings; relative data_dir paths resolve beside the config."""
    if config_path is None:
        config_path = Path(__file__).resolve().parent.parent / "config.json"
    path = Path(config_path).resolve()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ConfigError("cannot read config file: {}".format(path)) from exc
    except json.JSONDecodeError as exc:
        raise ConfigError("config file is not valid JSON: {}".format(path)) from exc

    if not isinstance(raw, dict):
        raise ConfigError("config file must contain a JSON object")
    allowed = {"base_url", "model", "timeout_seconds", "data_dir"}
    unknown = sorted(set(raw) - allowed)
    if unknown:
        raise ConfigError("unknown config setting(s): {}".format(", ".join(unknown)))

    base_url_value = base_url_override if base_url_override is not None else raw.get(
        "base_url", DEFAULT_BASE_URL
    )
    base_url = _validate_base_url(base_url_value)

    model_value = model_override if model_override is not None else raw.get("model", "")
    if not isinstance(model_value, str):
        raise ConfigError("model must be a string")
    model = model_value.strip()

    timeout_value = raw.get("timeout_seconds", 90)
    if isinstance(timeout_value, bool) or not isinstance(timeout_value, (int, float)):
        raise ConfigError("timeout_seconds must be a positive number")
    timeout_seconds = float(timeout_value)
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise ConfigError("timeout_seconds must be a positive number")

    data_dir_value = raw.get("data_dir", "../the-nightmare-data")
    if not isinstance(data_dir_value, str) or not data_dir_value.strip():
        raise ConfigError("data_dir must be a non-empty relative path")
    relative_data_dir = Path(data_dir_value)
    if relative_data_dir.is_absolute():
        raise ConfigError("data_dir must be relative to the config file")
    config_dir = path.parent
    data_dir = (config_dir / relative_data_dir).resolve()
    try:
        data_dir.relative_to(config_dir)
    except ValueError:
        is_inside_config_dir = False
    else:
        is_inside_config_dir = True
    if is_inside_config_dir:
        raise ConfigError("data_dir must resolve outside the config directory")

    return AppConfig(
        base_url=base_url,
        model=model,
        timeout_seconds=timeout_seconds,
        data_dir=data_dir,
        config_path=path,
    )
