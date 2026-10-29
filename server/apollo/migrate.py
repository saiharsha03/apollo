"""Seed fixes for apps already planted (a seed change reaches only a fresh plant). Each replaces a file only while it is
byte for byte the old seed's (LF or CRLF), so anything Athena changed stays hers; files the fix needs that are missing are
added. Safe on every start: once replaced, the hash no longer matches.
"""
import hashlib
import logging
import shutil

from . import apps
from .seed import SEED

# (app, file, sha256 of the old seed file (LF, CRLF), files to add when missing)
FIXES = [
    # Weather's now widget: Open-Meteo °C shown as a bare "°" (the page shows °F), the old palette and a gradient.
    # ...and dark only (2026-10-27).
    ("weather", "widgets/now.html", {"6cfc5751e4c3b3a68dba12d21fed34f4b6a849407302ae87eb08c4e89342d04d",
                                     "00a024de90bad4a51bea4a953984e069bf2857245ccd872be60b1fdd4d81b487",
                                     "23174e75da03bd1344c0be0ba52ade32a1fbb24b8022311a73dba3373ac9d1d4",
                                     "d8d49b899d8c49be61363e6e81dd75fd7602db6d5e41550f636ba3d1e23550c8"},
     ["pages/fonts/figtree.ttf", "pages/fonts/doto.ttf"]),
    # Home: the phone now carries out his tile taps and answers {ok, detail}; the old page logged a tap whatever happened
    # and the old README told her to carry the tap out herself (twice, now). The page is dark only too (2026-10-27).
    ("home", "pages/index.html", {"f0c7c54600299938dbe1e9b76949c380d2b40fdb7ee3cf2d29260a364b8eb4b8",
                                  "b23b51f00f72fff07a8969566454d0cc52fa19d8ca6408ec2988f6a0a5be7f26",
                                  "81e914fbeb319ba1813e3c93963f801e56376b37f96f71a7a6de5db89b8bdfb8",
                                  "97ad968c0b9cd2cc24224fca50c9ea96bd8b1f8c413875d7fa38f553031104f9"}, []),
    ("home", "README.md", {"4803e479f108e0954944371e21af5f26ca75935173a89982a42046123e02ae7f",
                           "8a14c3dfc4839d49cc02ee9274a01eecae80c03762194c7337626a79ba23e10c"}, []),
    # Calliope: a line break typed inside a JS string, so the page's script never ran (no Done, record or nav).
    # ...and its voice note: it showed Stop before he tapped Record, and lost a note stopped from Apollo's banner.
    ("calliope", "pages/home.html", {"466cb589685b26979feaaa22adc2c43e8ada1671cb481e49c7408f5f93748628",
                                     "5e47d96b4dd455063e84223b299d4ee4c1c32c9dec6c5dd0456efcc39966d17a",
                                     "5f949bb43a949f848f78782fbc90eb7ebb01033b5c68720495393572ebc19ef3",
                                     "b8b04c1953aaae9767e10d2830305aff703b97c734a7a26cf4fdac69ea69a092"}, []),
]


# Dark only (Owner, 2026-10-27): every page, widget and theme file that was light, while it is an old seed's.
_LIGHT_APOLLO_CSS = {"4ebcd92702bfc54da7e90b099a29fd019e1ae0fef1916358967ec0a4e717304a",
                    "f016c38f1c06894a3a4ddd1f2ec41b403e366a0c28c961fdd210d3269c8bd2d6"}
_LIGHT_LOOK_CSS = {"8724e7998ddd19f89ae6ba7c96913562db37c44840eb2b4e4b0a73bec5ef2411",
                  "ff9cba81fbbef132a4b282f40328e0938a13eba47eba6ea08dabbe5b51e7ba06"}
FIXES += [(a, "pages/apollo.css", _LIGHT_APOLLO_CSS, []) for a in (
    "calendar", "fuel", "home", "money", "muse", "plazmi", "sports", "status", "tasks", "usage")]
FIXES += [(a, "pages/look.css", _LIGHT_LOOK_CSS, []) for a in (
    "brief", "calliope", "closet", "dawn", "memory", "people", "theogony", "trips")]
FIXES += [
    ("closet", "pages/index.html", {"075cf552f38db68bb04fa028a93b09e68d30170618c578ca084bcefab962703a",
                                    "0db9912cfd378f154a03590e364cc8aa503e37862223c2ea3fd038733713480b",
                                    "7cb796b258af76876bcfe0f7aaa5d53be843f13456d58e747212759337ff78b2",
                                    "ff2d120e8bd867cc2821236d639f9b3a92498db3fd1acfcd683ccf59cdca29d7"}, []),
    ("dawn", "pages/index.html", {"09b0bce90e4d0010fd945b00bb6462728a5a6142ec023557a564dee38a5abdd4",
                                  "8a4900c8b13f0c556ebb587a55cb1e430db48c5240d4d8b190c4cf2897748920"}, []),
    ("memory", "pages/index.html", {"4dce952a064ddeaa40afbfaec60213ef62f4a63b29c6277b2cdb2b84266318bb",
                                    "dbcd16998bdf1af3a73a794a66b4e525681d4f12df00711becea82872e140f34"}, []),
    ("themes", "README.md", {"98f9abdae2d5be92d3023dcd2eb866d1a3f735a203e7ea7af46c07844bae797c",
                             "c12192ea9b374d1e00e74580affdd14a8b6426596b067e64c93dd99072735f79"}, []),
    ("themes", "default.json", {"40cc1dfae9ea7d6e588d856fb733cf6cde079ba0b9d0d80e54e4897ecbc4f29e",
                                "a5218174e0c15f9e27eb532fff55babf732e6c5f617392afda70f92379372af1"}, []),
    ("theogony", "pages/index.html", {"bbe4c7b31fe9675a786b9593557d0494410c441d0c48f0df856575d8986c40a2",
                                      "f6d6422b10e208b5aa4394744be610e63fa996cfafad80790e20a55a6b160afb"}, []),
]
# Dawn asks for what the phone can grant (Allow) instead of "I did this" (2026-10-27): the dark page and its README.
FIXES += [
    # ...and its first Allow page, whose local answer outlived newer status rows, and the next one, which didn't
    # look again when a status came later (2026-10-27).
    ("dawn", "pages/index.html", {"3e5319998ad798f5e79cabe8caf0aca257cb1959554f68a4418ea0fbd1a4a816",
                                  "04f844383feff1a518504d6cac0754105e45505ec4b5307869b7a5eae80f0b1b",
                                  "b4b607ff4fddd3c1b3d26ddf97b7b594d23dadcc02b3ba095183acbd8fb1d07f",
                                  "bb722c119f3af5607e5ba79ef08511c151212e53240b535e1f74caf3f061a658",
                                  "4517c5d30810151be457fac6efc846b83caad3ef63bafc15b8e0b7958767b9fb",
                                  "9ccad8d6e309d003c19ab7cb938264616f7b105c7f5444f829c1c1191b60384a"}, []),
    ("dawn", "README.md", {"9da586aee519cc378dfd221165a2a85689989da55b9d792479090453dba8bba0",
                           "f68242ba22bd4799e705ac8821dce0e70d75a737b15c742a822ead0b2a2f4b59"}, []),
]

# Drives: the page Athena wrote on 2026-10-27 (no score, no events, no report) gives way to the seed's, while unchanged.
FIXES += [
    ("drives", "pages/index.html", {"29eca9eee9cbe919133c6332d4c4683ac01492981002be4e667b1ffda014be96",
                                    "90a1be165f041446cfff23968020ebc35a27ed55eb10e5305cbffdad14e10d80"},
     ["README.md", "pages/apollo.css", "pages/fonts/figtree.ttf", "pages/fonts/doto.ttf"]),
]


def run(src=SEED) -> list[str]:
    done = []
    for app, rel, old, needs in FIXES:
        d = apps.root() / app
        f = d / rel
        if not f.is_file() or hashlib.sha256(f.read_bytes()).hexdigest() not in old:
            continue
        try:
            for n in needs:
                if not (d / n).exists():
                    (d / n).parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(src / app / n, d / n)
            shutil.copyfile(src / app / rel, f)
            apps._changelog(d, "apollo", f"changed {rel}", "seed fix: the old seed's file, unchanged, replaced")
            done.append(f"{app}/{rel}")
        except OSError:
            logging.getLogger("apollo").exception("seed fix %s/%s not applied", app, rel)
    if done:
        apps._bump()
    return done
