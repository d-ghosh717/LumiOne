package com.lumione.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lumione.player.R
import com.lumione.player.data.Playlist

class DialogPlaylistAdapter(
    private val playlists: List<Playlist>,
    private val onPlaylistSelected: (Playlist) -> Unit
) : RecyclerView.Adapter<DialogPlaylistAdapter.DialogPlaylistViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DialogPlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dialog_playlist, parent, false)
        return DialogPlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: DialogPlaylistViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.bind(playlist, onPlaylistSelected)
    }

    override fun getItemCount(): Int = playlists.size

    class DialogPlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.dialogPlaylistName)
        private val count: TextView = itemView.findViewById(R.id.dialogPlaylistTrackCount)

        fun bind(playlist: Playlist, onSelect: (Playlist) -> Unit) {
            name.text = playlist.name
            val size = playlist.tracks.size
            count.text = if (size == 1) "1 track" else "$size tracks"
            itemView.setOnClickListener { onSelect(playlist) }
        }
    }
}
