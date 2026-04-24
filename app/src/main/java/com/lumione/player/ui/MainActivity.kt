package com.lumione.player.ui

import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.lumione.player.R
import com.lumione.player.queue.QueueManager
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
    private lateinit var searchResultsList: ListView
    private lateinit var miniPlayerCard: View
    private lateinit var miniTrackTitle: TextView
    private lateinit var miniArtistName: TextView
    private lateinit var miniPlayPauseBtn: ImageButton
    private lateinit var miniProgressBar: ProgressBar
    private lateinit var fullPlayerContainer: View
    private lateinit var fullTrackTitle: TextView
    private lateinit var fullArtistName: TextView
    private lateinit var fullPlayPauseBtn: ImageButton
    private lateinit var fullSeekBar: SeekBar
    private lateinit var fullCurrentTime: TextView
    private lateinit var fullDuration: TextView
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var albumArtView: ImageView
    private lateinit var searchResultAdapter: SearchResultAdapter

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
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        miniTrackTitle = findViewById(R.id.miniTrackTitle)
        miniArtistName = findViewById(R.id.miniArtistName)
        miniPlayPauseBtn = findViewById(R.id.miniPlayPauseBtn)
        miniProgressBar = findViewById(R.id.miniProgressBar)
        fullPlayerContainer = findViewById(R.id.fullPlayerContainer)
        fullTrackTitle = findViewById(R.id.fullTrackTitle)
        fullArtistName = findViewById(R.id.fullArtistName)
        fullPlayPauseBtn = findViewById(R.id.fullPlayPauseBtn)
        fullSeekBar = findViewById(R.id.fullSeekBar)
        fullCurrentTime = findViewById(R.id.fullCurrentTime)
        fullDuration = findViewById(R.id.fullDuration)
        btnNext = findViewById(R.id.btnNext)
        btnPrev = findViewById(R.id.btnPrev)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnBack = findViewById(R.id.btnBack)
        albumArtView = findViewById(R.id.albumArtView)

        searchResultAdapter = SearchResultAdapter(this, mutableListOf())
        searchResultsList.adapter = searchResultAdapter
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
            performSearch(searchInput.text.toString())
            true
        }

        searchResultsList.setOnItemClickListener { _, _, position, _ ->
            val result = searchResultAdapter.getItem(position) ?: return@setOnItemClickListener
            val track = result.toTrack()
            playbackService?.let { svc ->
                svc.queueManager.setQueue(
                    buildQueueFromResults(searchResultAdapter.results, position),
                    0
                )
                svc.loadAndPlay(track)
            }
            showFullPlayer()
        }
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return
        mainScope.launch {
            val results = withContext(Dispatchers.IO) { searchEngine.search(query) }
            searchResultAdapter.updateResults(results)
        }
    }

    private fun loadTrendingTracks() {
        mainScope.launch {
            val results = withContext(Dispatchers.IO) {
                // Seed with popular requests for demo
                listOf(
                    SearchResult("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213000,
                        "https://img.youtube.com/vi/dQw4w9WgXcQ/mqdefault.jpg"),
                    SearchResult("y6120QOlsfU", "Sandstorm", "Darude", 229000,
                        "https://img.youtube.com/vi/y6120QOlsfU/mqdefault.jpg"),
                    SearchResult("ktvTqknDobU", "Radioactive", "Imagine Dragons", 187000,
                        "https://img.youtube.com/vi/ktvTqknDobU/mqdefault.jpg"),
                    SearchResult("1G4isv_Fylg", "Losing It", "FISHER", 374000,
                        "https://img.youtube.com/vi/1G4isv_Fylg/mqdefault.jpg")
                )
            }
            searchResultAdapter.updateResults(results)
        }
    }

    private fun buildQueueFromResults(results: List<SearchResult>, startAt: Int): List<Track> {
        val reordered = results.subList(startAt, results.size) +
                        results.subList(0, startAt)
        return reordered.map { it.toTrack() }
    }

    // ─── Player UI ────────────────────────────────────────────────────────────

    private fun setupPlayerUI() {
        // Mini player click → open full player
        miniPlayerCard.setOnClickListener { showFullPlayer() }
        btnBack.setOnClickListener { hideFullPlayer() }

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
                QueueManager.RepeatMode.NONE -> QueueManager.RepeatMode.ALL
                QueueManager.RepeatMode.ALL -> QueueManager.RepeatMode.ONE
                QueueManager.RepeatMode.ONE -> QueueManager.RepeatMode.NONE
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
        fullPlayerContainer.visibility = View.VISIBLE
        fullPlayerContainer.animate().alpha(1f).duration = 200
    }

    private fun hideFullPlayer() {
        fullPlayerContainer.animate().alpha(0f).setDuration(200).withEndAction {
            fullPlayerContainer.visibility = View.GONE
        }
    }

    private fun syncUIWithService() {
        val svc = playbackService ?: return
        svc.queueManager.currentTrack()?.let { updateTrackUI(it) }
        updatePlayPauseIcons(svc.isPlaying)
    }

    private fun updateRepeatIcon() {
        val svc = playbackService ?: return
        btnRepeat.alpha = if (svc.queueManager.repeatMode != QueueManager.RepeatMode.NONE) 1.0f else 0.4f
    }

    // ─── PlaybackServiceListener ──────────────────────────────────────────────

    override fun onTrackChanged(track: Track?) {
        track?.let { updateTrackUI(it) }
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
        Toast.makeText(this, "Playback error: $code", Toast.LENGTH_SHORT).show()
    }

    private fun updateTrackUI(track: Track) {
        miniTrackTitle.text = track.title
        miniArtistName.text = track.artist
        fullTrackTitle.text = track.title
        fullArtistName.text = track.artist
        miniPlayerCard.visibility = View.VISIBLE
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

// ─── Search Result Adapter ────────────────────────────────────────────────────

class SearchResultAdapter(
    context: Context,
    val results: MutableList<SearchResult>
) : ArrayAdapter<SearchResult>(context, 0, results) {

    fun updateResults(newResults: List<SearchResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_search_result, parent, false)
        val result = getItem(position) ?: return view

        view.findViewById<TextView>(R.id.resultTitle).text = result.title
        view.findViewById<TextView>(R.id.resultArtist).text = result.artist
        view.findViewById<TextView>(R.id.resultDuration).text = formatMs(result.durationMs)

        return view
    }

    private fun formatMs(ms: Long): String {
        val sec = ms / 1000
        return "%d:%02d".format(sec / 60, sec % 60)
    }
}

fun SearchResult.toTrack() = Track(
    videoId = videoId,
    title = title,
    artist = artist,
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl
)
