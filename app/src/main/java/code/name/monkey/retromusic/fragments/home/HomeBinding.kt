/*
 * Modifications copyright (c) 2026 crunchbank (Shark Music): removed the greeting row; Shuffle button replaced by Unplayed.
 * Licensed under the GNU General Public License v3.
 */
package code.name.monkey.retromusic.fragments.home

import code.name.monkey.retromusic.databinding.FragmentHomeBinding

class HomeBinding(
    homeBinding: FragmentHomeBinding
) {
    val root = homeBinding.root
    val container = homeBinding.container
    val contentContainer = homeBinding.contentContainer
    val appBarLayout = homeBinding.appBarLayout
    val toolbar = homeBinding.appBarLayout.toolbar
    val lastAdded = homeBinding.homeContent.absPlaylists.lastAdded
    val topPlayed = homeBinding.homeContent.absPlaylists.topPlayed
    val unplayed = homeBinding.homeContent.absPlaylists.unplayed
    val history = homeBinding.homeContent.absPlaylists.history
    val recyclerView = homeBinding.homeContent.recyclerView
    val suggestions = homeBinding.homeContent.suggestions
}