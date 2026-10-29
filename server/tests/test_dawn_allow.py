"""Dawn's Allow (2026-10-27): the planted page and README, while still the old seed's (LF or CRLF), are replaced."""
import subprocess

import test_server  # noqa: F401  (the test data folders)

BEFORE = "490164c"   # the last "I did this" only Dawn
FILES = ["dawn/pages/index.html", "dawn/README.md"]


def test_old_dawn_page_and_readme_are_replaced():
    from apollo import apps, migrate, seed
    seed.plant()
    for crlf in (False, True):
        for rel in FILES:
            old = subprocess.run(["git", "show", f"{BEFORE}:server/seed/{rel}"], capture_output=True).stdout
            assert old and b"\r" not in old, rel
            (apps.root() / rel).write_bytes(old.replace(b"\n", b"\r\n") if crlf else old)
        assert sorted(migrate.run()) == sorted(FILES)
        for rel in FILES:
            assert (apps.root() / rel).read_bytes() == (seed.SEED / rel).read_bytes(), rel
        assert migrate.run() == []
    assert b"data-allow" in (seed.SEED / FILES[0]).read_bytes()


def test_first_allow_page_is_replaced():
    from apollo import apps, migrate, seed
    seed.plant()
    rel = "dawn/pages/index.html"
    old = subprocess.run(["git", "show", f"cf1119d:server/seed/{rel}"], capture_output=True).stdout
    assert old
    for body in (old, old.replace(b"\n", b"\r\n")):
        (apps.root() / rel).write_bytes(body)
        assert migrate.run() == [rel]
        assert (apps.root() / rel).read_bytes() == (seed.SEED / rel).read_bytes()
    assert b"r.at > n.at" in (seed.SEED / rel).read_bytes()
