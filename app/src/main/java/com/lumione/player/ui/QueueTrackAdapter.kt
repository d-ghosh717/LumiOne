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

class QueueTrackAdapter(
    private val tracks: MutableList<Track> = mutableListOf(),
    private val onTrackClick: (Track, Int) -> Unit,
    private val onRemoveClick: ((Track, Int) -> Unit)? = null
) : RecyclerView.Adapter<QueueTrackAdapter.QueueViewHolder>() {

    private var currentPlayingId: String? = null

    fun setTracks(newTracks: List<Track>) {
        tracks.clear()
        tracks.addAll(newTracks)
        notifyDataSetChanged()
    }

    fun setCurrentPlayingId(videoId: String?) {
        currentPlayingId = videoId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_track, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val track = tracks[position]
        holder.bind(
            track = track,
            isPlaying = (track.videoId == currentPlayingId),
            onClick = { onTrackClick(track, position) },
            onRemove = onRemoveClick?.let { callback -> { callback(track, position) } }
        )
    }

    override fun getItemCount(): Int = tracks.size

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: View = itemView.findViewById(R.id.queueItemContainer)
        private val activeBar: View = itemView.findViewById(R.id.queueActiveBar)
        private val thumbnail: ImageView = itemView.findViewById(R.id.queueThumbnail)
        private val title: TextView = itemView.findViewById(R.id.queueTrackTitle)
        private val artist: TextView = itemView.findViewById(R.id.queueTrackArtist)
        private val duration: TextView = itemView.findViewById(R.id.queueDuration)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btnQueueRemove)

        fun bind(
            track: Track,
            isPlaying: Boolean,
            onClick: () -> Unit,
            onRemove: (() -> Unit)?
        ) {
            title.text = track.title
            artist.text = track.artist
            duration.text = formatDuration(track.durationMs)

            activeBar.visibility = if (isPlaying) View.VISIBLE else View.GONE
            container.setBackgroundResource(
                if (isPlaying) R.drawable.bg_active_track_pill else R.drawable.bg_track_item_normal
            )

            if (track.thumbnailUrl.isNotBlank()) {
                Glide.with(itemView.context)
                    .load(track.thumbnailUrl)
                    .transform(CenterCrop(), RoundedCorners(16))
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(thumbnail)
            } else {
                thumbnail.setImageResource(R.drawable.bg_album_art_placeholder)
            }

            if (onRemove != null) {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener { onRemove() }
            } else {
                btnRemove.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick() }
        }

        private fun formatDuration(ms: Long): String {
            if (ms <= 0L) return ""
            val sec = ms / 1000
            return "%d:%02d".format(sec / 60, sec % 60)
        }
    }
}
