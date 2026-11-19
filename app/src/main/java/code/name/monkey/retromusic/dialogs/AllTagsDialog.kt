/*
 * Copyright (c) 2026 crunchbank (Shark Music).
 *
 * This file is part of Shark Music, a modified version of Retro Music Player.
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.dialogs

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.core.text.inSpans
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.EXTRA_SONG
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.colorButtons
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.TagDump
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only view of everything jaudiotagger can read from a song file: the audio stream, every
 * standard tag field with all of its values, every field as stored, and embedded artwork.
 */
class AllTagsDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val song = BundleCompat.getParcelable(requireArguments(), EXTRA_SONG, Song::class.java)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = MaterialTextView(context).apply {
            setPadding(pad, pad / 2, pad, pad)
            setTextIsSelectable(true)
            textSize = 15f
            text = getString(R.string.tags_loading)
        }
        val scroll = ScrollView(context).apply {
            addView(
                text,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        lifecycleScope.launch {
            val sections = withContext(Dispatchers.IO) {
                song?.let { TagDump.read(context, File(it.data)) }
            }
            text.text = when {
                sections == null -> getString(R.string.tags_unreadable)
                sections.isEmpty() -> getString(R.string.tags_none)
                else -> render(sections, ThemeStore.accentColor(context))
            }
        }

        return materialDialog(R.string.all_tags)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .colorButtons()
    }

    private fun render(sections: List<TagDump.Section>, accent: Int): CharSequence =
        SpannableStringBuilder().apply {
            sections.forEachIndexed { index, section ->
                if (index > 0) append("\n\n")
                inSpans(
                    StyleSpan(Typeface.BOLD),
                    ForegroundColorSpan(accent),
                    RelativeSizeSpan(1.1f)
                ) { append(sectionTitle(section)) }
                for (entry in section.entries) {
                    append("\n\n")
                    inSpans(StyleSpan(Typeface.BOLD)) { append(entry.name) }
                    if (entry.values.size == 1) {
                        append("\n").append(display(entry.values[0]))
                    } else {
                        // Several values in one field (e.g. separate GENRE entries): one per line.
                        entry.values.forEach { append("\n• ").append(display(it)) }
                    }
                }
            }
        }

    private fun sectionTitle(section: TagDump.Section): String = when (section.kind) {
        TagDump.Kind.AUDIO -> getString(R.string.tag_section_audio)
        TagDump.Kind.TAGS -> getString(R.string.tag_section_tags, section.detail)
        TagDump.Kind.RAW -> getString(R.string.tag_section_raw)
        TagDump.Kind.ARTWORK -> getString(R.string.tag_section_artwork)
    }

    /** Long values (lyrics, base64 blobs) are cut short so the dialog stays usable. */
    private fun display(value: String): String =
        if (value.length <= MAX_SHOWN) value
        else value.take(MAX_SHOWN) + getString(R.string.tag_value_truncated, value.length)

    companion object {
        private const val MAX_SHOWN = 400
        const val TAG = "AllTagsDialog"

        fun create(song: Song): AllTagsDialog =
            AllTagsDialog().apply { arguments = bundleOf(EXTRA_SONG to song) }
    }
}
