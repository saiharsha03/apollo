package dev.apollo.mnemosyne

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class StageSealTest {
    private val key = Random(1).nextBytes(32)

    private fun stage() = Files.createTempDirectory("stage").toFile().also { s ->
        File(s, "db").mkdirs(); File(s, "db/approvals.db").writeText("rules"); File(s, "manifest.json").writeText("{}")
    }

    @Test fun onlyTheSealedStageApplies() {
        val s = stage()
        val seal = StageSeal.of(s, key)
        File(s, StageSeal.READY).writeText(seal)
        assertTrue(StageSeal.ok(s, key, File(s, StageSeal.READY).readText()))
        assertFalse(StageSeal.ok(s, Random(2).nextBytes(32), seal))                 // planted without the key
        File(s, "db/approvals.db").writeText("every action FREE")
        assertFalse(StageSeal.ok(s, key, seal))                                      // a swapped file
        File(s, "db/approvals.db").writeText("rules"); File(s, "prefs").mkdirs(); File(s, "prefs/x.xml").writeText("x")
        assertFalse(StageSeal.ok(s, key, seal))                                      // an added file
        assertFalse(StageSeal.ok(stage(), key, "ready"))                             // a bare ready file
    }
}
