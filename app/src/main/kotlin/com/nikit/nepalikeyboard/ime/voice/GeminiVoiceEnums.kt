/*
 * Copyright (C) 2026 Nikit Hamal / The FlorisBoard Contributors
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

package com.nikit.nepalikeyboard.ime.voice

/**
 * Operating mode for Gemini Voice input.
 */
enum class GeminiVoiceMode(val id: String) {
    /** Verbatim speech-to-text in the spoken language (Nepali or English). */
    TRANSCRIBE("transcribe"),

    /** Real-time speech-to-text translation into the target language. */
    TRANSLATE("translate");

    companion object {
        fun fromId(id: String): GeminiVoiceMode =
            entries.firstOrNull { it.id == id } ?: TRANSCRIBE
    }
}

/**
 * Translation language direction for Live Translate.
 */
enum class GeminiTranslateTarget(val id: String) {
    /** Spoken Nepali -> Written English. */
    NEPALI_TO_ENGLISH("ne_to_en"),

    /** Spoken English -> Written Nepali (Devanagari). */
    ENGLISH_TO_NEPALI("en_to_ne");

    companion object {
        fun fromId(id: String): GeminiTranslateTarget =
            entries.firstOrNull { it.id == id } ?: NEPALI_TO_ENGLISH
    }
}
