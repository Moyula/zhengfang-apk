package com.tyust.course.ui.system

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Paint the destination immediately; initialization must not occupy its transition frame. */
@Composable
fun InitialPageLoad(key: String, title: String, active: Boolean, transitionFinished: Boolean,
    prepare: suspend () -> Unit, content: @Composable () -> Unit) {
    val store = LocalPageDataState.current
    var entered by rememberPageData("page-entry:$key") { false }
    var prepared by remember(store, key) { mutableStateOf(false) }
    var error by remember(store, key) { mutableStateOf(false) }
    var retry by remember(store, key) { mutableIntStateOf(0) }
    val currentPrepare by rememberUpdatedState(prepare)
    LaunchedEffect(store, key, active, retry) {
        if (!active || entered || prepared) return@LaunchedEffect
        error = false
        try {
            val task = currentPrepare
            withContext(Dispatchers.IO) { task() }
            // A completed cache lookup must still let the lightweight shell draw.
            withFrameNanos { }; withFrameNanos { }
            prepared = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    LaunchedEffect(store, key, active, prepared, transitionFinished) {
        if (active && prepared && transitionFinished) entered = true
    }
    if (entered) content() else Column(Modifier.fillMaxSize().statusBarsPadding().testTag("page-loading:$title")) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (error) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("页面准备失败，请重试", style = MaterialTheme.typography.bodyMedium)
                SystemDialogButton(onClick = { retry++ }) { Text("重试") }
            } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassLoadingIndicator()
                Text("正在加载…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
