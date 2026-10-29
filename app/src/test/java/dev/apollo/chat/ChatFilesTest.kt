package dev.apollo.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ChatFilesTest {
    private val dir = Files.createTempDirectory("chat-uploads").toFile()

    @Test fun onlyNumericIdsInsideTheFolder() {
        assertEquals("12", ChatFiles.uploadFile(dir, "12")!!.name)
        for (id in listOf("../../no_backup/mnemosyne-stage/ready", "..", "1/2", "a1", "", "12/../13", "1".repeat(19))) assertNull(id, ChatFiles.uploadFile(dir, id))
    }
}
