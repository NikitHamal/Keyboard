/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikit.nepalikeyboard.BuildConfig
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.app.LocalNavController
import com.nikit.nepalikeyboard.app.Routes
import com.nikit.nepalikeyboard.clipboardManager
import com.nikit.nepalikeyboard.lib.compose.FlorisScreen
import com.nikit.nepalikeyboard.lib.util.launchUrl
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.compose.FlorisCanvasIcon
import org.florisboard.lib.compose.stringRes

@Composable
fun AboutScreen() = FlorisScreen {
    title = stringRes(R.string.about__title)

    val navController = LocalNavController.current
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()

    val appVersion = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

    content {
        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 24.dp)
        ) {
            FlorisCanvasIcon(
                modifier = Modifier.requiredSize(68.dp),
                iconId = R.mipmap.floris_app_icon,
                contentDescription = "Nepali Keyboard icon",
            )
            Text(
                text = "नेपाली किबोर्ड",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "Nepali Keyboard • $appVersion",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        PreferenceGroup(title = "एप र अद्यावधिक (App & Updates)") {
            Preference(
                icon = Icons.Filled.SystemUpdate,
                title = "नयाँ अपडेट जाँच गर्नुहोस् (Check Updates)",
                summary = "सिधै नयाँ संस्करण जाँच गरी स्थापना गर्नुहोस्",
                onClick = { navController.navigate(Routes.Settings.AppUpdates) },
            )
            Preference(
                icon = Icons.Outlined.Info,
                title = stringRes(R.string.about__version__title),
                summary = appVersion,
                onClick = {
                    try {
                        clipboardManager.addNewPlaintext(appVersion)
                        Toast.makeText(context, R.string.about__version_copied__title, Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        Toast.makeText(
                            context,
                            context.stringRes(R.string.about__version_copied__error, "error_message" to e.message),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            Preference(
                icon = Icons.Default.History,
                title = stringRes(R.string.about__changelog__title),
                summary = stringRes(R.string.about__changelog__summary),
                onClick = { context.launchUrl(R.string.florisboard__changelog_url, "version" to BuildConfig.VERSION_NAME) },
            )
            Preference(
                icon = Icons.Default.Code,
                title = stringRes(R.string.about__repository__title),
                summary = stringRes(R.string.about__repository__summary),
                onClick = { context.launchUrl(R.string.florisboard__repo_url) },
            )
            Preference(
                icon = Icons.Outlined.Policy,
                title = stringRes(R.string.about__privacy_policy__title),
                summary = stringRes(R.string.about__privacy_policy__summary),
                onClick = { context.launchUrl(R.string.florisboard__privacy_policy_url) },
            )
        }

        // -------------------------------------------------------------
        // Open Source Attribution & Heritage Card
        // -------------------------------------------------------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Open Source Attribution & Credits",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Nepali Keyboard is extended from and powered by the open-source FlorisBoard project (Apache-2.0, © The FlorisBoard Contributors). We gratefully acknowledge and credit the upstream contributors for the keyboard engine architecture.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        PreferenceGroup(title = "इजाजतपत्रहरू (Licenses)") {
            Preference(
                icon = Icons.Outlined.Description,
                title = stringRes(R.string.about__project_license__title),
                summary = stringRes(R.string.about__project_license__summary, "license_name" to "Apache 2.0"),
                onClick = { navController.navigate(Routes.Settings.ProjectLicense) },
            )
            Preference(
                icon = Icons.Outlined.Description,
                title = stringRes(id = R.string.about__third_party_licenses__title),
                summary = stringRes(id = R.string.about__third_party_licenses__summary),
                onClick = { navController.navigate(Routes.Settings.ThirdPartyLicenses) },
            )
        }
    }
}
