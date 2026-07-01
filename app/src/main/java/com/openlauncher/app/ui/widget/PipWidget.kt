package com.openlauncher.app.ui.widget

import android.app.ActivityView
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openlauncher.app.util.FileLogger

@Composable
fun PipWidget(
    packageName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activityViewInstance by remember { mutableStateOf<ActivityView?>(null) }
    var isReady by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf<Boolean?>(null) }
    var lastLaunchedPackage by remember { mutableStateOf("") }

    // Re-launch app whenever packageName changes OR when ActivityView becomes ready
    LaunchedEffect(packageName, isReady, activityViewInstance) {
        if (packageName.isNotEmpty() && isReady && activityViewInstance != null && packageName != lastLaunchedPackage) {
            try {
                FileLogger.log(context, "PIP: Attempting to launch package: $packageName")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activityViewInstance?.startActivity(intent)
                    FileLogger.log(context, "PIP: startActivity called for $packageName")
                    lastLaunchedPackage = packageName
                } else {
                    FileLogger.log(context, "PIP: Failed to get launch intent for $packageName")
                }
            } catch (e: Exception) {
                FileLogger.log(context, "PIP: Error launching app: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (supported == false) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "PIP not supported on this device.\n(ActivityView requires Android 10 and system-level permissions)",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else if (packageName.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Cast, null, tint = Color.Gray, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text("Select an app from sidebar", color = Color.Gray, fontSize = 11.sp)
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    try {
                        FileLogger.log(ctx, "PIP: Creating ActivityView...")
                        ActivityView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setCallback(object : ActivityView.StateCallback() {
                                override fun onActivityViewReady(view: ActivityView) {
                                    FileLogger.log(ctx, "PIP: ActivityView READY")
                                    activityViewInstance = view
                                    isReady = true
                                    supported = true
                                }

                                override fun onActivityViewDestroyed(view: ActivityView) {
                                    FileLogger.log(ctx, "PIP: ActivityView DESTROYED")
                                    isReady = false
                                    activityViewInstance = null
                                }

                                override fun onTaskMovedToFront(taskId: Int) {
                                    FileLogger.log(ctx, "PIP: Task moved to front: $taskId")
                                }
                            })
                        }
                    } catch (e: Throwable) {
                        FileLogger.log(ctx, "PIP: ActivityView constructor failed: ${e.message}")
                        supported = false
                        View(ctx)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    FileLogger.log(context, "PIP: Releasing ActivityView")
                    if (view is ActivityView) {
                        view.release()
                    }
                }
            )
            
            // If it's taking too long to be ready, it might be unsupported or failing silently
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(4000)
                if (supported == null) {
                    FileLogger.log(context, "PIP: Timeout reached waiting for onActivityViewReady")
                    supported = false
                }
            }
        }
    }
}
