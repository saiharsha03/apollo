"""A page Athena writes that links apollo.css gets the look and fonts beside it (Drives looked empty, 2026-10-27)."""
from test_server import tools  # noqa: F401  (the tools fixture)


def test_a_page_that_links_apollo_css_gets_the_look_beside_it(tools):
    from apollo import apps
    apps.create("drives2", "Drives", "car", "test", True, "w", "athena")
    apps.write("drives2", "pages/index.html", '<link rel="stylesheet" href="apollo.css"><p>hi</p>', "w", "athena")
    p = apps.root() / "drives2" / "pages"
    assert (p / "apollo.css").is_file() and (p / "fonts").is_dir()
    apps.write("drives2", "pages/plain.html", "<p>no look</p>", "w", "athena")      # nothing to add
