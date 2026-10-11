package com.tvatlas.player.ui
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.tvatlas.player.transfer.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
class PhoneTransferTest {
    @get:Rule val compose = createComposeRule()
    @Test fun playlistScanEntryReturnsToPreservedInputWithoutSaving() {
        compose.setContent { MaterialTheme { PlaylistDialog(false, {}) { _, _ -> fail("Should await confirmation") } } }
        compose.onNodeWithText("名称").performTextInput("频道列表")
        compose.onNodeWithText("手机扫码输入").performClick()
        compose.onNodeWithText("手机扫码输入频道订阅").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNode(hasSetTextAction() and hasText("频道列表")).assertExists()
    }
    @Test fun proxySubscriptionHasItsOwnScanEntry() {
        compose.setContent { MaterialTheme { SubscriptionDialog(false, {}) { _, _ -> fail("Should await confirmation") } } }
        compose.onNodeWithText("手机扫码输入").performClick()
        compose.onNodeWithText("手机扫码输入代理订阅").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("下载并导入").assertIsNotEnabled()
    }
    @Test fun qrRoundTripsAndPhoneSubmissionReachesUiWithCompleteToken() {
        PhoneTransferSession(InetAddress.getByName("127.0.0.1"), TransferKind.PROXY).use { session ->
            val bitmap = phoneQrBitmap(session.pageUrl)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
            assertEquals(session.pageUrl, decoded.text)
            var received: TransferInput? = null
            compose.setContent { MaterialTheme { PhoneTransferDialog(TransferKind.PROXY, {},
                sessionFactory = { _, _ -> session }, onReceive = { received = it }) } }
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("手机输入订阅地址的临时页面二维码").fetchSemanticsNodes().isNotEmpty() }
            val url = "https://example.com/sub?token=fixture%2Bvalue&format=clash+yaml"
            val request = Request.Builder().url(decoded.text).post(FormBody.Builder().add("name", "手机输入").add("url", url).build()).build()
            OkHttpClient().newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertFalse(response.body!!.string().contains(url))
            }
            compose.waitUntil(5000) { received != null }
            compose.runOnIdle { assertEquals(TransferInput("手机输入", url), received) }
        }
    }
}
