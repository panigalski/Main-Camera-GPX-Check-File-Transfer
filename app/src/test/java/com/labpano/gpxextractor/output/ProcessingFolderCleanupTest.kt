package com.labpano.gpxextractor.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessingFolderCleanupTest {
    @Test
    fun removesOnlyEmptyLegacyProcessingFoldersInsideDateFolders() {
        val root = createTempDir(prefix = "processing-cleanup-")
        try {
            val emptyProcessing = File(root, "17-08-2026/PROCESSING").apply { mkdirs() }
            val nonEmptyProcessing = File(root, "18-08-2026/PROCESSING").apply { mkdirs() }
            File(nonEmptyProcessing, "keep.tmp").writeText("in-use")
            val unrelated = File(root, "PROCESSING").apply { mkdirs() }

            assertEquals(1, ProcessingFolderCleanupPolicy.cleanupLocal(root))
            assertFalse(emptyProcessing.exists())
            assertTrue(nonEmptyProcessing.isDirectory)
            assertTrue(unrelated.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
