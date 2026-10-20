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
import com.lumione.player.queue.Track

class PlaylistTrackAdapter(
    private val tracks: MutableList<Track> = mutableListOf(),
    private val onTrackClick: (Track, Int) -> Unit,
    private val onRemoveClick: (Track, Int) -> Unit
) : RecyclerView.Adapter<PlaylistTrackAdapter.TrackViewHolder>() {

    fun setTracks(newTracks: List<Track>) {
        tracks.clear()
        tracks.addAll(newTracks)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.bind(track, position, onTrackClick, onRemoveClick)
    }

    override fun getItemCount(): Int = tracks.size

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val number: TextView = itemView.findViewById(R.id.playlistTrackNumber)
        private val artwork: ImageView = itemView.findViewById(R.id.playlistTrackArtwork)
        private val title: TextView = itemView.findViewById(R.id.playlistTrackTitle)
        private val artist: TextView = itemView.findViewById(R.id.playlistTrackArtist)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemoveFromPlaylist)

        fun bind(
            track: Track,
            position: Int,
            onClick: (Track, Int) -> Unit,
            onRemove: (Track, Int) -> Unit
        ) {
            number.text = (position + 1).toString()
            title.text = track.title
            val durationStr = formatDuration(track.durationMs)
            artist.text = if (durationStr.isNotBlank()) {
                "${track.artist} • $durationStr"
            } else {
                track.artist
            }

            if (track.thumbnailUrl.isNotBlank()) {
                Glide.with(itemView.context)
                    .load(track.thumbnailUrl)
                    .transform(CenterCrop(), RoundedCorners(10))
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(artwork)
            } else {
                artwork.setImageResource(R.drawable.bg_album_art_placeholder)
            }

            itemView.setOnClickListener { onClick(track, position) }
            btnRemove.setOnClickListener { onRemove(track, position) }
        }

        private fun formatDuration(ms: Long): String {
            if (ms <= 0L) return ""
            val sec = ms / 1000
            return "%d:%02d".format(sec / 60, sec % 60)
        }
    }
}
