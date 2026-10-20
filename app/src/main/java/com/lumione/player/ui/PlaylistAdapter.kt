package com.lumione.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.lumione.player.R
import com.lumione.player.data.Playlist

class PlaylistAdapter(
    private val playlists: MutableList<Playlist> = mutableListOf(),
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onPlaylistPlayClick: ((Playlist) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    fun setPlaylists(newPlaylists: List<Playlist>) {
        playlists.clear()
        playlists.addAll(newPlaylists)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_card, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.bind(playlist, onPlaylistClick, onPlaylistPlayClick)
    }

    override fun getItemCount(): Int = playlists.size

    class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.playlistTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.playlistSubtitle)
        private val artwork: ImageView = itemView.findViewById(R.id.playlistArtwork)
        private val fallback: ImageView = itemView.findViewById(R.id.playlistFallbackIcon)
        private val btnQuickPlay: ImageButton = itemView.findViewById(R.id.btnPlaylistQuickPlay)

        fun bind(
            playlist: Playlist,
            onClick: (Playlist) -> Unit,
            onPlayClick: ((Playlist) -> Unit)?
        ) {
            title.text = playlist.name
            val count = playlist.tracks.size
            subtitle.text = when {
                count == 0 -> "0 tracks • Tap to add songs"
                count == 1 -> "1 track"
                else -> "$count tracks"
            }

            val firstThumb = playlist.tracks.firstOrNull()?.thumbnailUrl
            if (!firstThumb.isNullOrBlank()) {
                fallback.visibility = View.GONE
                artwork.visibility = View.VISIBLE
                Glide.with(itemView.context)
                    .load(firstThumb)
                    .transform(CenterCrop(), RoundedCorners(14))
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(artwork)
            } else {
                artwork.visibility = View.GONE
                fallback.visibility = View.VISIBLE
            }

            itemView.setOnClickListener { onClick(playlist) }

            if (onPlayClick != null && playlist.tracks.isNotEmpty()) {
                btnQuickPlay.visibility = View.VISIBLE
                btnQuickPlay.setOnClickListener { onPlayClick(playlist) }
            } else {
                btnQuickPlay.visibility = View.VISIBLE
                btnQuickPlay.setOnClickListener { onClick(playlist) }
            }
        }
    }
}
