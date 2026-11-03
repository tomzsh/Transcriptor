package id.tomz.transcriptor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.inference.IInferenceService
import id.tomz.transcriptor.inference.NativeInferenceService
import id.tomz.transcriptor.inference.NativeInferenceWorker
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeInferenceIsolationTest {
    @Test fun workerDeathLeavesAppAliveAndCanStartAnotherSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, NativeInferenceService::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertEquals("${context.packageName}:inference", info.processName)
        val mainPid = Process.myPid()
        val binder = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
        try {
            val remote = withTimeout(20_000) { binder.await() }
            val died = CompletableDeferred<Unit>()
            remote.linkToDeath({ died.complete(Unit) }, 0)
            IInferenceService.Stub.asInterface(remote).cancel()
            withTimeout(10_000) { died.await() }
            assertEquals(mainPid, Process.myPid())
        } finally { context.unbindService(connection) }
        // Validation happens in the restarted service, without needing a model download.
        val worker = NativeInferenceWorker(context)
        worker.withSession { session ->
            repeat(2) {
                try {
                    session.generate("${context.filesDir}/missing.gguf", "Translate", "Hello") {}
                    fail("Missing models must report an error")
                } catch (error: IOException) {
                    assertFalse(error.message.orEmpty().contains("lain masih aktif"))
                }
            }
        }
    }
}
