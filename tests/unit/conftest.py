import pytest
from hubidubios_vault.vault import Vault


@pytest.fixture
def vault(tmp_path):
    v = Vault(tmp_path / "vault")
    v.ensure_layout()
    return v
