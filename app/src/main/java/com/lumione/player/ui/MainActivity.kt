package com.lumione.player.ui

import android.content.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.lumione.player.R
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.RepeatMode
import com.lumione.player.queue.Track
import com.lumione.player.search.SearchEngine
import com.lumione.player.search.SearchResult
import com.lumione.player.service.PlaybackService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), PlaybackService.PlaybackServiceListener {

    private var playbackService: PlaybackService? = null
    private var isBound = false
    private val searchEngine = SearchEngine()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var searchInput: EditText
    private lateinit var searchResultsList: RecyclerView
    private lateinit var sectionTitle: TextView
    private lateinit var btnSeeAll: TextView
    private lateinit var miniPlayerCard: View
    private lateinit var miniTrackTitle: TextView
    private lateinit var miniArtistName: TextView
    private lateinit var miniPlayPauseBtn: ImageButton
    private lateinit var miniProgressBar: ProgressBar
    private lateinit var miniAlbumArtView: ImageView
    private lateinit var fullPlayerContainer: View
    private lateinit var fullTrackTitle: TextView
    private lateinit var fullArtistName: TextView
    private lateinit var fullPlayPauseBtn: ImageButton
    private lateinit var fullSeekBar: SeekBar
    private lateinit var fullCurrentTime: TextView
    private lateinit var fullDuration: TextView
    private lateinit var fullAlbumArtView: ImageView
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnLike: ImageButton
    private var isLiked = false
    private lateinit var trackAdapter: TrackAdapter

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LumiBinder
            playbackService = binder.getService()
            playbackService?.setListener(this@MainActivity)
            isBound = true
            syncUIWithService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        bindViews()
        setupSearchUI()
        setupPlayerUI()
        startAndBindService()
        loadTrendingTracks()
    }

    override fun onResume() {
        super.onResume()
        if (!isBound) startAndBindService()
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        mainScope.cancel()
        if (isBound) {
            playbackService?.setListener(null)
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    // ─── View Binding ─────────────────────────────────────────────────────────

    private fun bindViews() {
        searchInput = findViewById(R.id.searchInput)
        searchResultsList = findViewById(R.id.searchResultsList)
        sectionTitle = findViewById(R.id.sectionTitle)
        btnSeeAll = findViewById(R.id.btnSeeAll)
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        miniTrackTitle = findViewById(R.id.miniTrackTitle)
        miniArtistName = findViewById(R.id.miniArtistName)
        miniPlayPauseBtn = findViewById(R.id.miniPlayPauseBtn)
        miniProgressBar = findViewById(R.id.miniProgressBar)
        miniAlbumArtView = findViewById(R.id.miniAlbumArtView)
        fullPlayerContainer = findViewById(R.id.fullPlayerContainer)
        fullTrackTitle = findViewById(R.id.fullTrackTitle)
        fullArtistName = findViewById(R.id.fullArtistName)
        fullPlayPauseBtn = findViewById(R.id.fullPlayPauseBtn)
        fullSeekBar = findViewById(R.id.fullSeekBar)
        fullCurrentTime = findViewById(R.id.fullCurrentTime)
        fullDuration = findViewById(R.id.fullDuration)
        fullAlbumArtView = findViewById(R.id.fullAlbumArtView)
        btnNext = findViewById(R.id.btnNext)
        btnPrev = findViewById(R.id.btnPrev)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnBack = findViewById(R.id.btnBack)
        btnSettings = findViewById(R.id.btnSettings)
        btnLike = findViewById(R.id.btnLike)

        trackAdapter = TrackAdapter(mutableListOf()) { result, position ->
            val track = result.toTrack()
            playbackService?.let { svc ->
                svc.queueManager.setQueue(
                    buildQueueFromResults(trackAdapter.results, position),
                    0
                )
                svc.loadAndPlay(track)
            }
            showFullPlayer()
        }
        searchResultsList.layoutManager = LinearLayoutManager(this)
        searchResultsList.isNestedScrollingEnabled = false
        searchResultsList.adapter = trackAdapter
    }

    // ─── Service ──────────────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ─── Search UI ────────────────────────────────────────────────────────────

    private fun setupSearchUI() {
        searchInput.setOnEditorActionListener { _, _, _ ->
            val query = searchInput.text.toString().trim()
            if (query.isNotBlank()) {
                performSearch(query)
            } else {
                loadTrendingTracks()
            }
            true
        }

        btnSeeAll.setOnClickListener {
            loadTrendingTracks()
        }
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return
        sectionTitle.text = "Results for \"$query\""
        mainScope.launch {
            val results = withContext(Dispatchers.IO) { searchEngine.search(query) }
            trackAdapter.updateResults(results)
        }
    }

    private fun loadTrendingTracks() {
        sectionTitle.text = "🔥 Trending on Audius"
        mainScope.launch {
            val results = withContext(Dispatchers.IO) { searchEngine.getTrending() }
            if (results.isNotEmpty()) {
                trackAdapter.updateResults(results)
            }
        }
    }

    private fun buildQueueFromResults(results: List<SearchResult>, startAt: Int): List<Track> {
        if (results.isEmpty()) return emptyList()
        val safeIndex = startAt.coerceIn(0, results.lastIndex)
        val reordered = results.subList(safeIndex, results.size) +
                        results.subList(0, safeIndex)
        return reordered.map { it.toTrack() }
    }

    // ─── Player UI ────────────────────────────────────────────────────────────

    private fun setupPlayerUI() {
        // Mini player click → open full player
        val openFullPlayer = View.OnClickListener { showFullPlayer() }
        miniPlayerCard.setOnClickListener(openFullPlayer)
        miniAlbumArtView.setOnClickListener(openFullPlayer)
        miniTrackTitle.setOnClickListener(openFullPlayer)
        miniArtistName.setOnClickListener(openFullPlayer)
        btnBack.setOnClickListener { hideFullPlayer() }

        btnSettings.setOnClickListener {
            Toast.makeText(this, "LumiOne Music • Streamed natively from Audius", Toast.LENGTH_SHORT).show()
        }

        btnLike.setOnClickListener {
            isLiked = !isLiked
            btnLike.setColorFilter(
                if (isLiked) getColor(R.color.accent_fuchsia) else getColor(R.color.primary_lavender)
            )
        }

        miniPlayPauseBtn.setOnClickListener { togglePlayPause() }
        fullPlayPauseBtn.setOnClickListener { togglePlayPause() }

        btnNext.setOnClickListener { playbackService?.skipNext() }
        btnPrev.setOnClickListener { playbackService?.skipPrevious() }

        btnShuffle.setOnClickListener {
            val svc = playbackService ?: return@setOnClickListener
            svc.queueManager.shuffleEnabled = !svc.queueManager.shuffleEnabled
            btnShuffle.alpha = if (svc.queueManager.shuffleEnabled) 1.0f else 0.4f
        }

        btnRepeat.setOnClickListener {
            val svc = playbackService ?: return@setOnClickListener
            svc.queueManager.repeatMode = when (svc.queueManager.repeatMode) {
                RepeatMode.NONE -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.NONE
            }
            updateRepeatIcon()
        }

        fullSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var dragging = false
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && dragging) {
                    val svc = playbackService ?: return
                    val target = (progress.toLong() * svc.durationMs) / 1000L
                    fullCurrentTime.text = formatMs(target)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                val svc = playbackService ?: return
                val target = (sb!!.progress.toLong() * svc.durationMs) / 1000L
                svc.seekTo(target)
            }
        })
    }

    private fun togglePlayPause() {
        val svc = playbackService ?: return
        if (svc.isPlaying) svc.pausePlayback() else svc.resumePlayback()
    }

    private fun showFullPlayer() {
        fullPlayerContainer.alpha = 1f
        fullPlayerContainer.visibility = View.VISIBLE
        fullPlayerContainer.bringToFront()
    }

    private fun hideFullPlayer() {
        fullPlayerContainer.visibility = View.GONE
    }

    private fun syncUIWithService() {
        val svc = playbackService ?: return
        svc.queueManager.currentTrack()?.let { updateTrackUI(it) }
        updatePlayPauseIcons(svc.isPlaying)
    }

    private fun updateRepeatIcon() {
        val svc = playbackService ?: return
        btnRepeat.alpha = if (svc.queueManager.repeatMode != RepeatMode.NONE) 1.0f else 0.4f
    }

    // ─── PlaybackServiceListener ──────────────────────────────────────────────

    override fun onTrackChanged(track: Track?) {
        track?.let {
            updateTrackUI(it)
            trackAdapter.setCurrentPlayingTrackId(it.videoId)
        }
    }

    override fun onPlayStateChanged(playing: Boolean) {
        updatePlayPauseIcons(playing)
    }

    override fun onProgressUpdate(currentMs: Long, durationMs: Long, bufferedPct: Int) {
        val progress = if (durationMs > 0) ((currentMs * 1000) / durationMs).toInt() else 0
        fullSeekBar.progress = progress
        miniProgressBar.progress = progress
        fullCurrentTime.text = formatMs(currentMs)
        fullDuration.text = formatMs(durationMs)
    }

    override fun onPlayerReady() {}

    override fun onError(code: Int) {
        Toast.makeText(this, "Playback notice: $code", Toast.LENGTH_SHORT).show()
    }

    private fun updateTrackUI(track: Track) {
        miniTrackTitle.text = track.title
        miniArtistName.text = track.artist
        fullTrackTitle.text = track.title
        fullArtistName.text = track.artist
        miniPlayerCard.visibility = View.VISIBLE

        if (track.thumbnailUrl.isNotBlank()) {
            Glide.with(this)
                .load(track.thumbnailUrl)
                .placeholder(R.drawable.bg_album_art_placeholder)
                .into(miniAlbumArtView)
            Glide.with(this)
                .load(track.thumbnailUrl)
                .placeholder(R.drawable.bg_album_art_large)
                .into(fullAlbumArtView)
        }
    }

    private fun updatePlayPauseIcons(playing: Boolean) {
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        miniPlayPauseBtn.setImageResource(icon)
        fullPlayPauseBtn.setImageResource(icon)
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}

// ─── Modern Track Adapter (RecyclerView) ──────────────────────────────────────

class TrackAdapter(
    val results: MutableList<SearchResult>,
    private val onItemClick: (SearchResult, Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    private var currentPlayingId: String? = null

    fun updateResults(newResults: List<SearchResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }

    fun setCurrentPlayingTrackId(id: String?) {
        currentPlayingId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val item = results[position]
        holder.bind(item, item.videoId == currentPlayingId) {
            onItemClick(item, position)
        }
    }

    override fun getItemCount(): Int = results.size

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.resultThumbnail)
        private val title: TextView = itemView.findViewById(R.id.resultTitle)
        private val artist: TextView = itemView.findViewById(R.id.resultArtist)
        private val duration: TextView = itemView.findViewById(R.id.resultDuration)
        private val playIcon: ImageView = itemView.findViewById(R.id.resultPlayIcon)
        private val cardContainer: View = itemView.findViewById(R.id.trackCardContainer)

        fun bind(result: SearchResult, isPlaying: Boolean, onClick: () -> Unit) {
            title.text = result.title
            artist.text = result.artist
            duration.text = formatDuration(result.durationMs)

            if (result.thumbnailUrl.isNotBlank()) {
                Glide.with(itemView.context)
                    .load(result.thumbnailUrl)
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(thumbnail)
            } else {
                thumbnail.setImageResource(R.drawable.bg_album_art_placeholder)
            }

            if (isPlaying) {
                cardContainer.setBackgroundResource(R.drawable.bg_play_chip)
                playIcon.setImageResource(R.drawable.ic_pause)
            } else {
                cardContainer.setBackgroundResource(R.drawable.bg_track_card)
                playIcon.setImageResource(R.drawable.ic_play)
            }

            itemView.setOnClickListener { onClick() }
        }

        private fun formatDuration(ms: Long): String {
            val sec = ms / 1000
            return "%d:%02d".format(sec / 60, sec % 60)
        }
    }
}

fun SearchResult.toTrack() = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl,
    streamUrl = streamUrl
)
