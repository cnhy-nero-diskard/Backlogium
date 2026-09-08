package com.example.backlogium.work.setup

import com.example.backlogium.data.repo.HltbDatasetProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HltbDatasetWorkerTest {

    @Test
    fun downloadingProgressIsPersistedAsASetupFriendlySnapshot() {
        val data = HltbDatasetProgress.Downloading(bytesRead = 12, totalBytes = 100).toWorkData()

        assertEquals(12, data.getInt(HltbDatasetWorker.KEY_PROCESSED, 0))
        assertEquals(100, data.getInt(HltbDatasetWorker.KEY_TOTAL, 0))
        assertEquals("Downloading completion times", data.getString(HltbDatasetWorker.KEY_LABEL))
    }

    @Test
    fun nonDownloadStagesRemainIndeterminateButExplainWhatIsHappening() {
        val checking = HltbDatasetProgress.Checking.toWorkData()
        val applying = HltbDatasetProgress.Applying.toWorkData()

        assertEquals(0, checking.getInt(HltbDatasetWorker.KEY_TOTAL, -1))
        assertEquals("Checking for a completion-times dataset", checking.getString(HltbDatasetWorker.KEY_LABEL))
        assertEquals("Applying completion times", applying.getString(HltbDatasetWorker.KEY_LABEL))
        assertNull(applying.getString(HltbDatasetWorker.KEY_FAILURE_REASON))
    }
}