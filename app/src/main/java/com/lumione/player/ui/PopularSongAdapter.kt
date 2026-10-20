package com.lumione.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.lumione.player.R
import com.lumione.player.search.SearchResult

class PopularSongAdapter(
    val items: MutableList<SearchResult>,
    private val onItemClick: (SearchResult, Int) -> Unit
) : RecyclerView.Adapter<PopularSongAdapter.PopularViewHolder>() {

    fun updateItems(newItems: List<SearchResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PopularViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_popular_song, parent, false)
        return PopularViewHolder(view)
    }

    override fun onBindViewHolder(holder: PopularViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item) {
            onItemClick(item, position)
        }
    }

    override fun getItemCount(): Int = items.size

    class PopularViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val artwork: ImageView = itemView.findViewById(R.id.popularArtwork)
        val title: TextView = itemView.findViewById(R.id.popularTitle)
        val artist: TextView = itemView.findViewById(R.id.popularArtist)

        fun bind(item: SearchResult, onClick: () -> Unit) {
            title.text = item.title
            artist.text = item.artist

            if (item.thumbnailUrl.isNotBlank()) {
                val cleanUrl = if (item.thumbnailUrl.contains("/hqdefault.jpg")) {
                    item.thumbnailUrl.replace("/hqdefault.jpg", "/mqdefault.jpg")
                } else {
                    item.thumbnailUrl
                }
                Glide.with(itemView.context)
                    .load(cleanUrl)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(artwork)
            } else {
                artwork.setImageResource(R.drawable.bg_album_art_placeholder)
            }

            itemView.setOnClickListener { onClick() }
        }
    }
}
