package com.tvatlas.player

import com.tvatlas.core.cloud.*
import com.tvatlas.player.cloud.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class CloudLibraryControllerTest {
    private val share = CloudShare(CloudProvider.PAN115, "sExampleShare", name = "测试合集")
    private class MemoryStore(share: CloudShare) : CloudShareStorage {
        override val items = MutableStateFlow(listOf(share))
        override val damaged = false
        override fun save(share: CloudShare) { items.value = listOf(share) }
        override fun remove(key: String) { items.value = items.value.filterNot { it.key == key } }
    }
    private suspend fun settled(controller: CloudLibraryController) {
        withTimeout(3000) { while (controller.state.value.loading || controller.state.value.shares.isEmpty()) delay(5) }
    }
    @Test fun failedPaginationRetainsFilesAndCanRetrySamePage() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val calls = mutableListOf<Int>(); var fail = true
            val controller = CloudLibraryController(scope, { MemoryStore(share) }, CloudDirectoryReader { _, _, offset ->
                calls += offset
                if (offset == 0) CloudDirectory(listOf(CloudFile("v2", "E2.mp4", false)), 100)
                else if (fail) throw CloudException(CloudFailure.NETWORK, "目录读取失败")
                else CloudDirectory(listOf(CloudFile("v2", "E2.mp4", false), CloudFile("v10", "E10.mp4", false)))
            }, {})
            settled(controller)
            controller.open(share); settled(controller)
            controller.more(); settled(controller)
            assertEquals(100, controller.state.value.nextOffset)
            assertEquals(listOf("E2.mp4"), controller.state.value.files.map { it.name })
            assertNotNull(controller.state.value.error)
            fail = false; controller.more(); settled(controller)
            assertEquals(listOf(0, 100, 100), calls)
            assertEquals(listOf("E2.mp4", "E10.mp4"), controller.state.value.files.map { it.name })
            assertNull(controller.state.value.nextOffset); assertNull(controller.state.value.error)
        } finally { scope.cancel() }
    }
    @Test fun backUsesLoadedParentButExplicitRefreshFetchesAgain() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val calls = mutableListOf<String>()
            val folder = CloudFile("folder", "第一季", true)
            val controller = CloudLibraryController(scope, { MemoryStore(share) }, CloudDirectoryReader { _, parent, _ ->
                calls += parent
                CloudDirectory(if (parent == "0") listOf(folder) else listOf(CloudFile("video", "E01.mp4", false)))
            }, {})
            settled(controller); controller.open(share); settled(controller)
            controller.enter(folder); settled(controller)
            controller.back(); settled(controller)
            assertEquals(listOf("0", "folder"), calls)
            assertEquals(listOf(folder), controller.state.value.files)
            controller.refresh(); settled(controller)
            assertEquals(listOf("0", "folder", "0"), calls)
        } finally { scope.cancel() }
    }
    @Test fun cancelledOldDirectoryCannotReplaceNewSelection() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val old = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
            val other = share.copy(shareId = "sOtherExample", name = "另一个合集")
            val controller = CloudLibraryController(scope, { MemoryStore(share) }, CloudDirectoryReader { selected, _, _ ->
                if (selected == share) { started.complete(Unit); withContext(NonCancellable) { old.await() } }
                CloudDirectory(listOf(CloudFile(selected.shareId, selected.name, false)))
            }, {})
            settled(controller); controller.open(share); started.await()
            controller.open(other); settled(controller)
            old.complete(Unit); delay(30)
            assertEquals(other, controller.state.value.selected)
            assertEquals(listOf("另一个合集"), controller.state.value.files.map { it.name })
        } finally { scope.cancel() }
    }
}
