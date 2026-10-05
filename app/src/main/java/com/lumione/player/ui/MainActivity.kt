package com.lumione.player.ui

import android.content.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lumione.player.R
import com.lumione.player.auth.AuthManager
import com.lumione.player.auth.AuthState
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.RepeatMode
import com.lumione.player.queue.Track
import com.lumione.player.search.SearchAuthException
import com.lumione.player.search.SearchEngine
import com.lumione.player.search.SearchResult
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val searchEngine = SearchEngine()
    private val queueManager = QueueManager()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val authManager by lazy { AuthManager(this) }
    private var accountDialog: BottomSheetDialog? = null

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data: Intent? = result.data
        authManager.handleSignInResult(
            data = data,
            onSuccess = { user ->
                Toast.makeText(this, "Signed in as ${user.displayName ?: user.email}", Toast.LENGTH_SHORT).show()
                updateAccountDialogView(accountDialog)
                loadTrendingTracks()
            },
            onError = { error ->
                Toast.makeText(this, "Sign-in error: $error", Toast.LENGTH_LONG).show()
                updateAccountDialogView(accountDialog)
            },
            onCancelled = {
                updateAccountDialogView(accountDialog)
            }
        )
    }

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var searchInput: EditText
    private lateinit var searchResultsList: RecyclerView
    private lateinit var sectionTitle: TextView
    private lateinit var btnSeeAll: TextView
    private lateinit var featuredMusicCard: View
    private lateinit var featuredThumbnail: ImageView
    private lateinit var featuredTitle: TextView
    private lateinit var featuredArtist: TextView
    private lateinit var btnFeaturedPlay: View
    private lateinit var featuredPlayIcon: ImageView
    private var featuredTrackResult: SearchResult? = null
    private var allLoadedResults: List<SearchResult> = emptyList()

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
    private lateinit var navHome: ImageButton
    private lateinit var navSearch: ImageButton
    private lateinit var navLibrary: ImageButton
    private lateinit var navProfile: ImageButton
    private var isLiked = false
    private lateinit var trackAdapter: TrackAdapter

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
        setupNavUI()

        if (authManager.isUserSignedIn()) {
            loadTrendingTracks()
        } else {
            sectionTitle.text = "Sign in to search music"
            featuredMusicCard.visibility = View.GONE
            trackAdapter.updateResults(emptyList())
        }
    }

    override fun onDestroy() {
        mainScope.cancel()
        searchEngine.cancel()
        authManager.cleanup()
        accountDialog?.dismiss()
        super.onDestroy()
    }

    // ─── View Binding ─────────────────────────────────────────────────────────

    private fun bindViews() {
        searchInput = findViewById(R.id.searchInput)
        searchResultsList = findViewById(R.id.searchResultsList)
        sectionTitle = findViewById(R.id.sectionTitle)
        btnSeeAll = findViewById(R.id.btnSeeAll)
        featuredMusicCard = findViewById(R.id.featuredMusicCard)
        featuredThumbnail = findViewById(R.id.featuredThumbnail)
        featuredTitle = findViewById(R.id.featuredTitle)
        featuredArtist = findViewById(R.id.featuredArtist)
        btnFeaturedPlay = findViewById(R.id.btnFeaturedPlay)
        featuredPlayIcon = findViewById(R.id.featuredPlayIcon)
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
        navHome = findViewById(R.id.navHome)
        navSearch = findViewById(R.id.navSearch)
        navLibrary = findViewById(R.id.navLibrary)
        navProfile = findViewById(R.id.navProfile)

        // Featured Card Click → Play Featured
        val playFeaturedAction = View.OnClickListener {
            featuredTrackResult?.let { res ->
                val track = res.toTrack()
                val globalIndex = allLoadedResults.indexOfFirst { it.videoId == res.videoId }.coerceAtLeast(0)
                queueManager.setQueue(buildQueueFromResults(allLoadedResults, globalIndex), 0)
                playTrack(track)
                showFullPlayer()
            }
        }
        featuredMusicCard.setOnClickListener(playFeaturedAction)
        btnFeaturedPlay.setOnClickListener(playFeaturedAction)

        trackAdapter = TrackAdapter(mutableListOf()) { result, _ ->
            val track = result.toTrack()
            val globalIndex = allLoadedResults.indexOfFirst { it.videoId == result.videoId }.coerceAtLeast(0)
            queueManager.setQueue(buildQueueFromResults(allLoadedResults, globalIndex), 0)
            playTrack(track)
            showFullPlayer()
        }
        searchResultsList.layoutManager = LinearLayoutManager(this)
        searchResultsList.isNestedScrollingEnabled = false
        searchResultsList.adapter = trackAdapter
    }

    private fun playTrack(track: Track) {
        updateTrackUI(track)
        trackAdapter.setCurrentPlayingTrackId(track.videoId)
        updatePlayPauseIcons(false)
        Toast.makeText(this, "Audio playback provider pending integration", Toast.LENGTH_SHORT).show()
    }

    // ─── Search UI ────────────────────────────────────────────────────────────

    private fun setupSearchUI() {
        val executeSearch: () -> Unit = {
            val query = searchInput.text.toString().trim()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
            if (query.isNotBlank()) {
                performSearch(query)
            } else {
                loadTrendingTracks()
            }
        }

        var searchJob: Job? = null
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val q = s?.toString()?.trim() ?: ""
                if (q.isNotBlank()) {
                    searchJob = mainScope.launch {
                        delay(600)
                        performSearch(q)
                    }
                } else {
                    loadTrendingTracks()
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        searchInput.setOnEditorActionListener { _, _, _ ->
            executeSearch()
            true
        }

        searchInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                executeSearch()
                true
            } else {
                false
            }
        }

        btnSeeAll.setOnClickListener {
            if (allLoadedResults.isNotEmpty()) {
                sectionTitle.text = "Complete Discovery Queue"
                featuredMusicCard.visibility = View.GONE
                trackAdapter.updateResults(allLoadedResults)
            } else {
                loadTrendingTracks()
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return

        if (!authManager.isUserSignedIn()) {
            sectionTitle.text = "Sign in to search music"
            featuredMusicCard.visibility = View.GONE
            trackAdapter.updateResults(emptyList())
            Toast.makeText(this, "Sign in to search music.", Toast.LENGTH_SHORT).show()
            showAccountDialog()
            return
        }

        sectionTitle.text = "Searching \"$query\"..."
        mainScope.launch {
            try {
                var idToken = authManager.getIdToken(forceRefresh = false)
                var results: List<SearchResult> = emptyList()
                try {
                    results = searchEngine.search(query, idToken)
                } catch (e: SearchAuthException) {
                    // Refresh token and retry once
                    idToken = authManager.getIdToken(forceRefresh = true)
                    results = searchEngine.search(query, idToken)
                }
                sectionTitle.text = "Results for \"$query\""
                displayResults(results, isSearch = true)
            } catch (e: Exception) {
                sectionTitle.text = "Search results"
                Toast.makeText(this@MainActivity, e.message ?: "Failed to retrieve search results", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadTrendingTracks() {
        if (!authManager.isUserSignedIn()) {
            sectionTitle.text = "Sign in to search music"
            featuredMusicCard.visibility = View.GONE
            trackAdapter.updateResults(emptyList())
            return
        }

        sectionTitle.text = "Trending Now"
        mainScope.launch {
            try {
                var idToken = authManager.getIdToken(forceRefresh = false)
                var results: List<SearchResult> = emptyList()
                try {
                    results = searchEngine.getTrending(idToken)
                } catch (e: SearchAuthException) {
                    idToken = authManager.getIdToken(forceRefresh = true)
                    results = searchEngine.getTrending(idToken)
                }
                if (results.isNotEmpty()) {
                    displayResults(results, isSearch = false)
                }
            } catch (e: Exception) {
                // If trending fails, keep current state
            }
        }
    }

    private fun displayResults(results: List<SearchResult>, isSearch: Boolean) {
        allLoadedResults = results
        if (results.isEmpty()) {
            featuredMusicCard.visibility = View.GONE
            trackAdapter.updateResults(emptyList())
            return
        }

        // Top track goes into Featured Card
        val hero = results[0]
        featuredTrackResult = hero
        featuredTitle.text = hero.title
        featuredArtist.text = hero.artist
        if (hero.thumbnailUrl.isNotBlank()) {
            Glide.with(this@MainActivity)
                .load(hero.thumbnailUrl)
                .placeholder(R.drawable.bg_album_art_placeholder)
                .into(featuredThumbnail)
        }
        featuredMusicCard.visibility = View.VISIBLE

        // Remaining tracks populate Discovery Stream
        val streamItems = if (isSearch) results.drop(1) else results.drop(1).take(6)
        trackAdapter.updateResults(streamItems)
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
        val openFullPlayer = View.OnClickListener { showFullPlayer() }
        miniPlayerCard.setOnClickListener(openFullPlayer)
        miniAlbumArtView.setOnClickListener(openFullPlayer)
        miniTrackTitle.setOnClickListener(openFullPlayer)
        miniArtistName.setOnClickListener(openFullPlayer)
        btnBack.setOnClickListener { hideFullPlayer() }

        btnSettings.setOnClickListener {
            showAccountDialog()
        }

        btnLike.setOnClickListener {
            isLiked = !isLiked
            btnLike.setColorFilter(
                if (isLiked) getColor(R.color.accent_fuchsia) else getColor(R.color.primary_lavender)
            )
        }

        miniPlayPauseBtn.setOnClickListener { togglePlayPause() }
        fullPlayPauseBtn.setOnClickListener { togglePlayPause() }

        btnNext.setOnClickListener {
            queueManager.nextTrack()?.let { playTrack(it) }
        }
        btnPrev.setOnClickListener {
            queueManager.previousTrack()?.let { playTrack(it) }
        }

        btnShuffle.setOnClickListener {
            queueManager.shuffleEnabled = !queueManager.shuffleEnabled
            btnShuffle.alpha = if (queueManager.shuffleEnabled) 1.0f else 0.4f
        }

        btnRepeat.setOnClickListener {
            queueManager.repeatMode = when (queueManager.repeatMode) {
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
                    val currentTrack = queueManager.currentTrack()
                    val duration = currentTrack?.durationMs ?: 0L
                    val target = (progress.toLong() * duration) / 1000L
                    fullCurrentTime.text = formatMs(target)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
            }
        })
    }

    private fun setupNavUI() {
        navHome.setOnClickListener {
            findViewById<ScrollView>(R.id.mainScrollView)?.smoothScrollTo(0, 0)
        }
        navSearch.setOnClickListener {
            searchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        navLibrary.setOnClickListener {
            Toast.makeText(this, "Queue: ${allLoadedResults.size} tracks ready", Toast.LENGTH_SHORT).show()
        }
        navProfile.setOnClickListener {
            showAccountDialog()
        }
    }

    private fun showAccountDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_account, null)
        dialog.setContentView(view)
        accountDialog = dialog

        view.findViewById<View>(R.id.btnCloseDialog).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnGoogleSignIn).setOnClickListener {
            googleSignInLauncher.launch(authManager.getSignInIntent())
        }

        view.findViewById<View>(R.id.btnSignOut).setOnClickListener {
            authManager.signOut {
                Toast.makeText(this, "Signed out of LumiOne", Toast.LENGTH_SHORT).show()
                updateAccountDialogView(dialog)
                sectionTitle.text = "Sign in to search music"
                featuredMusicCard.visibility = View.GONE
                trackAdapter.updateResults(emptyList())
            }
        }

        updateAccountDialogView(dialog)
        dialog.show()
    }

    private fun updateAccountDialogView(dialog: BottomSheetDialog?) {
        if (dialog == null || !dialog.isShowing) return
        val root = dialog.findViewById<View>(R.id.signedInContainer)?.parent as? View ?: return

        val signedInContainer = root.findViewById<View>(R.id.signedInContainer) ?: return
        val signedOutContainer = root.findViewById<View>(R.id.signedOutContainer) ?: return
        val txtUserName = root.findViewById<TextView>(R.id.txtUserName) ?: return
        val txtUserEmail = root.findViewById<TextView>(R.id.txtUserEmail) ?: return
        val txtUserUid = root.findViewById<TextView>(R.id.txtUserUid) ?: return
        val imgUserAvatar = root.findViewById<ImageView>(R.id.imgUserAvatar) ?: return

        val user = authManager.getCurrentUser()
        if (user != null) {
            signedInContainer.visibility = View.VISIBLE
            signedOutContainer.visibility = View.GONE
            txtUserName.text = user.displayName ?: "LumiOne Listener"
            txtUserEmail.text = user.email ?: "No email associated"
            txtUserUid.text = "UID: ${user.uid}"

            val photoUrl = user.photoUrl?.toString()
            if (!photoUrl.isNullOrBlank()) {
                Glide.with(this)
                    .load(photoUrl)
                    .circleCrop()
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(imgUserAvatar)
            } else {
                imgUserAvatar.setImageResource(R.drawable.bg_album_art_placeholder)
            }
        } else {
            signedInContainer.visibility = View.GONE
            signedOutContainer.visibility = View.VISIBLE
        }
    }

    private fun togglePlayPause() {
        Toast.makeText(this, "Audio playback provider pending integration", Toast.LENGTH_SHORT).show()
    }

    private fun showFullPlayer() {
        fullPlayerContainer.alpha = 1f
        fullPlayerContainer.visibility = View.VISIBLE
        fullPlayerContainer.bringToFront()
    }

    private fun hideFullPlayer() {
        fullPlayerContainer.visibility = View.GONE
    }

    private fun updateRepeatIcon() {
        btnRepeat.alpha = if (queueManager.repeatMode != RepeatMode.NONE) 1.0f else 0.4f
    }

    override fun onBackPressed() {
        if (fullPlayerContainer.visibility == View.VISIBLE) {
            hideFullPlayer()
        } else {
            super.onBackPressed()
        }
    }

    private fun updateTrackUI(track: Track) {
        miniTrackTitle.text = track.title
        miniArtistName.text = track.artist
        fullTrackTitle.text = track.title
        fullArtistName.text = track.artist
        fullCurrentTime.text = "0:00"
        fullDuration.text = formatMs(track.durationMs)
        fullSeekBar.progress = 0
        miniProgressBar.progress = 0
        miniPlayerCard.visibility = View.VISIBLE

        if (track.thumbnailUrl.isNotBlank()) {
            Glide.with(this)
                .load(track.thumbnailUrl)
                .circleCrop()
                .placeholder(R.drawable.bg_album_art_placeholder)
                .into(miniAlbumArtView)
            Glide.with(this)
                .load(track.thumbnailUrl)
                .circleCrop()
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

// ─── Modern Editorial Track Adapter (RecyclerView) ───────────────────────────

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
                    .circleCrop()
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(thumbnail)
            } else {
                thumbnail.setImageResource(R.drawable.bg_album_art_placeholder)
            }

            if (isPlaying) {
                cardContainer.setBackgroundResource(R.drawable.bg_active_track_pill)
                playIcon.setImageResource(R.drawable.ic_pause)
            } else {
                cardContainer.setBackgroundResource(R.drawable.bg_track_item_normal)
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
