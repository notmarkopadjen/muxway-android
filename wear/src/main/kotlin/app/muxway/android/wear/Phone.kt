package app.muxway.android.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import androidx.concurrent.futures.await

/** The phone that runs the app, found by the capability it announces. */
object Phone {
    private const val CAPABILITY = "muxway_phone"

    suspend fun nodeId(context: Context): String? = try {
        val info = Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
        val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        BoardStore.savePhone(context, node != null)
        node?.id
    } catch (e: Exception) {
        null
    }

    suspend fun reachable(context: Context): Boolean = nodeId(context) != null

    /** Asks the phone to open the pane: the companion starts the app's browsable link. */
    suspend fun open(context: Context, address: String): String {
        val node = nodeId(context) ?: return "no phone"
        val link = Uri.Builder().scheme("muxway").authority("open").appendQueryParameter("pane", address).build()
        val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(link)
        return try {
            RemoteActivityHelper(context).startRemoteActivity(intent, node).await()
            "opened on the phone"
        } catch (e: Exception) {
            "the phone did not answer"
        }
    }
}
