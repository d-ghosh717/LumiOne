package com.lumione.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.lumione.player.R
import com.lumione.player.search.SearchResult

class FeaturedCarouselAdapter(
    val items: MutableList<SearchResult>,
    private var currentPlayingId: String? = null,
    private var isPlaying: Boolean = false,
    private val onItemClick: (SearchResult, Int) -> Unit
) : RecyclerView.Adapter<FeaturedCarouselAdapter.CarouselViewHolder>() {

    fun updateItems(newItems: List<SearchResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updatePlaybackState(playingId: String?, playing: Boolean) {
        val oldId = currentPlayingId
        val oldPlaying = isPlaying
        currentPlayingId = playingId
        isPlaying = playing

        if (oldId != playingId || oldPlaying != playing) {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_featured_carousel, parent, false)
        return CarouselViewHolder(view)
    }

    override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item.videoId == currentPlayingId && isPlaying) {
            onItemClick(item, position)
        }
    }

    override fun getItemCount(): Int = items.size

    class CarouselViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val artwork: ImageView = itemView.findViewById(R.id.carouselArtwork)
        val title: TextView = itemView.findViewById(R.id.carouselTitle)
        val artist: TextView = itemView.findViewById(R.id.carouselArtist)
        val playBtn: FrameLayout = itemView.findViewById(R.id.carouselPlayBtn)
        val playIcon: ImageView = itemView.findViewById(R.id.carouselPlayIcon)

        fun bind(item: SearchResult, isItemPlaying: Boolean, onClick: () -> Unit) {
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

            playIcon.setImageResource(if (isItemPlaying) R.drawable.ic_pause else R.drawable.ic_play)

            itemView.setOnClickListener { onClick() }
            playBtn.setOnClickListener { onClick() }
        }
    }
}
