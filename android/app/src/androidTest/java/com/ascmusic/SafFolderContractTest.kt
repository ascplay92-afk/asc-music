package com.ascmusic

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafFolderContractTest {
    @Test
    fun folderPickerRequestsWholeTreeSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ActivityResultContracts.OpenDocumentTree().createIntent(context, null)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
    }
}
