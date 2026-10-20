package app.alcovaibe.vkmusicexport

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Only the disposable AVD's known framework dialogs may be dismissed. App ANRs fail. */
internal fun dismissKnownFrameworkDialog() {
    val root=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?:return
    val nodes=mutableListOf<AccessibilityNodeInfo>();val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    while(queue.isNotEmpty()) {
        val node=queue.removeFirst();nodes.add(node)
        repeat(node.childCount){node.getChild(it)?.let(queue::add)}
    }
    val caption=nodes.mapNotNull{it.text?.toString()}.firstOrNull{
        it.contains("responding")&&(it.contains("Quickstep")||it.contains("System UI"))
    }?:return
    val action=if(caption.contains("System UI"))"Wait" else "Close app"
    nodes.firstOrNull{it.text?.toString()==action}?.let{label->
        val button=if(label.isClickable)label else label.parent
        assertTrue("Known framework dialog action",button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
}

internal fun awaitTestWindowFocus(activity: Activity) {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val deadline=SystemClock.elapsedRealtime()+10000
    while(SystemClock.elapsedRealtime()<deadline) {
        dismissKnownFrameworkDialog()
        var focused=false
        instrumentation.runOnMainSync{focused=activity.hasWindowFocus()}
        if(focused)return
        SystemClock.sleep(50)
    }
    val root=instrumentation.uiAutomation.rootInActiveWindow
    assertTrue("Test window must be foreground; active package=${root?.packageName}; class=${root?.className}",false)
}

/** moveToState(RESUMED) alone does not guarantee that Android has foregrounded the task. */
internal fun restoreTestTaskToFront(activity: Activity) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        activity.startActivity(Intent(activity,activity.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    awaitTestWindowFocus(activity)
}
