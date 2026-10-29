"""Dark only (2026-10-27): every light seed file already planted is replaced by the dark one."""
import subprocess

import test_server  # noqa: F401  (the test data folders)

LIGHT = "9814afa"   # the last light commit
CHANGED = ["closet/pages/index.html", "dawn/pages/index.html", "home/pages/index.html", "memory/pages/index.html",
           "theogony/pages/index.html", "weather/widgets/now.html", "themes/README.md", "themes/default.json"]


def test_every_light_seed_file_planted_before_is_replaced():
    from apollo import apps, migrate, seed
    seed.plant()
    css = [p.relative_to(seed.SEED).as_posix() for p in seed.SEED.glob("*/pages/*.css") if p.name in ("apollo.css", "look.css") and p.parts[-3] != "drives"]
    files = css + CHANGED
    assert len(css) == 18
    for rel in files:
        old = subprocess.run(["git", "show", f"{LIGHT}:server/seed/{rel}"], capture_output=True).stdout
        assert old, rel
        (apps.root() / rel).write_bytes(old.replace(b"\n", b"\r\n") if rel.endswith(".css") else old)
    assert sorted(migrate.run()) == sorted(files)
    for rel in files:
        assert (apps.root() / rel).read_bytes() == (seed.SEED / rel).read_bytes(), rel
    assert migrate.run() == []
