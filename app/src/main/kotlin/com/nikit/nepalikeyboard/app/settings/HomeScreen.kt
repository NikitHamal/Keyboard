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

package com.nikit.nepalikeyboard.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikit.nepalikeyboard.BuildConfig
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.app.LocalNavController
import com.nikit.nepalikeyboard.app.Routes
import com.nikit.nepalikeyboard.lib.compose.FlorisScreen
import com.nikit.nepalikeyboard.lib.compose.LocalPreviewFieldController
import com.nikit.nepalikeyboard.lib.util.InputMethodUtils
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import org.florisboard.lib.compose.FlorisCanvasIcon
import org.florisboard.lib.compose.stringRes

@Composable
fun HomeScreen() = FlorisScreen {
    title = stringRes(R.string.settings__home__title)
    navigationIconVisible = false
    previewFieldVisible = true

    val navController = LocalNavController.current
    val context = LocalContext.current
    val previewController = LocalPreviewFieldController.current

    content {
        val isFlorisBoardEnabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
        val isFlorisBoardSelected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)

        // -------------------------------------------------------------
        // Nepali Keyboard Hero Card
        // -------------------------------------------------------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF8F1D2C), // Nepali Crimson
                                Color(0xFF5E0C17)  // Deep Maroon
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FlorisCanvasIcon(
                            modifier = Modifier.requiredSize(52.dp),
                            iconId = R.mipmap.floris_app_icon,
                            contentDescription = "Nepali Keyboard icon",
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "नेपाली किबोर्ड",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = "Nepali Keyboard • v${BuildConfig.VERSION_NAME}",
                                fontSize = 13.sp,
                                color = Color(0xFFFFDDB1), // Himalayan Gold tint
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Status Indicator & Action
                    if (isFlorisBoardEnabled && isFlorisBoardSelected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    Color(0xFF2E7D32).copy(alpha = 0.35f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF81C784),
                                modifier = Modifier.requiredSize(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "सक्रिय छ • Ready to Type",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    } else if (!isFlorisBoardEnabled) {
                        Button(
                            onClick = { InputMethodUtils.showImeEnablerActivity(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "१. नेपाली किबोर्ड सक्रिय गर्नुहोस् (Enable)",
                                color = Color(0xFF291800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = { InputMethodUtils.showImePicker(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "२. मुख्य किबोर्डको रूपमा छान्नुहोस् (Select)",
                                color = Color(0xFF291800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Quick Typing Test Card
        // -------------------------------------------------------------
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            onClick = { previewController?.focus() }
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "यहाँ टाइप गर्नुहोस् (Test Keyboard)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Tap to open",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "रोमनाइज्ड च्याट (xa, hunxa, vayo, nepalma) र सुझावहरू परीक्षण गर्नुहोस्।",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // -------------------------------------------------------------
        // Feature Settings Groups
        // -------------------------------------------------------------
        PreferenceGroup(title = "टाइपिङ र भाषाहरू (Languages & Typing)") {
            Preference(
                icon = Icons.Default.Language,
                title = "भाषाहरू र लेआउट (Languages)",
                summary = "रोमनाइज्ड, परम्परागत देवनागरी, अङ्ग्रेजी, हिन्दी",
                onClick = { navController.navigate(Routes.Settings.Localization) },
            )
            Preference(
                icon = Icons.Default.Spellcheck,
                title = "स्मार्ट च्याट र सुझाव (Transliteration)",
                summary = "रोमनाइज्ड च्याट, सस्वत शब्द सुझाव र शुद्धता",
                onClick = { navController.navigate(Routes.Settings.Typing) },
            )
            Preference(
                icon = Icons.Outlined.Palette,
                title = "थिम र रङहरू (Themes)",
                summary = "नेपाली क्रिमसन, डे/नाइट, एमोलेड डार्क",
                onClick = { navController.navigate(Routes.Settings.Theme) },
            )
            Preference(
                icon = Icons.Outlined.Keyboard,
                title = "किबोर्ड र कुञ्जीहरू (Keyboard Layout)",
                summary = "किबोर्ड उचाइ, नम्बर पङ्क्ति, पपअप अक्षरहरू",
                onClick = { navController.navigate(Routes.Settings.Keyboard) },
            )
        }

        PreferenceGroup(title = "टुल्स र सुविधा (Tools & Features)") {
            Preference(
                icon = Icons.Default.SmartButton,
                title = "स्मार्ट सुझाव पट्टी (Smartbar)",
                summary = "सुझाव पट्टी, इमोजी र द्रुत बटनहरू",
                onClick = { navController.navigate(Routes.Settings.Smartbar) },
            )
            Preference(
                icon = Icons.Default.Gesture,
                title = "स्वाइप र कम्पन (Gestures & Haptics)",
                summary = "ग्लाइड टाइपिङ, कम्पन प्रतिक्रिया, ध्वनि",
                onClick = { navController.navigate(Routes.Settings.Gestures) },
            )
            Preference(
                icon = Icons.AutoMirrored.Outlined.Assignment,
                title = "क्लिपबोर्ड (Clipboard)",
                summary = "क्लिपबोर्ड इतिहास र सुरक्षित पिनहरू",
                onClick = { navController.navigate(Routes.Settings.Clipboard) },
            )
            Preference(
                icon = Icons.Default.SentimentSatisfiedAlt,
                title = "इमोजी र मिडिया (Emojis)",
                summary = "इमोजी खोज, हालै प्रयोग गरिएका इमोजी",
                onClick = { navController.navigate(Routes.Settings.Media) },
            )
            Preference(
                icon = Icons.Default.Extension,
                title = stringRes(R.string.ext__home__title),
                onClick = { navController.navigate(Routes.Ext.Home) },
            )
            Preference(
                icon = Icons.Outlined.Build,
                title = stringRes(R.string.settings__other__title),
                onClick = { navController.navigate(Routes.Settings.Other) },
            )
            Preference(
                icon = Icons.Outlined.Info,
                title = "नेपाली किबोर्डको बारेमा (About)",
                summary = "v${BuildConfig.VERSION_NAME} • अपडेट जाँच • खुला स्रोत श्रेय",
                onClick = { navController.navigate(Routes.Settings.About) },
            )
        }
    }
}
