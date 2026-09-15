/*
 * Copyright (C) 2026 Nikit Hamal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nikit.nepalikeyboard.app.settings.about

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.lib.compose.FlorisScreen
import com.nikit.nepalikeyboard.update.UpdateManager
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * In-app update screen: checks GitHub Releases for a newer APK and installs
 * it without leaving the app.
 *
 * This screen is the only place in the whole application that uses the
 * network. The keyboard service itself never opens a socket — all typing,
 * transliteration, and lexicon lookup stay on-device.
 */
@Composable
fun AppUpdatesScreen() = FlorisScreen {
    title = stringRes(R.string.about__app_updates__title)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = UpdateManager.get(context)
    val state by updateManager.state.collectAsState()

    LaunchedEffect(updateManager) {
        updateManager.checkForUpdate()
    }

    content {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                is UpdateManager.State.Idle -> {
                    Text(
                        text = stringRes(R.string.about__app_updates__summary),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { scope.launch { updateManager.checkForUpdate() } }) {
                        Text(text = stringRes(R.string.update__check_now))
                    }
                }
                is UpdateManager.State.Checking -> {
                    Text(text = stringRes(R.string.update__checking))
                }
                is UpdateManager.State.UpToDate -> {
                    Text(text = stringRes(R.string.update__up_to_date))
                    OutlinedButton(onClick = {
                        updateManager.reset()
                        scope.launch { updateManager.checkForUpdate() }
                    }) {
                        Text(text = stringRes(R.string.update__check_again))
                    }
                }
                is UpdateManager.State.UpdateAvailable -> {
                    Text(
                        text = stringRes(R.string.update__available, "version" to s.version),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { updateManager.downloadApk(s.downloadUrl) } }) {
                            Text(text = stringRes(R.string.update__download))
                        }
                        OutlinedButton(onClick = { updateManager.reset() }) {
                            Text(text = stringRes(R.string.update__dismiss))
                        }
                    }
                }
                is UpdateManager.State.Downloading -> {
                    Text(
                        text = stringRes(R.string.update__downloading, "progress" to s.progress),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { s.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is UpdateManager.State.Downloaded -> {
                    Text(
                        text = stringRes(R.string.update__install),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = {
                        (context as? Activity)?.let { updateManager.installApk(it) }
                    }) {
                        Text(text = stringRes(R.string.update__install))
                    }
                }
                is UpdateManager.State.Installing -> {
                    Text(text = stringRes(R.string.update__installing))
                }
                is UpdateManager.State.Error -> {
                    Text(
                        text = stringRes(R.string.update__error, "message" to s.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = {
                        updateManager.reset()
                        scope.launch { updateManager.checkForUpdate() }
                    }) {
                        Text(text = stringRes(R.string.update__check_again))
                    }
                }
            }
        }
    }
}
