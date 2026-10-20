package com.lumione.player.ui

import android.content.*
import android.os.*
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Color
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.lumione.player.R
import com.lumione.player.auth.AuthManager
import com.lumione.player.data.FavoritesManager
import com.lumione.player.data.HistoryManager
import com.lumione.player.data.Playlist
import com.lumione.player.data.PlaylistManager
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.RepeatMode
import com.lumione.player.queue.Track
import com.lumione.player.search.SearchAuthException
import com.lumione.player.search.SearchEngine
import com.lumione.player.search.SearchResult
import com.lumione.player.service.PlaybackService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), PlaybackService.PlaybackServiceListener {

    private val searchEngine = SearchEngine()
    private val queueManager = QueueManager()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val authManager by lazy { AuthManager(this) }
    private val favoritesManager by lazy { FavoritesManager.getInstance(this) }
    private val historyManager by lazy { HistoryManager.getInstance(this) }
    private val playlistManager by lazy { PlaylistManager.getInstance(this) }

    private var playbackService: PlaybackService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val lumiBinder = binder as? PlaybackService.LumiBinder
            playbackService = lumiBinder?.getService()
            isServiceBound = true
            playbackService?.setListener(this@MainActivity)
            playbackService?.let { s ->
                updatePlayPauseIcons(s.isPlaying)
                s.queueManager.currentTrack()?.let { updateTrackUI(it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data: Intent? = result.data
        authManager.handleSignInResult(
            data = data,
            onSuccess = { user ->
                Toast.makeText(this, "Signed in as ${user.displayName ?: user.email}", Toast.LENGTH_SHORT).show()
                updateProfileView()
                loadTrendingTracks()
            },
            onError = { error ->
                Toast.makeText(this, "Sign-in error: $error", Toast.LENGTH_LONG).show()
                updateProfileView()
            },
            onCancelled = {
                updateProfileView()
            }
        )
    }

    // ─── Destination Views & Navigation ───────────────────────────────────────
    private lateinit var includeHome: View
    private lateinit var includeSearch: View
    private lateinit var includeLibrary: View
    private lateinit var includeProfile: View
    private var currentNavTab = 0

    // ─── Bottom Navigation Views ─────────────────────────────────────────────
    private lateinit var navTabHome: View
    private lateinit var navTabSearch: View
    private lateinit var navTabLibrary: View
    private lateinit var navTabProfile: View
    private lateinit var pillHomeActive: View
    private lateinit var pillSearchActive: View
    private lateinit var pillLibraryActive: View
    private lateinit var pillProfileActive: View
    private lateinit var circleHomeInactive: View
    private lateinit var circleSearchInactive: View
    private lateinit var circleLibraryInactive: View
    private lateinit var circleProfileInactive: View

    // ─── Home / Explore Screen Views ──────────────────────────────────────────
    private lateinit var homeScrollView: View
    private lateinit var homeSearchTrigger: View
    private lateinit var btnHomeProfile: View
    private lateinit var homeFeaturedSection: View
    private lateinit var homeFeaturedCarousel: RecyclerView
    private lateinit var featuredCarouselAdapter: FeaturedCarouselAdapter
    private lateinit var homePopularSongsSection: View
    private lateinit var homePopularSongsList: RecyclerView
    private lateinit var popularSongAdapter: PopularSongAdapter
    private lateinit var homeRecentlyPlayedSection: View
    private lateinit var homeRecentlyPlayedList: RecyclerView
    private lateinit var homeRecentlyPlayedAdapter: RecentlyPlayedAdapter
    private lateinit var homeSectionTitle: TextView
    private lateinit var btnHomeSeeAll: TextView
    private lateinit var homeTrendingList: RecyclerView
    private lateinit var homeTrackAdapter: TrackAdapter
    private var allLoadedResults: List<SearchResult> = emptyList()

    private var isUserSeeking: Boolean = false

    // ─── Search Screen Views ──────────────────────────────────────────────────
    private lateinit var searchTabInput: EditText
    private lateinit var btnSearchClear: ImageButton
    private lateinit var searchTagsScroll: View
    private lateinit var searchLoadingBar: ProgressBar
    private lateinit var searchCountText: TextView
    private lateinit var searchEmptyState: View
    private lateinit var searchTabResultsList: RecyclerView
    private lateinit var searchTrackAdapter: TrackAdapter

    // ─── Library Screen Views ─────────────────────────────────────────────────
    private lateinit var tabPlaylists: TextView
    private lateinit var tabFavorites: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabQueue: TextView
    private lateinit var btnNewPlaylistHeader: TextView
    private lateinit var btnLibraryAction: TextView
    private lateinit var libraryCountLabel: TextView
    private lateinit var libraryEmptyState: View
    private lateinit var libraryEmptyIcon: ImageView
    private lateinit var libraryEmptyTitle: TextView
    private lateinit var libraryEmptyDesc: TextView
    private lateinit var btnLibraryEmptyAction: TextView
    private lateinit var libraryRecyclerView: RecyclerView
    private lateinit var libraryTrackAdapter: QueueTrackAdapter
    private lateinit var libraryPlaylistAdapter: PlaylistAdapter
    private var currentLibraryTab = 0 // 0 = Playlists, 1 = Favorites, 2 = History, 3 = Queue

    private val appPrefs by lazy { getSharedPreferences("lumione_settings", Context.MODE_PRIVATE) }

    private fun triggerHaptic(view: View) {
        if (appPrefs.getBoolean("pref_haptic", true)) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    // ─── Profile Screen Views ─────────────────────────────────────────────────
    private lateinit var profileAvatar: ImageView
    private lateinit var profileDisplayName: TextView
    private lateinit var profileEmail: TextView
    private lateinit var btnEditProfile: View
    private lateinit var btnProfileAuth: View
    private lateinit var profileAuthIcon: ImageView
    private lateinit var profileAuthText: TextView
    private lateinit var statPlaylistsCount: TextView
    private lateinit var statFavoritesCount: TextView
    private lateinit var statHistoryCount: TextView
    private lateinit var statQueueCount: TextView
    private lateinit var statCardPlaylists: View
    private lateinit var statCardFavorites: View
    private lateinit var statCardHistory: View
    private lateinit var statCardQueue: View
    private lateinit var rowShortcutPlaylists: View
    private lateinit var rowShortcutFavorites: View
    private lateinit var rowShortcutHistory: View
    private lateinit var rowShortcutQueue: View
    private lateinit var subtitleShortcutPlaylists: TextView
    private lateinit var subtitleShortcutFavorites: TextView
    private lateinit var subtitleShortcutHistory: TextView
    private lateinit var subtitleShortcutQueue: TextView
    private lateinit var btnProfileCreatePlaylist: TextView
    private lateinit var profilePlaylistsEmpty: TextView
    private lateinit var profilePlaylistsRecycler: RecyclerView
    private lateinit var profilePlaylistAdapter: PlaylistAdapter
    private lateinit var rowPrefShuffle: View
    private lateinit var txtPrefShuffleStatus: TextView
    private lateinit var rowPrefRepeat: View
    private lateinit var txtPrefRepeatStatus: TextView
    private lateinit var rowPrefAutoplay: View
    private lateinit var txtPrefAutoplayStatus: TextView
    private lateinit var rowPrefHaptic: View
    private lateinit var txtPrefHapticStatus: TextView
    private lateinit var rowClearHistory: View
    private lateinit var rowClearFavorites: View

    // ─── Navigation Drawer Views ──────────────────────────────────────────────
    private lateinit var btnHomeMenu: View
    private lateinit var navDrawerContainer: View
    private lateinit var navDrawerPanel: View
    private lateinit var navDrawerScrim: View
    private lateinit var btnCloseDrawer: View
    private lateinit var drawerAvatar: ImageView
    private lateinit var drawerUserName: TextView
    private lateinit var drawerUserEmail: TextView
    private lateinit var drawerFavoritesCount: TextView
    private lateinit var drawerHistoryCount: TextView

    // ─── Mini Player Views ────────────────────────────────────────────────────
    private lateinit var miniPlayerCard: View
    private lateinit var miniAlbumArtView: ImageView
    private lateinit var miniTrackTitle: TextView
    private lateinit var miniArtistName: TextView
    private lateinit var miniPlayPauseBtn: ImageButton
    private lateinit var miniNextBtn: ImageButton
    private lateinit var miniProgressBar: ProgressBar

    // ─── Now Playing Full Player Views ────────────────────────────────────────
    private lateinit var fullPlayerContainer: View
    private lateinit var btnCollapseNowPlaying: ImageButton
    private lateinit var btnNowPlayingQueue: ImageButton
    private lateinit var nowPlayingVinylDisc: VinylDiscView
    private lateinit var nowPlayingWaveform: CyanWaveformView
    private lateinit var turntableTonearm: ImageView
    private lateinit var fullTrackTitle: TextView
    private lateinit var fullArtistName: TextView
    private lateinit var btnNowPlayingAddToPlaylist: ImageButton
    private lateinit var btnNowPlayingLike: ImageButton
    private lateinit var fullSeekBar: SeekBar
    private lateinit var fullCurrentTime: TextView
    private lateinit var fullDurationTime: TextView
    private lateinit var btnFullShuffle: ImageButton
    private lateinit var btnFullPrev: ImageButton
    private lateinit var btnFullPlayPause: View
    private lateinit var fullPlayPauseIcon: ImageView
    private lateinit var btnFullNext: ImageButton
    private lateinit var btnFullRepeat: ImageButton
    private lateinit var fullQualityBadge: TextView

    // ─── Queue Sheet Overlay Views ────────────────────────────────────────────
    private lateinit var queueSheetContainer: View
    private lateinit var queueSubtitle: TextView
    private lateinit var btnQueueClear: TextView
    private lateinit var btnQueueClose: ImageButton
    private lateinit var queueEmptyState: View
    private lateinit var queueSheetRecycler: RecyclerView
    private lateinit var queueSheetAdapter: QueueTrackAdapter

    // ─── Playlist Detail Overlay Views ────────────────────────────────────────
    private lateinit var playlistDetailContainer: View
    private lateinit var btnPlaylistDetailBack: ImageButton
    private lateinit var btnPlaylistDetailRename: ImageButton
    private lateinit var btnPlaylistDetailDelete: ImageButton
    private lateinit var txtPlaylistDetailTitle: TextView
    private lateinit var txtPlaylistDetailDesc: TextView
    private lateinit var txtPlaylistDetailTrackCount: TextView
    private lateinit var playlistDetailArtwork: ImageView
    private lateinit var playlistDetailFallback: ImageView
    private lateinit var btnPlaylistDetailPlayAll: View
    private lateinit var btnPlaylistDetailShuffle: View
    private lateinit var btnPlaylistDetailAddSongs: View
    private lateinit var playlistDetailEmptyState: View
    private lateinit var btnPlaylistDetailEmptyAdd: TextView
    private lateinit var rvPlaylistTracks: RecyclerView
    private lateinit var playlistTrackAdapter: PlaylistTrackAdapter
    private var currentDetailPlaylist: Playlist? = null

    // ─── Activity Lifecycle ───────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindAllViews()
        setupHomeScreen()
        setupSearchScreen()
        setupLibraryScreen()
        setupProfileScreen()
        setupNowPlayingScreen()
        setupQueueSheet()
        setupPlaylistDetailScreen()
        setupMiniPlayer()
        setupNavigation()
        setupNavDrawer()

        // Start & bind PlaybackService
        val serviceIntent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        if (authManager.isUserSignedIn()) {
            loadTrendingTracks()
        } else {
            featuredCarouselAdapter.updateItems(emptyList())
            popularSongAdapter.updateItems(emptyList())
            homeTrackAdapter.updateResults(emptyList())
        }

        updateProfileView()
    }

    override fun onDestroy() {
        if (isServiceBound) {
            playbackService?.setListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
        mainScope.cancel()
        searchEngine.cancel()
        authManager.cleanup()
        super.onDestroy()
    }

    // ─── View Binding ─────────────────────────────────────────────────────────

    private fun bindAllViews() {
        // Tab includes
        includeHome = findViewById(R.id.includeHome)
        includeSearch = findViewById(R.id.includeSearch)
        includeLibrary = findViewById(R.id.includeLibrary)
        includeProfile = findViewById(R.id.includeProfile)

        // Bottom nav
        navTabHome = findViewById(R.id.navTabHome)
        navTabSearch = findViewById(R.id.navTabSearch)
        navTabLibrary = findViewById(R.id.navTabLibrary)
        navTabProfile = findViewById(R.id.navTabProfile)

        pillHomeActive = findViewById(R.id.pillHomeActive)
        pillSearchActive = findViewById(R.id.pillSearchActive)
        pillLibraryActive = findViewById(R.id.pillLibraryActive)
        pillProfileActive = findViewById(R.id.pillProfileActive)

        circleHomeInactive = findViewById(R.id.circleHomeInactive)
        circleSearchInactive = findViewById(R.id.circleSearchInactive)
        circleLibraryInactive = findViewById(R.id.circleLibraryInactive)
        circleProfileInactive = findViewById(R.id.circleProfileInactive)

        // Home / Explore
        homeScrollView = includeHome
        homeSearchTrigger = includeHome.findViewById(R.id.homeSearchTrigger)
        btnHomeProfile = includeHome.findViewById(R.id.btnHomeProfile)
        homeFeaturedSection = includeHome.findViewById(R.id.homeFeaturedSection)
        homeFeaturedCarousel = includeHome.findViewById(R.id.homeFeaturedCarousel)
        homePopularSongsSection = includeHome.findViewById(R.id.homePopularSongsSection)
        homePopularSongsList = includeHome.findViewById(R.id.homePopularSongsList)
        homeRecentlyPlayedSection = includeHome.findViewById(R.id.homeRecentlyPlayedSection)
        homeRecentlyPlayedList = includeHome.findViewById(R.id.homeRecentlyPlayedList)
        homeSectionTitle = includeHome.findViewById(R.id.homeSectionTitle)
        btnHomeSeeAll = includeHome.findViewById(R.id.btnHomeSeeAll)
        homeTrendingList = includeHome.findViewById(R.id.homeTrendingList)

        // Search
        searchTabInput = includeSearch.findViewById(R.id.searchTabInput)
        btnSearchClear = includeSearch.findViewById(R.id.btnSearchClear)
        searchTagsScroll = includeSearch.findViewById(R.id.searchTagsScroll)
        searchLoadingBar = includeSearch.findViewById(R.id.searchLoadingBar)
        searchCountText = includeSearch.findViewById(R.id.searchCountText)
        searchEmptyState = includeSearch.findViewById(R.id.searchEmptyState)
        searchTabResultsList = includeSearch.findViewById(R.id.searchTabResultsList)

        // Library
        tabPlaylists = includeLibrary.findViewById(R.id.tabPlaylists)
        tabFavorites = includeLibrary.findViewById(R.id.tabFavorites)
        tabHistory = includeLibrary.findViewById(R.id.tabHistory)
        tabQueue = includeLibrary.findViewById(R.id.tabQueue)
        btnNewPlaylistHeader = includeLibrary.findViewById(R.id.btnNewPlaylistHeader)
        btnLibraryAction = includeLibrary.findViewById(R.id.btnLibraryAction)
        libraryCountLabel = includeLibrary.findViewById(R.id.libraryCountLabel)
        libraryEmptyState = includeLibrary.findViewById(R.id.libraryEmptyState)
        libraryEmptyIcon = includeLibrary.findViewById(R.id.libraryEmptyIcon)
        libraryEmptyTitle = includeLibrary.findViewById(R.id.libraryEmptyTitle)
        libraryEmptyDesc = includeLibrary.findViewById(R.id.libraryEmptyDesc)
        btnLibraryEmptyAction = includeLibrary.findViewById(R.id.btnLibraryEmptyAction)
        libraryRecyclerView = includeLibrary.findViewById(R.id.libraryRecyclerView)

        // Profile
        profileAvatar = includeProfile.findViewById(R.id.profileAvatar)
        profileDisplayName = includeProfile.findViewById(R.id.profileDisplayName)
        profileEmail = includeProfile.findViewById(R.id.profileEmail)
        btnEditProfile = includeProfile.findViewById(R.id.btnEditProfile)
        btnProfileAuth = includeProfile.findViewById(R.id.btnProfileAuth)
        profileAuthIcon = includeProfile.findViewById(R.id.profileAuthIcon)
        profileAuthText = includeProfile.findViewById(R.id.profileAuthText)
        statPlaylistsCount = includeProfile.findViewById(R.id.statPlaylistsCount)
        statFavoritesCount = includeProfile.findViewById(R.id.statFavoritesCount)
        statHistoryCount = includeProfile.findViewById(R.id.statHistoryCount)
        statQueueCount = includeProfile.findViewById(R.id.statQueueCount)
        statCardPlaylists = includeProfile.findViewById(R.id.statCardPlaylists)
        statCardFavorites = includeProfile.findViewById(R.id.statCardFavorites)
        statCardHistory = includeProfile.findViewById(R.id.statCardHistory)
        statCardQueue = includeProfile.findViewById(R.id.statCardQueue)
        rowShortcutPlaylists = includeProfile.findViewById(R.id.rowShortcutPlaylists)
        rowShortcutFavorites = includeProfile.findViewById(R.id.rowShortcutFavorites)
        rowShortcutHistory = includeProfile.findViewById(R.id.rowShortcutHistory)
        rowShortcutQueue = includeProfile.findViewById(R.id.rowShortcutQueue)
        subtitleShortcutPlaylists = includeProfile.findViewById(R.id.subtitleShortcutPlaylists)
        subtitleShortcutFavorites = includeProfile.findViewById(R.id.subtitleShortcutFavorites)
        subtitleShortcutHistory = includeProfile.findViewById(R.id.subtitleShortcutHistory)
        subtitleShortcutQueue = includeProfile.findViewById(R.id.subtitleShortcutQueue)
        btnProfileCreatePlaylist = includeProfile.findViewById(R.id.btnProfileCreatePlaylist)
        profilePlaylistsEmpty = includeProfile.findViewById(R.id.profilePlaylistsEmpty)
        profilePlaylistsRecycler = includeProfile.findViewById(R.id.profilePlaylistsRecycler)
        rowPrefShuffle = includeProfile.findViewById(R.id.rowPrefShuffle)
        txtPrefShuffleStatus = includeProfile.findViewById(R.id.txtPrefShuffleStatus)
        rowPrefRepeat = includeProfile.findViewById(R.id.rowPrefRepeat)
        txtPrefRepeatStatus = includeProfile.findViewById(R.id.txtPrefRepeatStatus)
        rowPrefAutoplay = includeProfile.findViewById(R.id.rowPrefAutoplay)
        txtPrefAutoplayStatus = includeProfile.findViewById(R.id.txtPrefAutoplayStatus)
        rowPrefHaptic = includeProfile.findViewById(R.id.rowPrefHaptic)
        txtPrefHapticStatus = includeProfile.findViewById(R.id.txtPrefHapticStatus)
        rowClearHistory = includeProfile.findViewById(R.id.rowClearHistory)
        rowClearFavorites = includeProfile.findViewById(R.id.rowClearFavorites)

        // Navigation Drawer
        btnHomeMenu = includeHome.findViewById(R.id.btnHomeMenu)
        navDrawerContainer = findViewById(R.id.navDrawerContainer)
        val nd = findViewById<View>(R.id.includeNavDrawer) ?: navDrawerContainer
        navDrawerPanel = nd.findViewById(R.id.navDrawerPanel)
        navDrawerScrim = nd.findViewById(R.id.navDrawerScrim)
        btnCloseDrawer = nd.findViewById(R.id.btnCloseDrawer)
        drawerAvatar = nd.findViewById(R.id.drawerAvatar)
        drawerUserName = nd.findViewById(R.id.drawerUserName)
        drawerUserEmail = nd.findViewById(R.id.drawerUserEmail)
        drawerFavoritesCount = nd.findViewById(R.id.drawerFavoritesCount)
        drawerHistoryCount = nd.findViewById(R.id.drawerHistoryCount)

        // Mini player
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        miniAlbumArtView = findViewById(R.id.miniAlbumArtView)
        miniTrackTitle = findViewById(R.id.miniTrackTitle)
        miniArtistName = findViewById(R.id.miniArtistName)
        miniPlayPauseBtn = findViewById(R.id.miniPlayPauseBtn)
        miniNextBtn = findViewById(R.id.miniNextBtn)
        miniProgressBar = findViewById(R.id.miniProgressBar)

        // Now playing
        fullPlayerContainer = findViewById(R.id.fullPlayerContainer)
        val np = findViewById<View>(R.id.includeNowPlaying) ?: fullPlayerContainer
        btnCollapseNowPlaying = np.findViewById(R.id.btnCollapseNowPlaying)
        btnNowPlayingQueue = np.findViewById(R.id.btnNowPlayingQueue)
        nowPlayingVinylDisc = np.findViewById(R.id.nowPlayingVinylDisc)
        nowPlayingWaveform = np.findViewById(R.id.nowPlayingWaveform)
        turntableTonearm = np.findViewById(R.id.turntableTonearm)
        fullTrackTitle = np.findViewById(R.id.fullTrackTitle)
        fullArtistName = np.findViewById(R.id.fullArtistName)
        btnNowPlayingAddToPlaylist = np.findViewById(R.id.btnNowPlayingAddToPlaylist)
        btnNowPlayingLike = np.findViewById(R.id.btnNowPlayingLike)
        fullSeekBar = np.findViewById(R.id.fullSeekBar)
        fullCurrentTime = np.findViewById(R.id.fullCurrentTime)
        fullDurationTime = np.findViewById(R.id.fullDurationTime)
        btnFullShuffle = np.findViewById(R.id.btnFullShuffle)
        btnFullPrev = np.findViewById(R.id.btnFullPrev)
        btnFullPlayPause = np.findViewById(R.id.btnFullPlayPause)
        fullPlayPauseIcon = np.findViewById(R.id.fullPlayPauseIcon)
        btnFullNext = np.findViewById(R.id.btnFullNext)
        btnFullRepeat = np.findViewById(R.id.btnFullRepeat)
        fullQualityBadge = np.findViewById(R.id.fullQualityBadge)

        // Queue sheet
        queueSheetContainer = findViewById(R.id.queueSheetContainer)
        val qs = findViewById<View>(R.id.includeQueueSheet) ?: queueSheetContainer
        queueSubtitle = qs.findViewById(R.id.queueSubtitle)
        btnQueueClear = qs.findViewById(R.id.btnQueueClear)
        btnQueueClose = qs.findViewById(R.id.btnQueueClose)
        queueEmptyState = qs.findViewById(R.id.queueEmptyState)
        queueSheetRecycler = qs.findViewById(R.id.queueSheetRecycler)

        // Playlist detail
        playlistDetailContainer = findViewById(R.id.playlistDetailContainer)
        val pd = findViewById<View>(R.id.includePlaylistDetail) ?: playlistDetailContainer
        btnPlaylistDetailBack = pd.findViewById(R.id.btnPlaylistDetailBack)
        btnPlaylistDetailRename = pd.findViewById(R.id.btnPlaylistDetailRename)
        btnPlaylistDetailDelete = pd.findViewById(R.id.btnPlaylistDetailDelete)
        txtPlaylistDetailTitle = pd.findViewById(R.id.txtPlaylistDetailTitle)
        txtPlaylistDetailDesc = pd.findViewById(R.id.txtPlaylistDetailDesc)
        txtPlaylistDetailTrackCount = pd.findViewById(R.id.txtPlaylistDetailTrackCount)
        playlistDetailArtwork = pd.findViewById(R.id.playlistDetailArtwork)
        playlistDetailFallback = pd.findViewById(R.id.playlistDetailFallback)
        btnPlaylistDetailPlayAll = pd.findViewById(R.id.btnPlaylistDetailPlayAll)
        btnPlaylistDetailShuffle = pd.findViewById(R.id.btnPlaylistDetailShuffle)
        btnPlaylistDetailAddSongs = pd.findViewById(R.id.btnPlaylistDetailAddSongs)
        playlistDetailEmptyState = pd.findViewById(R.id.playlistDetailEmptyState)
        btnPlaylistDetailEmptyAdd = pd.findViewById(R.id.btnPlaylistDetailEmptyAdd)
        rvPlaylistTracks = pd.findViewById(R.id.rvPlaylistTracks)
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    private fun selectNavTab(index: Int) {
        currentNavTab = index
        includeHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        includeSearch.visibility = if (index == 1) View.VISIBLE else View.GONE
        includeLibrary.visibility = if (index == 2) View.VISIBLE else View.GONE
        includeProfile.visibility = if (index == 3) View.VISIBLE else View.GONE

        // Tab 0: Home
        pillHomeActive.visibility = if (index == 0) View.VISIBLE else View.GONE
        circleHomeInactive.visibility = if (index == 0) View.GONE else View.VISIBLE

        // Tab 1: Search
        pillSearchActive.visibility = if (index == 1) View.VISIBLE else View.GONE
        circleSearchInactive.visibility = if (index == 1) View.GONE else View.VISIBLE

        // Tab 2: Library
        pillLibraryActive.visibility = if (index == 2) View.VISIBLE else View.GONE
        circleLibraryInactive.visibility = if (index == 2) View.GONE else View.VISIBLE

        // Tab 3: Profile
        pillProfileActive.visibility = if (index == 3) View.VISIBLE else View.GONE
        circleProfileInactive.visibility = if (index == 3) View.GONE else View.VISIBLE

        val tabs = listOf(navTabHome, navTabSearch, navTabLibrary, navTabProfile)
        tabs.forEachIndexed { i, tab ->
            val lp = tab.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.weight = if (i == index) 1.8f else 0.9f
                tab.layoutParams = lp
            }
        }

        if (index == 0) {
            refreshRecentlyPlayed()
        } else if (index == 2) {
            refreshLibraryTab()
        } else if (index == 3) {
            updateProfileView()
        }
    }

    private fun setupNavigation() {
        selectNavTab(0)

        navTabHome.setOnClickListener { triggerHaptic(it); selectNavTab(0) }
        navTabSearch.setOnClickListener {
            triggerHaptic(it)
            selectNavTab(1)
            searchTabInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchTabInput, InputMethodManager.SHOW_IMPLICIT)
        }
        navTabLibrary.setOnClickListener { triggerHaptic(it); selectNavTab(2) }
        navTabProfile.setOnClickListener { triggerHaptic(it); selectNavTab(3) }
    }

    private fun setupNavDrawer() {
        val nd = findViewById<View>(R.id.includeNavDrawer) ?: navDrawerContainer

        navDrawerScrim.setOnClickListener {
            hideNavDrawer()
        }

        btnCloseDrawer.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
        }

        nd.findViewById<View>(R.id.drawerUserCard)?.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
            selectNavTab(3)
        }

        nd.findViewById<View>(R.id.drawerItemHome)?.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
            selectNavTab(0)
        }

        nd.findViewById<View>(R.id.drawerItemSearch)?.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
            selectNavTab(1)
            searchTabInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchTabInput, InputMethodManager.SHOW_IMPLICIT)
        }

        nd.findViewById<View>(R.id.drawerItemLibrary)?.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
            selectNavTab(2)
        }

        nd.findViewById<View>(R.id.drawerItemFavorites)?.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
            selectNavTab(2)
            switchLibraryTab(0)
        }

        nd.findViewById<View>(R.id.drawerItemHistory)?.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
            selectNavTab(2)
            switchLibraryTab(1)
        }

        nd.findViewById<View>(R.id.drawerItemQueue)?.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
            showQueueSheet()
        }

        nd.findViewById<View>(R.id.drawerItemProfile)?.setOnClickListener {
            triggerHaptic(it)
            hideNavDrawer()
            selectNavTab(3)
        }
    }

    private fun showNavDrawer() {
        if (::navDrawerContainer.isInitialized && navDrawerContainer.visibility == View.VISIBLE) return
        updateProfileView()
        navDrawerContainer.visibility = View.VISIBLE
        navDrawerContainer.bringToFront()
        navDrawerScrim.alpha = 0f
        navDrawerScrim.animate().alpha(1f).setDuration(220).start()

        val width = if (navDrawerPanel.width > 0) navDrawerPanel.width.toFloat() else (300 * resources.displayMetrics.density)
        navDrawerPanel.translationX = -width
        navDrawerPanel.animate()
            .translationX(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideNavDrawer() {
        if (!::navDrawerContainer.isInitialized || navDrawerContainer.visibility != View.VISIBLE) return
        navDrawerScrim.animate().alpha(0f).setDuration(200).start()
        val width = if (navDrawerPanel.width > 0) navDrawerPanel.width.toFloat() else (300 * resources.displayMetrics.density)
        navDrawerPanel.animate()
            .translationX(-width)
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                navDrawerContainer.visibility = View.GONE
            }
            .start()
    }

    private fun selectLibraryTab(tabIndex: Int) {
        switchLibraryTab(tabIndex)
    }

    // ─── Home Screen ──────────────────────────────────────────────────────────

    private fun setupHomeScreen() {
        btnHomeProfile.setOnClickListener { triggerHaptic(it); selectNavTab(3) }
        btnHomeMenu.setOnClickListener { triggerHaptic(it); showNavDrawer() }

        homeSearchTrigger.setOnClickListener {
            triggerHaptic(it)
            selectNavTab(1)
            searchTabInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchTabInput, InputMethodManager.SHOW_IMPLICIT)
        }

        // Category Filter Chips
        val chipAll: TextView = includeHome.findViewById(R.id.chipCategoryAll)
        val chipMusic: TextView = includeHome.findViewById(R.id.chipCategoryMusic)
        val chipChill: TextView = includeHome.findViewById(R.id.chipCategoryChill)
        val chipWorkout: TextView = includeHome.findViewById(R.id.chipCategoryWorkout)
        val chipFocus: TextView = includeHome.findViewById(R.id.chipCategoryFocus)
        val chipWrapped: TextView = includeHome.findViewById(R.id.chipCategoryWrapped)
        val allCategoryChips = listOf(chipAll, chipMusic, chipChill, chipWorkout, chipFocus, chipWrapped)

        fun selectCategory(selected: TextView, query: String, isAll: Boolean = false) {
            allCategoryChips.forEach { chip ->
                if (chip == selected) {
                    chip.setBackgroundResource(R.drawable.bg_category_pill_active)
                    chip.setTextColor(getColor(R.color.text_on_cyan))
                } else {
                    chip.setBackgroundResource(R.drawable.bg_category_pill_inactive)
                    chip.setTextColor(getColor(R.color.text_secondary))
                }
            }
            if (isAll) {
                loadTrendingTracks()
            } else {
                selectNavTab(1)
                searchTabInput.setText(query)
                performSearch(query)
            }
        }

        chipAll.setOnClickListener { selectCategory(chipAll, "", isAll = true) }
        chipMusic.setOnClickListener { selectCategory(chipMusic, "Top hits 2026 music") }
        chipChill.setOnClickListener { selectCategory(chipChill, "Chill acoustic vibes") }
        chipWorkout.setOnClickListener { selectCategory(chipWorkout, "Workout motivation EDM") }
        chipFocus.setOnClickListener { selectCategory(chipFocus, "Deep focus study instrumental") }
        chipWrapped.setOnClickListener { selectCategory(chipWrapped, "Best songs of the year") }

        // Section 1: "To get you started" Featured Carousel
        featuredCarouselAdapter = FeaturedCarouselAdapter(mutableListOf()) { result, position ->
            val track = result.toTrack()
            val globalIndex = allLoadedResults.indexOfFirst { it.videoId == result.videoId }.coerceAtLeast(0)
            val queue = buildQueueFromResults(allLoadedResults, globalIndex)
            setQueueAndPlay(queue, 0, track)
        }
        homeFeaturedCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        PagerSnapHelper().attachToRecyclerView(homeFeaturedCarousel)
        homeFeaturedCarousel.adapter = featuredCarouselAdapter

        includeHome.findViewById<View>(R.id.btnSeeAllStarted)?.setOnClickListener {
            if (allLoadedResults.isNotEmpty()) {
                homeSectionTitle.text = "All Discovery Tracks"
                homeTrackAdapter.updateResults(allLoadedResults)
            }
        }

        // Section 2: "Popular songs" Horizontal Reel
        popularSongAdapter = PopularSongAdapter(mutableListOf()) { result, position ->
            val track = result.toTrack()
            val globalIndex = allLoadedResults.indexOfFirst { it.videoId == result.videoId }.coerceAtLeast(0)
            val queue = buildQueueFromResults(allLoadedResults, globalIndex)
            setQueueAndPlay(queue, 0, track)
        }
        homePopularSongsList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        homePopularSongsList.adapter = popularSongAdapter

        includeHome.findViewById<View>(R.id.btnSeeAllPopular)?.setOnClickListener {
            if (allLoadedResults.isNotEmpty()) {
                homeSectionTitle.text = "Popular Tracks Feed"
                homeTrackAdapter.updateResults(allLoadedResults)
            }
        }

        // Section 3: Recently Played List
        homeRecentlyPlayedAdapter = RecentlyPlayedAdapter(mutableListOf()) { track ->
            val historyTracks = historyManager.getHistory().map { it.track }
            val index = historyTracks.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
            setQueueAndPlay(historyTracks, index, track)
        }
        homeRecentlyPlayedList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        homeRecentlyPlayedList.adapter = homeRecentlyPlayedAdapter
        refreshRecentlyPlayed()

        // Section 4: Curated Mix Tiles
        fun wireMix(viewId: Int, query: String) {
            includeHome.findViewById<View>(viewId)?.setOnClickListener {
                selectNavTab(1)
                searchTabInput.setText(query)
                performSearch(query)
            }
        }
        wireMix(R.id.mixEnergyBoost, "High energy boost workout beats")
        wireMix(R.id.mixDeepFocus, "Deep focus ambient instrumental")
        wireMix(R.id.mixChillVibes, "Chill beats late night lo-fi")
        wireMix(R.id.mixIndieDiscoveries, "Fresh indie discoveries alternative")
        wireMix(R.id.mixDesiHits, "Desi Bollywood party hits")

        includeHome.findViewById<View>(R.id.btnSeeAllMixes)?.setOnClickListener {
            selectNavTab(1)
            searchTabInput.setText("Curated Music Mixes")
            performSearch("Curated Music Mixes")
        }

        // Section 5: Discovery Feed (Trending Tracks List)
        homeTrackAdapter = TrackAdapter(mutableListOf()) { result, position ->
            val track = result.toTrack()
            val globalIndex = allLoadedResults.indexOfFirst { it.videoId == result.videoId }.coerceAtLeast(0)
            val queue = buildQueueFromResults(allLoadedResults, globalIndex)
            setQueueAndPlay(queue, 0, track)
        }
        homeTrendingList.layoutManager = LinearLayoutManager(this)
        homeTrendingList.isNestedScrollingEnabled = false
        homeTrendingList.adapter = homeTrackAdapter

        btnHomeSeeAll.setOnClickListener {
            if (allLoadedResults.isNotEmpty()) {
                homeSectionTitle.text = "Complete Discovery Feed"
                homeTrackAdapter.updateResults(allLoadedResults)
            } else {
                loadTrendingTracks()
            }
        }
    }

    private fun refreshRecentlyPlayed() {
        if (!::homeRecentlyPlayedAdapter.isInitialized || !::homeRecentlyPlayedSection.isInitialized) return
        val recentTracks = historyManager.getHistory().map { it.track }.take(10)
        if (recentTracks.isNotEmpty()) {
            homeRecentlyPlayedSection.visibility = View.VISIBLE
            homeRecentlyPlayedAdapter.updateItems(recentTracks)
        } else {
            homeRecentlyPlayedSection.visibility = View.GONE
        }
    }

    private fun loadTrendingTracks() {
        if (!authManager.isUserSignedIn()) {
            homeSectionTitle.text = "Trending Music"
            mainScope.launch {
                try {
                    val results = searchEngine.getTrendingMusic()
                    if (results.isNotEmpty()) {
                        displayTrendingResults(results)
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Guest trending music discovery failed: ${e.message}")
                }
            }
            return
        }

        homeSectionTitle.text = "Trending Now"
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
                if (results.isEmpty()) {
                    results = searchEngine.getTrendingMusic()
                }
                if (results.isNotEmpty()) {
                    displayTrendingResults(results)
                }
            } catch (e: Exception) {
                try {
                    val fallback = searchEngine.getTrendingMusic()
                    if (fallback.isNotEmpty()) {
                        displayTrendingResults(fallback)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun displayTrendingResults(results: List<SearchResult>) {
        allLoadedResults = results
        if (results.isEmpty()) {
            featuredCarouselAdapter.updateItems(emptyList())
            if (::homeFeaturedSection.isInitialized) {
                homeFeaturedSection.visibility = View.GONE
            }
            popularSongAdapter.updateItems(emptyList())
            if (::homePopularSongsSection.isInitialized) {
                homePopularSongsSection.visibility = View.GONE
            }
            homeTrackAdapter.updateResults(emptyList())
            return
        }

        // Section 1: Top 5 tracks go to the large "To get you started" featured carousel
        val featuredCount = minOf(5, results.size)
        val featuredItems = results.take(featuredCount)
        if (::homeFeaturedSection.isInitialized) {
            homeFeaturedSection.visibility = if (featuredItems.isNotEmpty()) View.VISIBLE else View.GONE
        }
        featuredCarouselAdapter.updateItems(featuredItems)

        // Section 2: Next tracks go to "Popular songs" horizontal reel
        val popularStart = featuredCount
        val popularCount = minOf(6, (results.size - popularStart).coerceAtLeast(0))
        val popularItems = if (popularCount > 0) results.subList(popularStart, popularStart + popularCount) else emptyList()
        if (::homePopularSongsSection.isInitialized) {
            homePopularSongsSection.visibility = if (popularItems.isNotEmpty()) View.VISIBLE else View.GONE
        }
        popularSongAdapter.updateItems(popularItems)

        // Section 5: Remaining tracks go to discovery feed list
        val feedStart = popularStart + popularCount
        val feedItems = if (feedStart < results.size) results.subList(feedStart, results.size) else results
        homeTrackAdapter.updateResults(feedItems)

        val current = queueManager.currentTrack()
        featuredCarouselAdapter.updatePlaybackState(current?.videoId, playbackService?.isPlaying == true)
    }

    // ─── Search Screen ────────────────────────────────────────────────────────

    private fun setupSearchScreen() {
        searchTrackAdapter = TrackAdapter(mutableListOf()) { result, position ->
            val track = result.toTrack()
            val queue = buildQueueFromResults(searchTrackAdapter.results, position)
            setQueueAndPlay(queue, 0, track)
        }
        searchTabResultsList.layoutManager = LinearLayoutManager(this)
        searchTabResultsList.adapter = searchTrackAdapter

        btnSearchClear.setOnClickListener {
            searchTabInput.setText("")
            btnSearchClear.visibility = View.GONE
            searchTabResultsList.visibility = View.GONE
            searchEmptyState.visibility = View.VISIBLE
            searchCountText.visibility = View.GONE
        }

        var searchJob: Job? = null
        searchTabInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val q = s?.toString()?.trim() ?: ""
                btnSearchClear.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                if (q.isNotBlank()) {
                    searchJob = mainScope.launch {
                        delay(550)
                        performSearch(q)
                    }
                } else {
                    searchTabResultsList.visibility = View.GONE
                    searchEmptyState.visibility = View.VISIBLE
                    searchCountText.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        searchTabInput.setOnEditorActionListener { _, _, _ ->
            val q = searchTabInput.text.toString().trim()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchTabInput.windowToken, 0)
            if (q.isNotBlank()) {
                performSearch(q)
            }
            true
        }

        // Quick Search Chips
        fun wireSearchChip(id: Int, query: String) {
            includeSearch.findViewById<View>(id).setOnClickListener {
                searchTabInput.setText(query)
                performSearch(query)
            }
        }
        wireSearchChip(R.id.chipTrendingPop, "Top 50 Pop Hits")
        wireSearchChip(R.id.chipLofiBeats, "Lo-Fi study chill beats")
        wireSearchChip(R.id.chipBollywood, "Top Bollywood hits")
        wireSearchChip(R.id.chipEdmEnergy, "EDM workout energy")
        wireSearchChip(R.id.chipAcoustic, "Acoustic coffee morning")
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return

        if (!authManager.isUserSignedIn()) {
            Toast.makeText(this, "Sign in to search music.", Toast.LENGTH_SHORT).show()
            selectNavTab(3)
            return
        }

        searchLoadingBar.visibility = View.VISIBLE
        searchEmptyState.visibility = View.GONE
        searchCountText.visibility = View.VISIBLE
        searchCountText.text = "Searching for \"$query\"…"

        mainScope.launch {
            try {
                var idToken = authManager.getIdToken(forceRefresh = false)
                var results: List<SearchResult> = emptyList()
                try {
                    results = searchEngine.search(query, idToken)
                } catch (e: SearchAuthException) {
                    idToken = authManager.getIdToken(forceRefresh = true)
                    results = searchEngine.search(query, idToken)
                }

                searchLoadingBar.visibility = View.GONE
                if (results.isNotEmpty()) {
                    searchCountText.text = "${results.size} tracks found"
                    searchTabResultsList.visibility = View.VISIBLE
                    searchEmptyState.visibility = View.GONE
                    searchTrackAdapter.updateResults(results)
                } else {
                    searchCountText.text = "No results found"
                    searchTabResultsList.visibility = View.GONE
                    searchEmptyState.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                searchLoadingBar.visibility = View.GONE
                searchCountText.text = "Search error"
                Toast.makeText(this@MainActivity, e.message ?: "Failed to retrieve search results", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Library Screen ───────────────────────────────────────────────────────

    private fun setupLibraryScreen() {
        libraryPlaylistAdapter = PlaylistAdapter(
            playlists = mutableListOf(),
            onPlaylistClick = { playlist ->
                showPlaylistDetail(playlist)
            },
            onPlaylistPlayClick = { playlist ->
                playPlaylist(playlist)
            }
        )

        libraryTrackAdapter = QueueTrackAdapter(
            tracks = mutableListOf(),
            onTrackClick = { track, index ->
                playTrack(track)
            },
            onRemoveClick = { track, index ->
                when (currentLibraryTab) {
                    1 -> {
                        favoritesManager.removeFavorite(track.videoId)
                        refreshLibraryTab()
                        updateLikeButtonState(playbackService?.queueManager?.currentTrack() ?: queueManager.currentTrack())
                    }
                    2 -> {
                        historyManager.removeEntry(track.videoId)
                        refreshLibraryTab()
                    }
                    3 -> {
                        val s = playbackService
                        s?.queueManager?.removeAt(index)
                        queueManager.removeAt(index)
                        refreshLibraryTab()
                    }
                }
            }
        )
        libraryRecyclerView.layoutManager = LinearLayoutManager(this)
        libraryRecyclerView.adapter = libraryPlaylistAdapter

        tabPlaylists.setOnClickListener { switchLibraryTab(0) }
        tabFavorites.setOnClickListener { switchLibraryTab(1) }
        tabHistory.setOnClickListener { switchLibraryTab(2) }
        tabQueue.setOnClickListener { switchLibraryTab(3) }

        btnNewPlaylistHeader.setOnClickListener {
            triggerHaptic(it)
            showCreatePlaylistDialog()
        }

        btnLibraryEmptyAction.setOnClickListener {
            triggerHaptic(it)
            if (currentLibraryTab == 0) {
                showCreatePlaylistDialog()
            } else if (currentLibraryTab == 1 || currentLibraryTab == 2) {
                selectNavTab(0)
            } else {
                selectNavTab(1)
            }
        }

        btnLibraryAction.setOnClickListener {
            when (currentLibraryTab) {
                1 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Clear Favorites")
                        .setMessage("Are you sure you want to remove all liked songs?")
                        .setPositiveButton("Clear") { _, _ ->
                            favoritesManager.clearFavorites()
                            refreshLibraryTab()
                            updateProfileView()
                            updateLikeButtonState(playbackService?.queueManager?.currentTrack() ?: queueManager.currentTrack())
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                2 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Clear Listening History")
                        .setMessage("Are you sure you want to clear your listening history?")
                        .setPositiveButton("Clear") { _, _ ->
                            historyManager.clearHistory()
                            refreshLibraryTab()
                            updateProfileView()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                3 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Clear Queue")
                        .setMessage("Are you sure you want to clear the upcoming queue?")
                        .setPositiveButton("Clear") { _, _ ->
                            playbackService?.queueManager?.clearQueue()
                            queueManager.clearQueue()
                            refreshLibraryTab()
                            updateProfileView()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun switchLibraryTab(tabIndex: Int) {
        currentLibraryTab = tabIndex

        val activeBg = R.drawable.bg_category_pill_active
        val inactiveBg = R.drawable.bg_chip_pill

        tabPlaylists.setBackgroundResource(if (tabIndex == 0) activeBg else inactiveBg)
        tabFavorites.setBackgroundResource(if (tabIndex == 1) activeBg else inactiveBg)
        tabHistory.setBackgroundResource(if (tabIndex == 2) activeBg else inactiveBg)
        tabQueue.setBackgroundResource(if (tabIndex == 3) activeBg else inactiveBg)

        tabPlaylists.setTextColor(getColor(if (tabIndex == 0) R.color.text_on_cyan else R.color.text_muted))
        tabFavorites.setTextColor(getColor(if (tabIndex == 1) R.color.text_on_cyan else R.color.text_muted))
        tabHistory.setTextColor(getColor(if (tabIndex == 2) R.color.text_on_cyan else R.color.text_muted))
        tabQueue.setTextColor(getColor(if (tabIndex == 3) R.color.text_on_cyan else R.color.text_muted))

        refreshLibraryTab()
    }

    private fun refreshLibraryTab() {
        when (currentLibraryTab) {
            0 -> {
                btnNewPlaylistHeader.visibility = View.VISIBLE
                btnLibraryAction.visibility = View.GONE
                libraryRecyclerView.layoutManager = LinearLayoutManager(this)
                libraryRecyclerView.adapter = libraryPlaylistAdapter
                val playlists = playlistManager.getPlaylists()
                libraryCountLabel.text = if (playlists.size == 1) "1 Personal Playlist" else "${playlists.size} Personal Playlists"
                if (playlists.isEmpty()) {
                    libraryEmptyState.visibility = View.VISIBLE
                    libraryRecyclerView.visibility = View.GONE
                    libraryEmptyIcon.setImageResource(R.drawable.ic_playlist)
                    libraryEmptyTitle.text = "No playlists yet"
                    libraryEmptyDesc.text = "Create your first personal playlist to organize your favorite music."
                    btnLibraryEmptyAction.visibility = View.VISIBLE
                    btnLibraryEmptyAction.text = "+ Create Playlist"
                } else {
                    libraryEmptyState.visibility = View.GONE
                    libraryRecyclerView.visibility = View.VISIBLE
                    libraryPlaylistAdapter.setPlaylists(playlists)
                }
            }
            1 -> {
                btnNewPlaylistHeader.visibility = View.GONE
                libraryRecyclerView.layoutManager = LinearLayoutManager(this)
                libraryRecyclerView.adapter = libraryTrackAdapter
                val favorites = favoritesManager.getFavorites()
                btnLibraryAction.visibility = if (favorites.isNotEmpty()) View.VISIBLE else View.GONE
                libraryCountLabel.text = "${favorites.size} Liked Songs"
                if (favorites.isEmpty()) {
                    libraryEmptyState.visibility = View.VISIBLE
                    libraryRecyclerView.visibility = View.GONE
                    libraryEmptyIcon.setImageResource(R.drawable.ic_heart)
                    libraryEmptyTitle.text = "No favorites yet"
                    libraryEmptyDesc.text = "Tap the heart icon on any playing track to save it here."
                    btnLibraryEmptyAction.visibility = View.VISIBLE
                    btnLibraryEmptyAction.text = "Explore Songs"
                } else {
                    libraryEmptyState.visibility = View.GONE
                    libraryRecyclerView.visibility = View.VISIBLE
                    libraryTrackAdapter.setTracks(favorites)
                    val currentId = playbackService?.queueManager?.currentTrack()?.videoId ?: queueManager.currentTrack()?.videoId
                    libraryTrackAdapter.setCurrentPlayingId(currentId)
                }
            }
            2 -> {
                btnNewPlaylistHeader.visibility = View.GONE
                libraryRecyclerView.layoutManager = LinearLayoutManager(this)
                libraryRecyclerView.adapter = libraryTrackAdapter
                val history = historyManager.getHistory().map { it.track }
                btnLibraryAction.visibility = if (history.isNotEmpty()) View.VISIBLE else View.GONE
                libraryCountLabel.text = "${history.size} Recently Played Tracks"
                if (history.isEmpty()) {
                    libraryEmptyState.visibility = View.VISIBLE
                    libraryRecyclerView.visibility = View.GONE
                    libraryEmptyIcon.setImageResource(R.drawable.ic_history)
                    libraryEmptyTitle.text = "No listening history"
                    libraryEmptyDesc.text = "Tracks will appear here as soon as you start listening."
                    btnLibraryEmptyAction.visibility = View.VISIBLE
                    btnLibraryEmptyAction.text = "Discover Music"
                } else {
                    libraryEmptyState.visibility = View.GONE
                    libraryRecyclerView.visibility = View.VISIBLE
                    libraryTrackAdapter.setTracks(history)
                    val currentId = playbackService?.queueManager?.currentTrack()?.videoId ?: queueManager.currentTrack()?.videoId
                    libraryTrackAdapter.setCurrentPlayingId(currentId)
                }
            }
            3 -> {
                btnNewPlaylistHeader.visibility = View.GONE
                libraryRecyclerView.layoutManager = LinearLayoutManager(this)
                libraryRecyclerView.adapter = libraryTrackAdapter
                val queue = playbackService?.queueManager?.queue?.value ?: queueManager.queue.value ?: emptyList()
                btnLibraryAction.visibility = if (queue.isNotEmpty()) View.VISIBLE else View.GONE
                libraryCountLabel.text = "${queue.size} Tracks in Playback Queue"
                if (queue.isEmpty()) {
                    libraryEmptyState.visibility = View.VISIBLE
                    libraryRecyclerView.visibility = View.GONE
                    libraryEmptyIcon.setImageResource(R.drawable.ic_queue)
                    libraryEmptyTitle.text = "Queue is empty"
                    libraryEmptyDesc.text = "Play any song from Home or Search to queue up music."
                    btnLibraryEmptyAction.visibility = View.GONE
                } else {
                    libraryEmptyState.visibility = View.GONE
                    libraryRecyclerView.visibility = View.VISIBLE
                    libraryTrackAdapter.setTracks(queue)
                    val currentId = playbackService?.queueManager?.currentTrack()?.videoId ?: queueManager.currentTrack()?.videoId
                    libraryTrackAdapter.setCurrentPlayingId(currentId)
                }
            }
        }
    }

    // ─── Profile Screen ───────────────────────────────────────────────────────

    private fun setupProfileScreen() {
        btnEditProfile.setOnClickListener {
            triggerHaptic(it)
            showEditProfileDialog()
        }

        btnProfileAuth.setOnClickListener {
            triggerHaptic(it)
            if (authManager.isUserSignedIn()) {
                val user = authManager.getCurrentUser()
                val emailText = user?.email?.let { " (${it})" } ?: ""
                AlertDialog.Builder(this)
                    .setTitle("Sign Out")
                    .setMessage("Are you sure you want to sign out of LumiOne$emailText?")
                    .setPositiveButton("Sign Out") { _, _ ->
                        authManager.signOut {
                            Toast.makeText(this, "Signed out of LumiOne", Toast.LENGTH_SHORT).show()
                            updateProfileView()
                            loadTrendingTracks()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                googleSignInLauncher.launch(authManager.getSignInIntent())
            }
        }

        statCardPlaylists.setOnClickListener {
            triggerHaptic(it)
            selectNavTab(2)
            selectLibraryTab(0)
        }

        statCardFavorites.setOnClickListener {
            triggerHaptic(it)
            selectNavTab(2)
            selectLibraryTab(1)
        }

        statCardHistory.setOnClickListener {
            triggerHaptic(it)
            selectNavTab(2)
            selectLibraryTab(2)
        }

        statCardQueue.setOnClickListener {
            triggerHaptic(it)
            showQueueSheet()
        }

        rowShortcutPlaylists.setOnClickListener {
            triggerHaptic(it)
            selectNavTab(2)
            selectLibraryTab(0)
        }

        rowShortcutFavorites.setOnClickListener {
            triggerHaptic(it)
            selectNavTab(2)
            selectLibraryTab(1)
        }

        rowShortcutHistory.setOnClickListener {
            triggerHaptic(it)
            selectNavTab(2)
            selectLibraryTab(2)
        }

        rowShortcutQueue.setOnClickListener {
            triggerHaptic(it)
            showQueueSheet()
        }

        btnProfileCreatePlaylist.setOnClickListener {
            triggerHaptic(it)
            showCreatePlaylistDialog()
        }

        profilePlaylistAdapter = PlaylistAdapter(
            playlists = mutableListOf(),
            onPlaylistClick = { playlist ->
                showPlaylistDetail(playlist)
            },
            onPlaylistPlayClick = { playlist ->
                playPlaylist(playlist)
            }
        )
        profilePlaylistsRecycler.layoutManager = LinearLayoutManager(this)
        profilePlaylistsRecycler.adapter = profilePlaylistAdapter

        rowPrefShuffle.setOnClickListener {
            triggerHaptic(it)
            toggleShuffle()
        }

        rowPrefRepeat.setOnClickListener {
            triggerHaptic(it)
            cycleRepeat()
        }

        rowPrefAutoplay.setOnClickListener {
            triggerHaptic(it)
            val current = appPrefs.getBoolean("pref_autoplay", true)
            val next = !current
            appPrefs.edit().putBoolean("pref_autoplay", next).apply()
            updateProfileView()
            Toast.makeText(this, if (next) "Autoplay enabled" else "Autoplay disabled", Toast.LENGTH_SHORT).show()
        }

        rowPrefHaptic.setOnClickListener {
            val current = appPrefs.getBoolean("pref_haptic", true)
            val next = !current
            appPrefs.edit().putBoolean("pref_haptic", next).apply()
            if (next) {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            updateProfileView()
            Toast.makeText(this, if (next) "Haptic touch enabled" else "Haptic touch disabled", Toast.LENGTH_SHORT).show()
        }

        rowClearHistory.setOnClickListener {
            triggerHaptic(it)
            AlertDialog.Builder(this)
                .setTitle("Clear Listening History")
                .setMessage("Are you sure you want to clear your listening history? This will remove all your recently played tracks from this device. Your liked songs will not be affected.")
                .setPositiveButton("Clear History") { _, _ ->
                    historyManager.clearHistory()
                    Toast.makeText(this, "Listening history cleared", Toast.LENGTH_SHORT).show()
                    updateProfileView()
                    refreshRecentlyPlayed()
                    if (currentNavTab == 2 && currentLibraryTab == 2) refreshLibraryTab()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        rowClearFavorites.setOnClickListener {
            triggerHaptic(it)
            AlertDialog.Builder(this)
                .setTitle("Clear Liked Songs")
                .setMessage("Are you sure you want to clear all liked songs? This will remove all tracks from your favorites collection. Your listening history will not be affected.")
                .setPositiveButton("Clear Liked Songs") { _, _ ->
                    favoritesManager.clearFavorites()
                    Toast.makeText(this, "Liked songs cleared", Toast.LENGTH_SHORT).show()
                    updateProfileView()
                    updateLikeButtonState(playbackService?.queueManager?.currentTrack() ?: queueManager.currentTrack())
                    if (currentNavTab == 2 && currentLibraryTab == 1) refreshLibraryTab()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateProfileView() {
        val user = authManager.getCurrentUser()
        val customName = appPrefs.getString("pref_custom_display_name", null)?.trim()

        val effectiveDisplayName = if (!customName.isNullOrBlank()) {
            customName
        } else {
            user?.displayName?.ifBlank { "LumiOne Listener" } ?: "Guest Listener"
        }

        profileDisplayName.text = effectiveDisplayName
        if (::drawerUserName.isInitialized) {
            drawerUserName.text = effectiveDisplayName
        }

        if (user != null) {
            profileEmail.text = user.email ?: "Signed in with Google"
            profileAuthText.text = "Sign Out"
            profileAuthIcon.setImageResource(R.drawable.ic_close)
            profileAuthIcon.setColorFilter(getColor(R.color.coral))

            if (::drawerUserEmail.isInitialized) {
                drawerUserEmail.text = user.email ?: "Signed in with Google"
            }

            val photoUrl = user.photoUrl?.toString()
            if (!photoUrl.isNullOrBlank()) {
                profileAvatar.setPadding(0, 0, 0, 0)
                Glide.with(this)
                    .load(photoUrl)
                    .circleCrop()
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(profileAvatar)
                profileAvatar.clearColorFilter()

                if (::drawerAvatar.isInitialized) {
                    drawerAvatar.setPadding(0, 0, 0, 0)
                    Glide.with(this)
                        .load(photoUrl)
                        .circleCrop()
                        .placeholder(R.drawable.bg_album_art_placeholder)
                        .into(drawerAvatar)
                    drawerAvatar.clearColorFilter()
                }
            } else {
                val pad = (14 * resources.displayMetrics.density).toInt()
                profileAvatar.setPadding(pad, pad, pad, pad)
                profileAvatar.setImageResource(R.drawable.ic_profile)
                profileAvatar.setColorFilter(getColor(R.color.text_muted))

                if (::drawerAvatar.isInitialized) {
                    val dpad = (8 * resources.displayMetrics.density).toInt()
                    drawerAvatar.setPadding(dpad, dpad, dpad, dpad)
                    drawerAvatar.setImageResource(R.drawable.ic_profile)
                    drawerAvatar.setColorFilter(getColor(R.color.text_muted))
                }
            }
        } else {
            profileEmail.text = "Sign in to sync your favorites & stream"
            profileAuthText.text = "Sign In with Google"
            profileAuthIcon.setImageResource(R.drawable.ic_google)
            profileAuthIcon.clearColorFilter()

            if (::drawerUserEmail.isInitialized) {
                drawerUserEmail.text = "Tap to manage account"
            }

            val pad = (14 * resources.displayMetrics.density).toInt()
            profileAvatar.setPadding(pad, pad, pad, pad)
            profileAvatar.setImageResource(R.drawable.ic_profile)
            profileAvatar.setColorFilter(getColor(R.color.text_muted))

            if (::drawerAvatar.isInitialized) {
                val dpad = (8 * resources.displayMetrics.density).toInt()
                drawerAvatar.setPadding(dpad, dpad, dpad, dpad)
                drawerAvatar.setImageResource(R.drawable.ic_profile)
                drawerAvatar.setColorFilter(getColor(R.color.text_muted))
            }
        }

        val playlists = playlistManager.getPlaylists()
        val playlistCount = playlists.size
        val favCount = favoritesManager.getFavorites().size
        val histCount = historyManager.getHistory().size
        val qCount = queueManager.queue.value?.size ?: 0

        if (::statPlaylistsCount.isInitialized) {
            statPlaylistsCount.text = playlistCount.toString()
        }
        statFavoritesCount.text = favCount.toString()
        statHistoryCount.text = histCount.toString()
        if (::statQueueCount.isInitialized) {
            statQueueCount.text = qCount.toString()
        }

        if (::drawerFavoritesCount.isInitialized) {
            drawerFavoritesCount.text = favCount.toString()
        }
        if (::drawerHistoryCount.isInitialized) {
            drawerHistoryCount.text = histCount.toString()
        }

        if (::subtitleShortcutPlaylists.isInitialized) {
            subtitleShortcutPlaylists.text = if (playlistCount == 1) "1 personal playlist" else "$playlistCount personal playlists"
        }
        if (::subtitleShortcutFavorites.isInitialized) {
            subtitleShortcutFavorites.text = "$favCount saved tracks"
        }
        if (::subtitleShortcutHistory.isInitialized) {
            subtitleShortcutHistory.text = "$histCount played tracks"
        }
        if (::subtitleShortcutQueue.isInitialized) {
            subtitleShortcutQueue.text = "$qCount tracks in queue"
        }

        if (::profilePlaylistsRecycler.isInitialized && ::profilePlaylistsEmpty.isInitialized) {
            if (playlists.isEmpty()) {
                profilePlaylistsEmpty.visibility = View.VISIBLE
                profilePlaylistsRecycler.visibility = View.GONE
            } else {
                profilePlaylistsEmpty.visibility = View.GONE
                profilePlaylistsRecycler.visibility = View.VISIBLE
                profilePlaylistAdapter.setPlaylists(playlists.take(5))
            }
        }
        val shuffleActive = queueManager.shuffleEnabled
        if (::txtPrefShuffleStatus.isInitialized) {
            txtPrefShuffleStatus.text = if (shuffleActive) "Active" else "Off"
            txtPrefShuffleStatus.setTextColor(getColor(if (shuffleActive) R.color.primary_cyan else R.color.text_dim))
        }

        val repeatMode = queueManager.repeatMode
        if (::txtPrefRepeatStatus.isInitialized) {
            txtPrefRepeatStatus.text = when (repeatMode) {
                RepeatMode.NONE -> "Off"
                RepeatMode.ALL -> "Repeat All"
                RepeatMode.ONE -> "Repeat One"
            }
            txtPrefRepeatStatus.setTextColor(getColor(if (repeatMode != RepeatMode.NONE) R.color.primary_cyan else R.color.text_dim))
        }

        val autoplayActive = appPrefs.getBoolean("pref_autoplay", true)
        if (::txtPrefAutoplayStatus.isInitialized) {
            txtPrefAutoplayStatus.text = if (autoplayActive) "Enabled" else "Disabled"
            txtPrefAutoplayStatus.setTextColor(getColor(if (autoplayActive) R.color.primary_cyan else R.color.text_dim))
        }

        val hapticActive = appPrefs.getBoolean("pref_haptic", true)
        if (::txtPrefHapticStatus.isInitialized) {
            txtPrefHapticStatus.text = if (hapticActive) "Enabled" else "Disabled"
            txtPrefHapticStatus.setTextColor(getColor(if (hapticActive) R.color.primary_cyan else R.color.text_dim))
        }
    }

    private fun toggleShuffle() {
        val s = playbackService
        val newShuffle = !(s?.queueManager?.shuffleEnabled ?: queueManager.shuffleEnabled)
        queueManager.shuffleEnabled = newShuffle
        s?.queueManager?.shuffleEnabled = newShuffle
        appPrefs.edit().putBoolean("pref_shuffle", newShuffle).apply()
        updateShuffleIcon(newShuffle)
        updateProfileView()
    }

    private fun cycleRepeat() {
        val s = playbackService
        val nextMode = when (s?.queueManager?.repeatMode ?: queueManager.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        queueManager.repeatMode = nextMode
        s?.queueManager?.repeatMode = nextMode
        appPrefs.edit().putString("pref_repeat", nextMode.name).apply()
        updateRepeatIcon(nextMode)
        updateProfileView()
    }

    private fun updateShuffleIcon(active: Boolean) {
        btnFullShuffle.alpha = if (active) 1.0f else 0.4f
        btnFullShuffle.setColorFilter(getColor(if (active) R.color.primary_cyan else R.color.text_dim))
    }

    private fun updateRepeatIcon(mode: RepeatMode) {
        val isActive = mode != RepeatMode.NONE
        btnFullRepeat.alpha = if (isActive) 1.0f else 0.4f
        btnFullRepeat.setColorFilter(getColor(if (isActive) R.color.primary_cyan else R.color.text_dim))
    }

    // ─── Now Playing Screen ───────────────────────────────────────────────────

    private fun setupNowPlayingScreen() {
        btnCollapseNowPlaying.setOnClickListener { hideFullPlayer() }

        btnNowPlayingQueue.setOnClickListener {
            showQueueSheet()
        }

        btnNowPlayingAddToPlaylist.setOnClickListener {
            triggerHaptic(it)
            val track = playbackService?.queueManager?.currentTrack() ?: queueManager.currentTrack()
            if (track != null) {
                showAddToPlaylistDialog(track)
            } else {
                Toast.makeText(this, "No track currently playing", Toast.LENGTH_SHORT).show()
            }
        }

        btnNowPlayingLike.setOnClickListener {
            val track = playbackService?.queueManager?.currentTrack() ?: queueManager.currentTrack()
            if (track != null) {
                val isFav = favoritesManager.toggleFavorite(track)
                btnNowPlayingLike.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120).withEndAction {
                    btnNowPlayingLike.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }.start()
                updateLikeButtonState(track)
                Toast.makeText(
                    this,
                    if (isFav) "Saved to Liked Songs ❤️" else "Removed from Liked Songs",
                    Toast.LENGTH_SHORT
                ).show()
                if (currentNavTab == 2 && currentLibraryTab == 1) refreshLibraryTab()
            }
        }

        btnFullPlayPause.setOnClickListener { togglePlayPause() }
        btnFullNext.setOnClickListener { playbackService?.skipNext() }
        btnFullPrev.setOnClickListener { playbackService?.skipPrevious() }

        // Restore saved shuffle and repeat preferences
        val savedShuffle = appPrefs.getBoolean("pref_shuffle", false)
        queueManager.shuffleEnabled = savedShuffle
        updateShuffleIcon(savedShuffle)

        val savedRepeatStr = appPrefs.getString("pref_repeat", RepeatMode.NONE.name)
        val savedRepeat = try { RepeatMode.valueOf(savedRepeatStr ?: "NONE") } catch (e: Exception) { RepeatMode.NONE }
        queueManager.repeatMode = savedRepeat
        updateRepeatIcon(savedRepeat)

        btnFullShuffle.setOnClickListener {
            triggerHaptic(it)
            toggleShuffle()
        }

        btnFullRepeat.setOnClickListener {
            triggerHaptic(it)
            cycleRepeat()
        }

        fullSeekBar.max = 1000
        fullSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isUserSeeking) {
                    val duration = playbackService?.durationMs ?: (queueManager.currentTrack()?.durationMs ?: 0L)
                    if (duration > 0) {
                        val target = (progress.toLong() * duration) / 1000L
                        fullCurrentTime.text = formatMs(target)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                val progress = sb?.progress ?: 0
                val duration = playbackService?.durationMs ?: (queueManager.currentTrack()?.durationMs ?: 0L)
                if (duration > 0) {
                    val targetMs = (progress.toLong() * duration) / 1000L
                    playbackService?.seekTo(targetMs)
                }
            }
        })
    }

    private fun updateLikeButtonState(track: Track?) {
        if (track == null) {
            btnNowPlayingLike.setColorFilter(getColor(R.color.text_muted))
            return
        }
        val isFav = favoritesManager.isFavorite(track.videoId)
        btnNowPlayingLike.setColorFilter(getColor(if (isFav) R.color.coral else R.color.text_muted))
    }

    private fun showFullPlayer() {
        fullPlayerContainer.visibility = View.VISIBLE
        fullPlayerContainer.bringToFront()
        val isPlaying = playbackService?.isPlaying == true
        nowPlayingVinylDisc.setPlaying(isPlaying)
        if (::nowPlayingWaveform.isInitialized) {
            nowPlayingWaveform.setPlaying(isPlaying)
        }
    }

    private fun hideFullPlayer() {
        fullPlayerContainer.visibility = View.GONE
    }

    // ─── Queue Sheet ──────────────────────────────────────────────────────────

    private fun setupQueueSheet() {
        queueSheetAdapter = QueueTrackAdapter(
            tracks = mutableListOf(),
            onTrackClick = { track, position ->
                val s = playbackService
                s?.queueManager?.jumpTo(position)?.let {
                    playTrack(it)
                } ?: playTrack(track)
                hideQueueSheet()
            },
            onRemoveClick = { track, position ->
                val s = playbackService
                s?.queueManager?.removeAt(position)
                queueManager.removeAt(position)
                refreshQueueSheet()
                if (currentNavTab == 2 && currentLibraryTab == 2) refreshLibraryTab()
            }
        )
        queueSheetRecycler.layoutManager = LinearLayoutManager(this)
        queueSheetRecycler.adapter = queueSheetAdapter

        btnQueueClose.setOnClickListener { hideQueueSheet() }

        btnQueueClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Queue")
                .setMessage("Clear all tracks from the queue?")
                .setPositiveButton("Clear") { _, _ ->
                    playbackService?.queueManager?.clearQueue()
                    queueManager.clearQueue()
                    refreshQueueSheet()
                    if (currentNavTab == 2 && currentLibraryTab == 2) refreshLibraryTab()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showQueueSheet() {
        refreshQueueSheet()
        queueSheetContainer.visibility = View.VISIBLE
        queueSheetContainer.bringToFront()
    }

    private fun hideQueueSheet() {
        queueSheetContainer.visibility = View.GONE
    }

    private fun refreshQueueSheet() {
        val queue = playbackService?.queueManager?.queue?.value ?: queueManager.queue.value ?: emptyList()
        val currentId = playbackService?.queueManager?.currentTrack()?.videoId ?: queueManager.currentTrack()?.videoId

        queueSubtitle.text = "${queue.size} tracks in queue"
        if (queue.isEmpty()) {
            queueEmptyState.visibility = View.VISIBLE
            queueSheetRecycler.visibility = View.GONE
            btnQueueClear.visibility = View.GONE
        } else {
            queueEmptyState.visibility = View.GONE
            queueSheetRecycler.visibility = View.VISIBLE
            btnQueueClear.visibility = View.VISIBLE
            queueSheetAdapter.setTracks(queue)
            queueSheetAdapter.setCurrentPlayingId(currentId)
        }
    }

    // ─── Playlist Detail & Management ─────────────────────────────────────────

    private fun setupPlaylistDetailScreen() {
        playlistTrackAdapter = PlaylistTrackAdapter(
            tracks = mutableListOf(),
            onTrackClick = { track, index ->
                val playlist = currentDetailPlaylist
                if (playlist != null) {
                    setQueueAndPlay(playlist.tracks, index, track)
                }
            },
            onRemoveClick = { track, _ ->
                val playlist = currentDetailPlaylist
                if (playlist != null) {
                    val removed = playlistManager.removeTrackFromPlaylist(playlist.id, track.videoId)
                    if (removed) {
                        Toast.makeText(this, "Removed from \"${playlist.name}\"", Toast.LENGTH_SHORT).show()
                        val updated = playlistManager.getPlaylist(playlist.id)
                        if (updated != null) {
                            updatePlaylistDetailView(updated)
                        }
                        updateProfileView()
                        if (currentNavTab == 2 && currentLibraryTab == 0) refreshLibraryTab()
                    }
                }
            }
        )
        rvPlaylistTracks.layoutManager = LinearLayoutManager(this)
        rvPlaylistTracks.adapter = playlistTrackAdapter

        btnPlaylistDetailBack.setOnClickListener {
            triggerHaptic(it)
            hidePlaylistDetail()
        }

        btnPlaylistDetailPlayAll.setOnClickListener {
            triggerHaptic(it)
            val playlist = currentDetailPlaylist ?: return@setOnClickListener
            playPlaylist(playlist, shuffle = false)
        }

        btnPlaylistDetailShuffle.setOnClickListener {
            triggerHaptic(it)
            val playlist = currentDetailPlaylist ?: return@setOnClickListener
            playPlaylist(playlist, shuffle = true)
        }

        btnPlaylistDetailAddSongs.setOnClickListener {
            triggerHaptic(it)
            val playlist = currentDetailPlaylist ?: return@setOnClickListener
            showAddSongsPicker(playlist)
        }

        btnPlaylistDetailEmptyAdd.setOnClickListener {
            triggerHaptic(it)
            val playlist = currentDetailPlaylist ?: return@setOnClickListener
            showAddSongsPicker(playlist)
        }

        btnPlaylistDetailRename.setOnClickListener {
            triggerHaptic(it)
            val playlist = currentDetailPlaylist ?: return@setOnClickListener
            showRenamePlaylistDialog(playlist)
        }

        btnPlaylistDetailDelete.setOnClickListener {
            triggerHaptic(it)
            val playlist = currentDetailPlaylist ?: return@setOnClickListener
            showDeletePlaylistDialog(playlist)
        }
    }

    private fun showPlaylistDetail(playlist: Playlist) {
        currentDetailPlaylist = playlist
        updatePlaylistDetailView(playlist)
        playlistDetailContainer.visibility = View.VISIBLE
        playlistDetailContainer.bringToFront()
        playlistDetailContainer.alpha = 0f
        playlistDetailContainer.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    private fun hidePlaylistDetail() {
        playlistDetailContainer.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                playlistDetailContainer.visibility = View.GONE
                currentDetailPlaylist = null
            }
            .start()
    }

    private fun updatePlaylistDetailView(playlist: Playlist) {
        currentDetailPlaylist = playlist
        txtPlaylistDetailTitle.text = playlist.name
        if (!playlist.description.isNullOrBlank()) {
            txtPlaylistDetailDesc.visibility = View.VISIBLE
            txtPlaylistDetailDesc.text = playlist.description
        } else {
            txtPlaylistDetailDesc.visibility = View.GONE
        }

        val count = playlist.tracks.size
        txtPlaylistDetailTrackCount.text = when {
            count == 0 -> "0 tracks • LumiOne Personal Playlist"
            count == 1 -> "1 track • LumiOne Personal Playlist"
            else -> "$count tracks • LumiOne Personal Playlist"
        }

        val firstThumb = playlist.tracks.firstOrNull()?.thumbnailUrl
        if (!firstThumb.isNullOrBlank()) {
            playlistDetailFallback.visibility = View.GONE
            playlistDetailArtwork.visibility = View.VISIBLE
            Glide.with(this)
                .load(firstThumb)
                .transform(CenterCrop(), RoundedCorners(24))
                .placeholder(R.drawable.bg_album_art_placeholder)
                .into(playlistDetailArtwork)
        } else {
            playlistDetailArtwork.visibility = View.GONE
            playlistDetailFallback.visibility = View.VISIBLE
        }

        if (playlist.tracks.isEmpty()) {
            playlistDetailEmptyState.visibility = View.VISIBLE
            rvPlaylistTracks.visibility = View.GONE
        } else {
            playlistDetailEmptyState.visibility = View.GONE
            rvPlaylistTracks.visibility = View.VISIBLE
            playlistTrackAdapter.setTracks(playlist.tracks)
        }
    }

    private fun playPlaylist(playlist: Playlist, shuffle: Boolean = false) {
        if (playlist.tracks.isEmpty()) {
            Toast.makeText(this, "Playlist has no tracks yet", Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = if (shuffle) playlist.tracks.shuffled() else playlist.tracks
        setQueueAndPlay(tracks, 0, tracks.first())
    }

    private fun showCreatePlaylistDialog(initialTrack: Track? = null, onCreated: ((Playlist) -> Unit)? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_playlist, null)
        val inputName = dialogView.findViewById<EditText>(R.id.editPlaylistName)
        val inputDesc = dialogView.findViewById<EditText>(R.id.editPlaylistDesc)
        val txtError = dialogView.findViewById<TextView>(R.id.playlistNameError)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancelPlaylistDialog)
        val btnCreate = dialogView.findViewById<TextView>(R.id.btnConfirmPlaylistDialog)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnCreate.setOnClickListener {
            val name = inputName.text.toString().trim()
            val desc = inputDesc.text.toString().trim()

            if (name.isEmpty()) {
                txtError.visibility = View.VISIBLE
                txtError.text = "Playlist name cannot be empty"
                return@setOnClickListener
            }

            txtError.visibility = View.GONE
            val playlist = playlistManager.createPlaylist(name, desc)
            if (initialTrack != null) {
                playlistManager.addTrackToPlaylist(playlist.id, initialTrack)
            }

            Toast.makeText(this, "Playlist \"$name\" created", Toast.LENGTH_SHORT).show()
            updateProfileView()
            if (currentNavTab == 2 && currentLibraryTab == 0) {
                refreshLibraryTab()
            }
            dialog.dismiss()
            onCreated?.invoke(playlistManager.getPlaylist(playlist.id) ?: playlist)
        }

        dialog.show()
    }

    private fun showEditProfileDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_profile, null)
        val inputName = dialogView.findViewById<EditText>(R.id.editDisplayNameInput)
        val txtError = dialogView.findViewById<TextView>(R.id.editDisplayNameError)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancelEditProfile)
        val btnSave = dialogView.findViewById<TextView>(R.id.btnSaveEditProfile)

        val currentCustom = appPrefs.getString("pref_custom_display_name", null)
        val user = authManager.getCurrentUser()
        val initialName = if (!currentCustom.isNullOrBlank()) {
            currentCustom
        } else {
            user?.displayName?.ifBlank { "LumiOne Listener" } ?: "Guest Listener"
        }

        inputName.setText(initialName)
        inputName.setSelection(initialName.length)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val trimmed = inputName.text.toString().trim()
            if (trimmed.isEmpty()) {
                txtError.visibility = View.VISIBLE
                txtError.text = "Display name cannot be empty"
                return@setOnClickListener
            }

            txtError.visibility = View.GONE
            appPrefs.edit().putString("pref_custom_display_name", trimmed).apply()
            updateProfileView()
            Toast.makeText(this, "Profile display name updated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAddToPlaylistDialog(track: Track) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_to_playlist, null)
        val subtitle = dialogView.findViewById<TextView>(R.id.dialogAddToPlaylistSubtitle)
        val btnCreateNew = dialogView.findViewById<View>(R.id.btnDialogCreateNewPlaylist)
        val txtNoPlaylists = dialogView.findViewById<TextView>(R.id.txtDialogNoPlaylists)
        val rvDialog = dialogView.findViewById<RecyclerView>(R.id.rvDialogPlaylists)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancelAddToPlaylist)

        subtitle.text = "Add \"${track.title}\" to personal playlist"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val playlists = playlistManager.getPlaylists()
        if (playlists.isEmpty()) {
            txtNoPlaylists.visibility = View.VISIBLE
            rvDialog.visibility = View.GONE
        } else {
            txtNoPlaylists.visibility = View.GONE
            rvDialog.visibility = View.VISIBLE
            rvDialog.layoutManager = LinearLayoutManager(this)
            rvDialog.adapter = DialogPlaylistAdapter(playlists) { selectedPlaylist ->
                val added = playlistManager.addTrackToPlaylist(selectedPlaylist.id, track)
                if (added) {
                    Toast.makeText(this, "Added to ${selectedPlaylist.name}", Toast.LENGTH_SHORT).show()
                    if (currentDetailPlaylist?.id == selectedPlaylist.id) {
                        val updated = playlistManager.getPlaylist(selectedPlaylist.id)
                        if (updated != null) updatePlaylistDetailView(updated)
                    }
                    updateProfileView()
                    if (currentNavTab == 2 && currentLibraryTab == 0) refreshLibraryTab()
                } else {
                    Toast.makeText(this, "Song is already in ${selectedPlaylist.name}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
        }

        btnCreateNew.setOnClickListener {
            dialog.dismiss()
            showCreatePlaylistDialog(initialTrack = track) { createdPlaylist ->
                Toast.makeText(this, "Added to ${createdPlaylist.name}", Toast.LENGTH_SHORT).show()
                updateProfileView()
                if (currentNavTab == 2 && currentLibraryTab == 0) refreshLibraryTab()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        val input = EditText(this)
        input.setText(playlist.name)
        input.setSelection(playlist.name.length)
        input.setTextColor(getColor(R.color.text_primary))
        input.setHintTextColor(getColor(R.color.text_dim))

        val container = FrameLayout(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Rename Playlist")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    playlistManager.renamePlaylist(playlist.id, newName)
                    val updated = playlistManager.getPlaylist(playlist.id)
                    if (updated != null) updatePlaylistDetailView(updated)
                    Toast.makeText(this, "Playlist renamed to \"$newName\"", Toast.LENGTH_SHORT).show()
                    updateProfileView()
                    if (currentNavTab == 2 && currentLibraryTab == 0) refreshLibraryTab()
                } else {
                    Toast.makeText(this, "Playlist name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePlaylistDialog(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete \"${playlist.name}\"?\n\nYour liked songs and listening history will not be affected.")
            .setPositiveButton("Delete") { _, _ ->
                playlistManager.deletePlaylist(playlist.id)
                Toast.makeText(this, "Deleted \"${playlist.name}\"", Toast.LENGTH_SHORT).show()
                hidePlaylistDetail()
                updateProfileView()
                if (currentNavTab == 2 && currentLibraryTab == 0) refreshLibraryTab()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddSongsPicker(playlist: Playlist) {
        val availableTracks = (favoritesManager.getFavorites() + historyManager.getHistory().map { it.track })
            .distinctBy { it.videoId }
            .filter { candidate -> playlist.tracks.none { it.videoId == candidate.videoId } }

        if (availableTracks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Add Songs to Playlist")
                .setMessage("All your liked and recently played songs are already in this playlist. Search for new songs to add?")
                .setPositiveButton("Search Songs") { _, _ ->
                    hidePlaylistDetail()
                    selectNavTab(1)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val titles = availableTracks.map { "${it.title} • ${it.artist}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add Songs to \"${playlist.name}\"")
            .setItems(titles) { _, which ->
                val trackToAdd = availableTracks[which]
                val added = playlistManager.addTrackToPlaylist(playlist.id, trackToAdd)
                if (added) {
                    Toast.makeText(this, "Added \"${trackToAdd.title}\"", Toast.LENGTH_SHORT).show()
                    val updated = playlistManager.getPlaylist(playlist.id)
                    if (updated != null) updatePlaylistDetailView(updated)
                    updateProfileView()
                    if (currentNavTab == 2 && currentLibraryTab == 0) refreshLibraryTab()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Mini Player ──────────────────────────────────────────────────────────

    private fun setupMiniPlayer() {
        val openFullPlayer = View.OnClickListener { showFullPlayer() }
        miniPlayerCard.setOnClickListener(openFullPlayer)
        miniAlbumArtView.setOnClickListener(openFullPlayer)
        miniTrackTitle.setOnClickListener(openFullPlayer)
        miniArtistName.setOnClickListener(openFullPlayer)

        miniPlayPauseBtn.setOnClickListener { togglePlayPause() }
        miniNextBtn.setOnClickListener { playbackService?.skipNext() }
    }

    // ─── Playback Coordination ────────────────────────────────────────────────

    private fun setQueueAndPlay(queue: List<Track>, startIndex: Int, trackToPlay: Track) {
        queueManager.setQueue(queue, startIndex)
        playbackService?.queueManager?.setQueue(queue, startIndex)
        playTrack(trackToPlay)
        showFullPlayer()
    }

    private fun playTrack(track: Track) {
        updateTrackUI(track)
        homeTrackAdapter.setCurrentPlayingTrackId(track.videoId)
        searchTrackAdapter.setCurrentPlayingTrackId(track.videoId)
        libraryTrackAdapter.setCurrentPlayingId(track.videoId)
        queueSheetAdapter.setCurrentPlayingId(track.videoId)
        updatePlayPauseIcons(false)
        playbackService?.loadAndPlay(track)

        // Record in Listening History
        historyManager.recordTrack(track)
        updateLikeButtonState(track)
    }

    private fun togglePlayPause() {
        val service = playbackService
        if (service == null) {
            Toast.makeText(this, "Audio player starting...", Toast.LENGTH_SHORT).show()
            return
        }

        if (service.isPlaying) {
            service.pausePlayback()
        } else {
            val currentTrack = service.queueManager.currentTrack() ?: queueManager.currentTrack()
            if (currentTrack != null) {
                if (currentTrack.streamUrl.isNotBlank()) {
                    service.resumePlayback()
                } else {
                    service.loadAndPlay(currentTrack)
                }
            } else {
                Toast.makeText(this, "Select a track to play", Toast.LENGTH_SHORT).show()
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

    // ─── PlaybackService.PlaybackServiceListener ──────────────────────────────

    override fun onTrackChanged(track: Track?) {
        runOnUiThread {
            if (track != null) {
                updateTrackUI(track)
                homeTrackAdapter.setCurrentPlayingTrackId(track.videoId)
                searchTrackAdapter.setCurrentPlayingTrackId(track.videoId)
                libraryTrackAdapter.setCurrentPlayingId(track.videoId)
                queueSheetAdapter.setCurrentPlayingId(track.videoId)
                historyManager.recordTrack(track)
                updateLikeButtonState(track)
                if (currentNavTab == 2) refreshLibraryTab()
            }
        }
    }

    override fun onPlayStateChanged(playing: Boolean) {
        runOnUiThread {
            updatePlayPauseIcons(playing)
        }
    }

    override fun onProgressUpdate(currentMs: Long, durationMs: Long, bufferedPct: Int) {
        runOnUiThread {
            if (!isUserSeeking) {
                val effDuration = if (durationMs > 0 && durationMs != androidx.media3.common.C.TIME_UNSET) {
                    durationMs
                } else {
                    queueManager.currentTrack()?.durationMs ?: 0L
                }

                fullCurrentTime.text = formatMs(currentMs)
                fullDurationTime.text = if (effDuration > 0) formatMs(effDuration) else "--:--"

                if (effDuration > 0) {
                    val progress = ((currentMs * 1000) / effDuration).toInt().coerceIn(0, 1000)
                    fullSeekBar.progress = progress
                    miniProgressBar.progress = progress
                } else {
                    fullSeekBar.progress = 0
                    miniProgressBar.progress = 0
                }
            }
        }
    }

    override fun onPlayerReady() {
        runOnUiThread {
            updatePlayPauseIcons(true)
        }
    }

    override fun onError(code: Int) {
        runOnUiThread {
            if (code != 0) {
                Toast.makeText(this, "Playback error (code: $code)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStreamUnavailable(track: Track, reason: String) {
        runOnUiThread {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        }
    }

    // ─── UI Updates ───────────────────────────────────────────────────────────

    private fun updateTrackUI(track: Track) {
        miniTrackTitle.text = track.title
        miniArtistName.text = track.artist
        fullTrackTitle.text = track.title
        fullArtistName.text = track.artist
        fullCurrentTime.text = "0:00"
        fullDurationTime.text = if (track.durationMs > 0) formatMs(track.durationMs) else "--:--"
        fullSeekBar.progress = 0
        miniProgressBar.progress = 0
        miniPlayerCard.visibility = View.VISIBLE
        miniProgressBar.visibility = View.VISIBLE

        fullQualityBadge.text = "${track.provider.uppercase()} HD • 320 kbps"

        if (track.thumbnailUrl.isNotBlank()) {
            Glide.with(this)
                .load(track.thumbnailUrl)
                .transform(CircleCrop())
                .placeholder(R.drawable.bg_album_art_placeholder)
                .into(miniAlbumArtView)

            nowPlayingVinylDisc.resetRotation()
            nowPlayingVinylDisc.loadArtworkUrl(track.thumbnailUrl)
            nowPlayingVinylDisc.setPlaying(playbackService?.isPlaying == true)
        } else {
            nowPlayingVinylDisc.resetRotation()
            nowPlayingVinylDisc.setArtworkBitmap(null)
            nowPlayingVinylDisc.setPlaying(playbackService?.isPlaying == true)
        }

        updateLikeButtonState(track)
        refreshRecentlyPlayed()
    }

    private fun updatePlayPauseIcons(playing: Boolean) {
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        miniPlayPauseBtn.setImageResource(icon)
        fullPlayPauseIcon.setImageResource(icon)

        val current = queueManager.currentTrack()
        featuredCarouselAdapter.updatePlaybackState(current?.videoId, playing)
        updateVinylRotation(playing)
    }

    private fun updateVinylRotation(playing: Boolean) {
        nowPlayingVinylDisc.setPlaying(playing)
        if (::nowPlayingWaveform.isInitialized) {
            nowPlayingWaveform.setPlaying(playing)
        }
    }

    override fun onStop() {
        super.onStop()
        nowPlayingVinylDisc.setPlaying(false)
        if (::nowPlayingWaveform.isInitialized) {
            nowPlayingWaveform.setPlaying(false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (playbackService?.isPlaying == true) {
            nowPlayingVinylDisc.setPlaying(true)
            if (::nowPlayingWaveform.isInitialized) {
                nowPlayingWaveform.setPlaying(true)
            }
        }
    }


    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    override fun onBackPressed() {
        if (::navDrawerContainer.isInitialized && navDrawerContainer.visibility == View.VISIBLE) {
            hideNavDrawer()
        } else if (::playlistDetailContainer.isInitialized && playlistDetailContainer.visibility == View.VISIBLE) {
            hidePlaylistDetail()
        } else if (queueSheetContainer.visibility == View.VISIBLE) {
            hideQueueSheet()
        } else if (fullPlayerContainer.visibility == View.VISIBLE) {
            hideFullPlayer()
        } else if (currentNavTab != 0) {
            selectNavTab(0)
        } else {
            super.onBackPressed()
        }
    }
}

// ─── Track Adapter for Search & Trending (RecyclerView) ───────────────────────

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
                    .transform(CircleCrop())
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
    streamUrl = streamUrl,
    provider = provider,
    providerId = providerId,
    youtubeVideoId = videoId
)

// ─── Recently Played Horizontal Reel Adapter ─────────────────────────────────

class RecentlyPlayedAdapter(
    val items: MutableList<Track>,
    private val onItemClick: (Track) -> Unit
) : RecyclerView.Adapter<RecentlyPlayedAdapter.RecentViewHolder>() {

    fun updateItems(newItems: List<Track>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recently_played, parent, false)
        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        val track = items[position]
        holder.bind(track) { onItemClick(track) }
    }

    override fun getItemCount(): Int = items.size

    class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val artwork: ImageView = itemView.findViewById(R.id.recentArtwork)
        private val title: TextView = itemView.findViewById(R.id.recentTitle)
        private val artist: TextView = itemView.findViewById(R.id.recentArtist)

        fun bind(track: Track, onClick: () -> Unit) {
            title.text = track.title
            artist.text = track.artist
            if (track.thumbnailUrl.isNotBlank()) {
                val cleanUrl = track.thumbnailUrl
                    .replace("/hqdefault.jpg", "/mqdefault.jpg")
                    .replace("/sddefault.jpg", "/mqdefault.jpg")
                Glide.with(itemView.context)
                    .load(cleanUrl)
                    .centerCrop()
                    .placeholder(R.drawable.bg_album_art_placeholder)
                    .into(artwork)
            } else {
                artwork.setImageResource(R.drawable.bg_album_art_placeholder)
            }
            itemView.setOnClickListener { onClick() }
        }
    }
}
