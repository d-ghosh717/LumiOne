/**
 * LumiOne Music Intelligence & Acoustic Profiling Engine
 *
 * Implements:
 * 1. Normalized acoustic vector space:
 *    [danceability-0.5, energy-0.5, valence-0.5, acousticness-0.5, liveness-0.5, speechiness-0.5, instrumentalness-0.5]
 * 2. Genre and subgenre classification with hierarchical taxonomy and real-world artist knowledge.
 * 3. Mood tags (melancholic, uplifting, energetic, chill, romantic, dark, serene, spiritual).
 * 4. Genre & Mood Eligibility Gates (strict pre-scoring rejection of incompatible styles).
 * 5. Weighted 0–100 point-scoring system with confidence-aware breakdown:
 *    - Acoustic similarity: 40 pts (gated/scaled by confidence)
 *    - Genre & subgenre compatibility: 25 pts
 *    - Mood & energy compatibility: 20 pts
 *    - Artist affinity & preference: 15 pts
 *    - Penalties for duplicates, recent tracks, and multi-artist saturation.
 */

// ─── 1. Genre Taxonomy & Families ───────────────────────────────────────
const GENRE_FAMILIES = {
    ELECTRONIC_EDM: 'electronic_edm',
    POP_MAINSTREAM: 'pop_mainstream',
    HIPHOP_RAP: 'hiphop_rap',
    ROCK_ALTERNATIVE: 'rock_alternative',
    CHILL_AMBIENT: 'chill_ambient',
    DEVOTIONAL_SPIRITUAL: 'devotional_spiritual',
    INDIAN_REGIONAL_DESI: 'indian_regional_desi',
    BOLLYWOOD_DESI: 'bollywood_desi',
    CLASSICAL_ACOUSTIC: 'classical_acoustic'
};

// Compatibility scores between genre families [0.0 = completely incompatible/gate reject, 1.0 = identical family]
const GENRE_COMPATIBILITY_MATRIX = {
    [GENRE_FAMILIES.ELECTRONIC_EDM]: {
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 1.0,
        [GENRE_FAMILIES.POP_MAINSTREAM]: 0.70, // Dance-pop, synthpop, electropop
        [GENRE_FAMILIES.CHILL_AMBIENT]: 0.35,  // Melodic chillout
        [GENRE_FAMILIES.HIPHOP_RAP]: 0.30,     // Trap, electronic hiphop
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 0.25,
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 0.10,
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 0.0, // HARD INCOMPATIBLE
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 0.0, // HARD INCOMPATIBLE
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 0.05
    },
    [GENRE_FAMILIES.POP_MAINSTREAM]: {
        [GENRE_FAMILIES.POP_MAINSTREAM]: 1.0,
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 0.70,
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 0.45,
        [GENRE_FAMILIES.HIPHOP_RAP]: 0.60,
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 0.55,
        [GENRE_FAMILIES.CHILL_AMBIENT]: 0.45,
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 0.0, // HARD INCOMPATIBLE
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 0.05,
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 0.25
    },
    [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: {
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 1.0,
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 0.60,
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 0.30,
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 0.20,
        [GENRE_FAMILIES.CHILL_AMBIENT]: 0.15,
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 0.0, // HARD INCOMPATIBLE
        [GENRE_FAMILIES.HIPHOP_RAP]: 0.0,     // HARD INCOMPATIBLE
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 0.0,
        [GENRE_FAMILIES.POP_MAINSTREAM]: 0.0
    },
    [GENRE_FAMILIES.CHILL_AMBIENT]: {
        [GENRE_FAMILIES.CHILL_AMBIENT]: 1.0,
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 0.75,
        [GENRE_FAMILIES.POP_MAINSTREAM]: 0.45,
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 0.35,
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 0.15,
        [GENRE_FAMILIES.HIPHOP_RAP]: 0.20,
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 0.20,
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 0.15,
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 0.0
    },
    [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: {
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 1.0,
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 0.60,
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 0.30,
        [GENRE_FAMILIES.POP_MAINSTREAM]: 0.15,
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 0.0, // HARD INCOMPATIBLE
        [GENRE_FAMILIES.HIPHOP_RAP]: 0.25,
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 0.0,
        [GENRE_FAMILIES.CHILL_AMBIENT]: 0.0,
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 0.10
    },
    [GENRE_FAMILIES.BOLLYWOOD_DESI]: {
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 1.0,
        [GENRE_FAMILIES.POP_MAINSTREAM]: 0.50,
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 0.50,
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 0.20,
        [GENRE_FAMILIES.CHILL_AMBIENT]: 0.25,
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 0.10,
        [GENRE_FAMILIES.HIPHOP_RAP]: 0.30,
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 0.15,
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 0.30
    },
    [GENRE_FAMILIES.HIPHOP_RAP]: {
        [GENRE_FAMILIES.HIPHOP_RAP]: 1.0,
        [GENRE_FAMILIES.POP_MAINSTREAM]: 0.60,
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 0.30,
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 0.25,
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 0.30,
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 0.0,
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 0.20,
        [GENRE_FAMILIES.CHILL_AMBIENT]: 0.20,
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 0.05
    },
    [GENRE_FAMILIES.ROCK_ALTERNATIVE]: {
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 1.0,
        [GENRE_FAMILIES.POP_MAINSTREAM]: 0.55,
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 0.25,
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 0.35,
        [GENRE_FAMILIES.HIPHOP_RAP]: 0.25,
        [GENRE_FAMILIES.CHILL_AMBIENT]: 0.20,
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 0.0,
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 0.15,
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 0.0
    },
    [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: {
        [GENRE_FAMILIES.CLASSICAL_ACOUSTIC]: 1.0,
        [GENRE_FAMILIES.CHILL_AMBIENT]: 0.75,
        [GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL]: 0.60,
        [GENRE_FAMILIES.POP_MAINSTREAM]: 0.30,
        [GENRE_FAMILIES.BOLLYWOOD_DESI]: 0.30,
        [GENRE_FAMILIES.ROCK_ALTERNATIVE]: 0.30,
        [GENRE_FAMILIES.ELECTRONIC_EDM]: 0.05,
        [GENRE_FAMILIES.HIPHOP_RAP]: 0.05,
        [GENRE_FAMILIES.INDIAN_REGIONAL_DESI]: 0.10
    }
};

// ─── 2. Multi-Artist Extraction Helper ──────────────────────────────────
function extractArtistList(rawArtist) {
    if (!rawArtist || typeof rawArtist !== 'string') return [];
    return rawArtist
        .split(/[,&/|]|(?:\s+(?:feat\.?|ft\.?|featuring|vs\.?|with|x)\s+)/i)
        .map(a => a.toLowerCase().replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim())
        .filter(a => a.length > 0 && !['vevo', 'topic', 'official', 'records'].includes(a));
}

// ─── 3. Comprehensive Artist & Taxonomy Knowledge Base ──────────────────
const ARTIST_GENRE_MAP = {
    // Electronic / EDM
    'alan walker': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['edm', 'melodic_house', 'dance_pop'],
        energy: 0.72, valence: 0.35, danceability: 0.65, acousticness: 0.15,
        mood: ['melancholic', 'uplifting'],
        relatedArtists: ['avicii', 'the chainsmokers', 'marshmello', 'kygo', 'martin garrix', 'k-391', 'alok', 'illenium']
    },
    'avicii': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['edm', 'progressive_house', 'melodic_edm'],
        energy: 0.78, valence: 0.55, danceability: 0.60, acousticness: 0.20,
        mood: ['uplifting', 'energetic'],
        relatedArtists: ['alan walker', 'kygo', 'martin garrix', 'alessoo', 'david guetta']
    },
    'the chainsmokers': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['dance_pop', 'edm', 'electropop'],
        energy: 0.70, valence: 0.45, danceability: 0.70, acousticness: 0.18,
        mood: ['melancholic', 'romantic'],
        relatedArtists: ['alan walker', 'marshmello', 'halsey', 'zedd']
    },
    'marshmello': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['future_bass', 'edm', 'dance_pop'],
        energy: 0.75, valence: 0.60, danceability: 0.72, acousticness: 0.15,
        mood: ['happy', 'energetic'],
        relatedArtists: ['the chainsmokers', 'alan walker', 'bastille', 'martin garrix']
    },
    'martin garrix': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['progressive_house', 'edm'],
        energy: 0.85, valence: 0.50, danceability: 0.65, acousticness: 0.10,
        mood: ['energetic', 'uplifting'],
        relatedArtists: ['avicii', 'tiesto', 'david guetta', 'alan walker']
    },
    'calvin harris': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['dance_pop', 'house', 'edm'],
        energy: 0.82, valence: 0.65, danceability: 0.75, acousticness: 0.12,
        mood: ['energetic', 'happy'],
        relatedArtists: ['david guetta', 'tiesto', 'alok', 'dua lipa']
    },
    'dj snake': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['trap_edm', 'dance_pop'],
        energy: 0.80, valence: 0.50, danceability: 0.75, acousticness: 0.15,
        mood: ['energetic'],
        relatedArtists: ['major lazer', 'diplo', 'skrillex']
    },
    'kygo': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['tropical_house', 'melodic_house'],
        energy: 0.65, valence: 0.55, danceability: 0.68, acousticness: 0.25,
        mood: ['chill', 'uplifting'],
        relatedArtists: ['alan walker', 'avicii', 'gryffin', 'sam feldt']
    },
    'alok': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['brazilian_bass', 'edm', 'house'],
        energy: 0.80, valence: 0.50, danceability: 0.75, acousticness: 0.12,
        mood: ['energetic'],
        relatedArtists: ['alan walker', 'tiesto', 'martin garrix']
    },
    'k-391': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['electro_house', 'edm'],
        energy: 0.78, valence: 0.45, danceability: 0.65, acousticness: 0.15,
        mood: ['uplifting', 'melancholic'],
        relatedArtists: ['alan walker', 'julie bergan']
    },
    'illenium': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['future_bass', 'melodic_dubstep'],
        energy: 0.72, valence: 0.30, danceability: 0.55, acousticness: 0.20,
        mood: ['melancholic', 'emotional'],
        relatedArtists: ['alan walker', 'seven lions', 'said the sky']
    },
    'zedd': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['electro_house', 'dance_pop'],
        energy: 0.82, valence: 0.60, danceability: 0.70, acousticness: 0.12,
        mood: ['energetic', 'uplifting'],
        relatedArtists: ['the chainsmokers', 'alessia cara', 'martin garrix']
    },
    'tiesto': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['house', 'edm'],
        energy: 0.85, valence: 0.55, danceability: 0.75, acousticness: 0.08,
        mood: ['energetic'],
        relatedArtists: ['martin garrix', 'david guetta', 'calvin harris']
    },
    'david guetta': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['dance_pop', 'edm', 'house'],
        energy: 0.84, valence: 0.65, danceability: 0.74, acousticness: 0.10,
        mood: ['energetic', 'happy'],
        relatedArtists: ['calvin harris', 'tiesto', 'sia']
    },
    'skrillex': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['dubstep', 'bass', 'edm'],
        energy: 0.88, valence: 0.45, danceability: 0.65, acousticness: 0.08,
        mood: ['energetic', 'dark'],
        relatedArtists: ['diplo', 'fred again', 'boys noize']
    },
    'fred again': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['uk_garage', 'house', 'electronic'],
        energy: 0.75, valence: 0.50, danceability: 0.70, acousticness: 0.20,
        mood: ['emotional', 'energetic'],
        relatedArtists: ['skrillex', 'four tet', 'bicep']
    },

    // Devotional / Spiritual
    'hariharan': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'chalisa', 'ghazal'],
        energy: 0.40, valence: 0.60, danceability: 0.35, acousticness: 0.80,
        mood: ['serene', 'spiritual', 'devotional'],
        relatedArtists: ['gulshan kumar', 'anup jalota', 'anuradha paudwal', 'suresh wadkar']
    },
    'gulshan kumar': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'aarti', 'chalisa'],
        energy: 0.45, valence: 0.65, danceability: 0.38, acousticness: 0.75,
        mood: ['devotional', 'spiritual'],
        relatedArtists: ['hariharan', 'anuradha paudwal', 'anup jalota']
    },
    'hansraj raghuwanshi': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['shiv_bhajan', 'devotional'],
        energy: 0.55, valence: 0.55, danceability: 0.45, acousticness: 0.65,
        mood: ['devotional', 'spiritual'],
        relatedArtists: ['lakhbir singh lakha', 'kailash kher']
    },
    'anup jalota': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'devotional'],
        energy: 0.35, valence: 0.60, danceability: 0.30, acousticness: 0.85,
        mood: ['devotional', 'serene'],
        relatedArtists: ['hariharan', 'anuradha paudwal', 'jagjit singh']
    },
    'sadhu tiwari': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['devotional', 'bhajan'],
        energy: 0.40, valence: 0.50, danceability: 0.35, acousticness: 0.75,
        mood: ['devotional'],
        relatedArtists: ['hansraj raghuwanshi', 'gulshan kumar']
    },
    'anuradha paudwal': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['aarti', 'bhajan'],
        energy: 0.40, valence: 0.60, danceability: 0.35, acousticness: 0.80,
        mood: ['devotional', 'serene'],
        relatedArtists: ['gulshan kumar', 'hariharan', 'suresh wadkar']
    },
    'lakhbir singh lakha': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'mata_bhajan'],
        energy: 0.55, valence: 0.60, danceability: 0.40, acousticness: 0.70,
        mood: ['devotional'],
        relatedArtists: ['gulshan kumar', 'hansraj raghuwanshi']
    },
    'suresh wadkar': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'mantra'],
        energy: 0.38, valence: 0.58, danceability: 0.32, acousticness: 0.82,
        mood: ['serene', 'devotional'],
        relatedArtists: ['hariharan', 'anuradha paudwal']
    },

    // Indian Regional / Haryanvi / Bhojpuri / Desi Party
    'mc square': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['haryanvi_hiphop', 'desi_hiphop'],
        energy: 0.82, valence: 0.70, danceability: 0.80, acousticness: 0.15,
        mood: ['energetic'],
        relatedArtists: ['diler kharkiya', 'renuka panwar', 'fazilpuria']
    },
    'dhanda nyoliwala': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['haryanvi_hiphop', 'desi_hiphop'],
        energy: 0.85, valence: 0.70, danceability: 0.82, acousticness: 0.15,
        mood: ['energetic'],
        relatedArtists: ['mc square', 'diler kharkiya']
    },
    'yo yo honey singh': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['desi_hiphop', 'punjabi_pop'],
        energy: 0.85, valence: 0.75, danceability: 0.85, acousticness: 0.12,
        mood: ['energetic', 'party'],
        relatedArtists: ['badshah', 'guru randhawa', 'raftaar']
    },
    'kr$na': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['desi_hiphop'],
        energy: 0.82, valence: 0.50, danceability: 0.78, acousticness: 0.15,
        mood: ['energetic', 'dark'],
        relatedArtists: ['raftaar', 'seedhe maut']
    },
    'krsna': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['desi_hiphop'],
        energy: 0.82, valence: 0.50, danceability: 0.78, acousticness: 0.15,
        mood: ['energetic', 'dark'],
        relatedArtists: ['raftaar', 'seedhe maut']
    },
    'khesari lal yadav': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['bhojpuri'],
        energy: 0.85, valence: 0.80, danceability: 0.85, acousticness: 0.15,
        mood: ['energetic'],
        relatedArtists: ['pawan singh', 'shilpi raj']
    },
    'pawan singh': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['bhojpuri'],
        energy: 0.82, valence: 0.78, danceability: 0.82, acousticness: 0.18,
        mood: ['energetic'],
        relatedArtists: ['khesari lal yadav', 'shilpi raj']
    },
    'diler kharkiya': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['haryanvi'],
        energy: 0.80, valence: 0.75, danceability: 0.82, acousticness: 0.18,
        mood: ['energetic'],
        relatedArtists: ['renuka panwar', 'mc square']
    },
    'renuka panwar': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['haryanvi'],
        energy: 0.82, valence: 0.75, danceability: 0.85, acousticness: 0.20,
        mood: ['energetic'],
        relatedArtists: ['diler kharkiya', 'mc square']
    },

    // Bollywood / Desi Filmi
    'arijit singh': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['bollywood_romantic', 'filmi'],
        energy: 0.52, valence: 0.42, danceability: 0.50, acousticness: 0.55,
        mood: ['romantic', 'melancholic'],
        relatedArtists: ['jubin nautiyal', 'atif aslam', 'pritam', 'shreya ghoshal']
    },
    'b praak': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['punjabi_sad', 'bollywood_sad'],
        energy: 0.60, valence: 0.32, danceability: 0.48, acousticness: 0.50,
        mood: ['sad', 'emotional'],
        relatedArtists: ['jaani', 'arijit singh', 'jubin nautiyal']
    },
    'jubin nautiyal': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['bollywood_romantic'],
        energy: 0.50, valence: 0.45, danceability: 0.52, acousticness: 0.60,
        mood: ['romantic'],
        relatedArtists: ['arijit singh', 'darshan raval', 'armaan malik']
    },
    'shreya ghoshal': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['filmi', 'bollywood_romantic'],
        energy: 0.48, valence: 0.50, danceability: 0.50, acousticness: 0.60,
        mood: ['romantic', 'sweet'],
        relatedArtists: ['arijit singh', 'sonu nigam', 'sunidhi chauhan']
    },
    'sonu nigam': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['filmi', 'bollywood_classic'],
        energy: 0.55, valence: 0.55, danceability: 0.52, acousticness: 0.50,
        mood: ['romantic', 'emotional'],
        relatedArtists: ['shreya ghoshal', 'arijit singh', 'udit narayan']
    },

    // Pop / Mainstream / Acoustic
    'passenger': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['indie_folk', 'acoustic_pop'],
        energy: 0.45, valence: 0.40, danceability: 0.55, acousticness: 0.75,
        mood: ['melancholic', 'romantic'],
        relatedArtists: ['ed sheeran', 'adele', 'john legend', 'james arthur', 'lewis capaldi']
    },
    'the weeknd': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['synthpop', 'contemporary_rnb', 'pop'],
        energy: 0.75, valence: 0.50, danceability: 0.68, acousticness: 0.15,
        mood: ['dark', 'melancholic', 'energetic'],
        relatedArtists: ['dua lipa', 'post malone', 'daft punk', 'zayn']
    },
    'dua lipa': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['dance_pop', 'nu_disco'],
        energy: 0.80, valence: 0.70, danceability: 0.78, acousticness: 0.10,
        mood: ['energetic', 'happy'],
        relatedArtists: ['calvin harris', 'the weeknd', 'bebe rexha']
    },
    'zayn': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['electropop', 'pop', 'rnb'],
        energy: 0.68, valence: 0.38, danceability: 0.60, acousticness: 0.22,
        mood: ['melancholic', 'emotional'],
        relatedArtists: ['sia', 'the weeknd', 'halsey']
    },
    'sia': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['electropop', 'pop'],
        energy: 0.72, valence: 0.52, danceability: 0.64, acousticness: 0.18,
        mood: ['uplifting', 'emotional'],
        relatedArtists: ['zayn', 'david guetta', 'adele']
    },
    'ed sheeran': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['pop', 'acoustic_pop'],
        energy: 0.58, valence: 0.60, danceability: 0.65, acousticness: 0.45,
        mood: ['romantic', 'happy'],
        relatedArtists: ['passenger', 'shawn mendes', 'adele', 'james arthur']
    },
    'adele': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['soul_pop', 'acoustic_ballad'],
        energy: 0.42, valence: 0.35, danceability: 0.48, acousticness: 0.70,
        mood: ['melancholic', 'emotional'],
        relatedArtists: ['passenger', 'sam smith', 'lewis capaldi', 'ed sheeran']
    },
    'john legend': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['soul', 'piano_pop'],
        energy: 0.45, valence: 0.45, danceability: 0.50, acousticness: 0.72,
        mood: ['romantic', 'emotional'],
        relatedArtists: ['adele', 'sam smith', 'alicia keys', 'ed sheeran']
    },
    'taylor swift': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['pop', 'synthpop', 'country_pop'],
        energy: 0.62, valence: 0.50, danceability: 0.65, acousticness: 0.30,
        mood: ['romantic', 'melancholic'],
        relatedArtists: ['olivia rodrigo', 'sabrina carpenter', 'ed sheeran']
    },
    'rihanna': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['pop', 'dance_pop', 'rnb'],
        energy: 0.70, valence: 0.55, danceability: 0.70, acousticness: 0.20,
        mood: ['uplifting'],
        relatedArtists: ['calvin harris', 'drake', 'beyonce']
    },
    'akon': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['dance_pop', 'rnb'],
        energy: 0.75, valence: 0.65, danceability: 0.75, acousticness: 0.18,
        mood: ['happy', 'energetic'],
        relatedArtists: ['pitbull', 'ne-yo', 'eminem']
    },

    // Hip-Hop / Rap
    'drake': {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        subgenres: ['hiphop', 'trap', 'melodic_rap'],
        energy: 0.62, valence: 0.45, danceability: 0.75, acousticness: 0.20,
        mood: ['confident', 'chill'],
        relatedArtists: ['travis scott', 'future', '21 savage', 'kendrick lamar']
    },
    'kendrick lamar': {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        subgenres: ['conscious_rap', 'hiphop'],
        energy: 0.75, valence: 0.50, danceability: 0.68, acousticness: 0.18,
        mood: ['intense', 'confident'],
        relatedArtists: ['j cole', 'drake', 'baby keem']
    },
    'eminem': {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        subgenres: ['hardcore_rap', 'hiphop'],
        energy: 0.85, valence: 0.48, danceability: 0.70, acousticness: 0.10,
        mood: ['aggressive', 'energetic'],
        relatedArtists: ['dr dre', '50 cent', 'snoop dogg']
    },
    'travis scott': {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        subgenres: ['trap', 'psychedelic_rap'],
        energy: 0.78, valence: 0.40, danceability: 0.72, acousticness: 0.12,
        mood: ['dark', 'energetic'],
        relatedArtists: ['drake', 'kanye west', 'don toliver']
    },

    // Chill / Ambient / Lo-Fi
    'brian eno': {
        family: GENRE_FAMILIES.CHILL_AMBIENT,
        subgenres: ['ambient', 'minimalism'],
        energy: 0.18, valence: 0.35, danceability: 0.20, acousticness: 0.90,
        mood: ['serene', 'calm'],
        relatedArtists: ['stars of the lid', 'aphex twin', 'steve roach']
    },
    'powfu': {
        family: GENRE_FAMILIES.CHILL_AMBIENT,
        subgenres: ['lofi_hiphop', 'sad_rap'],
        energy: 0.35, valence: 0.38, danceability: 0.60, acousticness: 0.75,
        mood: ['melancholic', 'chill'],
        relatedArtists: ['keshi', 'joji', 'boywithuke']
    },
    'keshi': {
        family: GENRE_FAMILIES.CHILL_AMBIENT,
        subgenres: ['lofi', 'indie_rnb'],
        energy: 0.42, valence: 0.35, danceability: 0.58, acousticness: 0.65,
        mood: ['melancholic', 'romantic'],
        relatedArtists: ['joji', 'powfu', 'dpr ian']
    }
};

// Keyword patterns identifying genre families from title metadata
const TITLE_GENRE_CUES = [
    {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        regex: /\b(chalisa|bhajan|aarti|stotra|stotram|mantra|satsang|kirtan|shiva|shiv|hanuman|krishna|ramayan|durga|ganesh|gayatri|dhun|amritwani|jaap|prarthana|bhakti|sankirtan|devotional|divine|temple|shlok|shloka|gurbani|shabad|qawwali|gospel|hymn|worship|chant|parvati|mahadev|ram|shyam)\b/i,
        subgenres: ['devotional', 'bhajan'],
        energy: 0.40,
        valence: 0.60,
        acousticness: 0.80
    },
    {
        family: GENRE_FAMILIES.CHILL_AMBIENT,
        regex: /\b(lo-?fi|chill\s*beats|ambient|meditation|sleep\s*music|calm\s*piano|study\s*beats|relaxing\s*piano|soundscape|downtempo|peaceful)\b/i,
        subgenres: ['ambient', 'lofi', 'downtempo'],
        energy: 0.28,
        valence: 0.40,
        acousticness: 0.85
    },
    {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        regex: /\b(edm|club\s*mix|extended\s*mix|future\s*bass|dubstep|techno|trance|house\s*music|progressive\s*house|festival\s*mix|synthwave|electronic|dnb|drum\s*and\s*bass|bass\s*boosted|rave)\b/i,
        subgenres: ['edm', 'electronic'],
        energy: 0.82,
        valence: 0.55,
        acousticness: 0.10
    },
    {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        regex: /\b(bhojpuri|haryanvi|ragni|tupka|casa|chora|desi\s*party|dhol\s*mix|dj\s*remix\s*desi)\b/i,
        subgenres: ['indian_regional'],
        energy: 0.80,
        valence: 0.70,
        acousticness: 0.20
    },
    {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        regex: /\b(freestyle|cypher|rap\s*music|drill\s*mix|trap\s*beat|diss\s*track)\b/i,
        subgenres: ['hiphop', 'rap'],
        energy: 0.75,
        valence: 0.45,
        acousticness: 0.15
    }
];

// ─── 4. Embeat Acoustic Vector (7-dim Normalized Vector Space) ──────────
function buildAcousticVector(features = {}) {
    const d = clamp01(features.danceability ?? 0.60);
    const e = clamp01(features.energy ?? 0.65);
    const v = clamp01(features.valence ?? 0.50);
    const a = clamp01(features.acousticness ?? 0.25);
    const l = clamp01(features.liveness ?? 0.18);
    const s = clamp01(features.speechiness ?? 0.08);
    const i = clamp01(features.instrumentalness ?? 0.10);

    return [
        d - 0.5,
        e - 0.5,
        v - 0.5,
        a - 0.5,
        l - 0.5,
        s - 0.5,
        i - 0.5
    ];
}

function clamp01(v) {
    return Math.max(0.0, Math.min(1.0, Number(v) || 0.0));
}

function computeAcousticSimilarity(vecA, vecB) {
    if (!vecA || !vecB || vecA.length !== 7 || vecB.length !== 7) return 0.5;

    let dot = 0.0;
    let normA = 0.0;
    let normB = 0.0;

    for (let i = 0; i < 7; i++) {
        dot += vecA[i] * vecB[i];
        normA += vecA[i] * vecA[i];
        normB += vecB[i] * vecB[i];
    }

    if (normA <= 1e-12 || normB <= 1e-12) return 0.5;

    const rawCosine = dot / (Math.sqrt(normA) * Math.sqrt(normB));
    const sim01 = 0.5 * (rawCosine + 1.0);
    return Math.max(0.0, Math.min(1.0, sim01));
}

// ─── 5. Track Profile Builder with Transparent Confidence ────────────────
function buildTrackProfile(track = {}) {
    const rawTitle = (track.title || '').trim();
    const rawArtist = (track.artist || '').trim();
    const parsedArtists = extractArtistList(rawArtist);

    let primaryGenre = GENRE_FAMILIES.POP_MAINSTREAM; // default fallback
    let subgenres = ['pop'];
    let moodTags = ['neutral'];
    let energy = 0.60;
    let valence = 0.50;
    let danceability = 0.60;
    let acousticness = 0.30;
    let confidence = 0.35; // default low confidence for heuristic profile
    let provenance = 'unclassified_fallback';
    let relatedArtists = [];

    // 1. Direct Catalog Match: check all participating artists
    for (const a of parsedArtists) {
        const matched = Object.keys(ARTIST_GENRE_MAP).find(k => k === a || a.includes(k) || k.includes(a));
        if (matched) {
            const meta = ARTIST_GENRE_MAP[matched];
            primaryGenre = meta.family;
            subgenres = [...meta.subgenres];
            energy = meta.energy;
            valence = meta.valence;
            danceability = meta.danceability;
            acousticness = meta.acousticness;
            moodTags = [...(meta.mood || ['neutral'])];
            confidence = 0.90;
            provenance = `catalog_taxonomy:${matched}`;
            relatedArtists = meta.relatedArtists || [];
            break;
        }
    }

    // 2. Strong Title Keyword Cues (overrides unclassified or refines family)
    for (const cue of TITLE_GENRE_CUES) {
        if (cue.regex.test(rawTitle)) {
            // Title cues for devotional or chill take priority
            if (cue.family === GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL || confidence < 0.80) {
                primaryGenre = cue.family;
                subgenres = [...cue.subgenres];
                energy = cue.energy;
                valence = cue.valence;
                acousticness = cue.acousticness;
                confidence = Math.max(confidence, 0.70);
                provenance = `title_cues:${cue.family}`;
                break;
            }
        }
    }

    // 3. Audio profile adjustments for title modifiers
    if (/(\(slowed|reverb|acoustic|piano\s*cover)\b/i.test(rawTitle)) {
        energy = Math.max(0.2, energy - 0.25);
        acousticness = Math.min(0.9, acousticness + 0.35);
        moodTags = Array.from(new Set([...moodTags, 'melancholic', 'chill']));
    }
    if (/(\(sped\s*up|nightcore|remix|club\s*mix)\b/i.test(rawTitle)) {
        energy = Math.min(0.95, energy + 0.20);
        danceability = Math.min(0.95, danceability + 0.15);
        moodTags = Array.from(new Set([...moodTags, 'energetic']));
    }

    const acousticVector = buildAcousticVector({
        danceability,
        energy,
        valence,
        acousticness,
        liveness: 0.18,
        speechiness: 0.08,
        instrumentalness: (subgenres.includes('edm') || subgenres.includes('ambient')) ? 0.35 : 0.05
    });

    return {
        title: rawTitle,
        artist: rawArtist,
        primaryArtists: parsedArtists,
        primaryGenre,
        subgenres,
        moodTags,
        energy: Number(energy.toFixed(2)),
        valence: Number(valence.toFixed(2)),
        danceability: Number(danceability.toFixed(2)),
        acousticness: Number(acousticness.toFixed(2)),
        acousticVector,
        confidence: Number(confidence.toFixed(2)),
        provenance,
        relatedArtists
    };
}

// ─── 6. Genre and Mood Eligibility Gate ─────────────────────────────────
function checkEligibilityGate(seedProfile, candidateProfile) {
    if (!seedProfile || !candidateProfile) {
        return { eligible: true, rejectionReason: null };
    }

    const seedFam = seedProfile.primaryGenre;
    const candFam = candidateProfile.primaryGenre;

    // 1. Compatibility Matrix Check
    const famMatrix = GENRE_COMPATIBILITY_MATRIX[seedFam];
    const compatScore = (famMatrix && famMatrix[candFam] !== undefined)
        ? famMatrix[candFam]
        : 0.50;

    if (compatScore <= 0.0) {
        return {
            eligible: false,
            rejectionReason: `Incompatible genre family: ${candFam} is strictly disallowed for ${seedFam} seed`
        };
    }

    // 2. High-Energy Session Gate (e.g. EDM / Dance)
    if (seedProfile.energy >= 0.70 && candidateProfile.energy <= 0.30) {
        return {
            eligible: false,
            rejectionReason: `Energy mismatch: candidate energy (${candidateProfile.energy}) is too low for high-energy seed (${seedProfile.energy})`
        };
    }

    // 3. Chill / Ambient Session Gate
    if (seedProfile.energy <= 0.35 && candidateProfile.energy >= 0.80) {
        return {
            eligible: false,
            rejectionReason: `Energy mismatch: candidate energy (${candidateProfile.energy}) is too intense for ambient seed (${seedProfile.energy})`
        };
    }

    return { eligible: true, rejectionReason: null };
}

// ─── 7. Weighted 0–100 Point-Scoring System ─────────────────────────────
function scoreTrackProfile(candidate, seedProfile, userProfile = {}, selectedSoFar = [], weights = {}) {
    const wAcoustic = weights.acousticSimilarity ?? 40.0;
    const wGenre = weights.genreCompatibility ?? 25.0;
    const wMoodEnergy = weights.moodEnergy ?? 20.0;
    const wPreference = weights.preference ?? 15.0;

    const candProfile = candidate.profile || buildTrackProfile(candidate);
    const explanations = [];

    // ── 1. Acoustic Similarity (Confidence-weighted) ──
    const rawAcousticSim = computeAcousticSimilarity(seedProfile.acousticVector, candProfile.acousticVector);
    const minConfidence = Math.min(seedProfile.confidence || 0.5, candProfile.confidence || 0.5);
    // Weight acoustic score by metadata confidence so fabricated values don't inflate
    const effectiveSim = rawAcousticSim * (0.6 + 0.4 * minConfidence);
    const acousticPoints = Number((effectiveSim * wAcoustic).toFixed(2));

    if (minConfidence >= 0.70) {
        explanations.push(`Acoustic match: ${(rawAcousticSim * 100).toFixed(0)}%`);
    }

    // ── 2. Genre & Subgenre Compatibility ──
    const seedFam = seedProfile.primaryGenre;
    const candFam = candProfile.primaryGenre;
    const famMatrix = GENRE_COMPATIBILITY_MATRIX[seedFam];
    let famRatio = famMatrix && famMatrix[candFam] !== undefined ? famMatrix[candFam] : 0.50;

    const subgenreOverlap = (seedProfile.subgenres || []).some(s => (candProfile.subgenres || []).includes(s));
    if (subgenreOverlap) {
        famRatio = Math.min(1.0, famRatio + 0.20);
    }
    const genrePoints = Number((famRatio * wGenre).toFixed(2));

    // ── 3. Mood & Energy Alignment ──
    const energyDelta = Math.abs(seedProfile.energy - candProfile.energy);
    const energyScore = Math.max(0.0, 1.0 - energyDelta * 1.5);

    const valenceDelta = Math.abs(seedProfile.valence - candProfile.valence);
    const valenceScore = Math.max(0.0, 1.0 - valenceDelta * 1.2);

    const moodOverlap = (seedProfile.moodTags || []).some(m => (candProfile.moodTags || []).includes(m));
    const moodBonus = moodOverlap ? 1.0 : 0.6;

    const moodEnergyRatio = 0.5 * energyScore + 0.3 * valenceScore + 0.2 * moodBonus;
    const moodEnergyPoints = Number((moodEnergyRatio * wMoodEnergy).toFixed(2));

    // ── 4. Artist Affinity & Source Quality ──
    let prefScore = 0.0;
    const candArtists = candProfile.primaryArtists || extractArtistList(candidate.artist);
    const seedArtists = (userProfile.seedArtists || []).map(a => a.toLowerCase().trim());
    const likedArtists = (userProfile.likedArtists || []).map(a => a.toLowerCase().trim());

    const isSeedArtistMatch = candArtists.some(ca => seedArtists.some(sa => sa === ca || ca.includes(sa) || sa.includes(ca)));
    const isLikedArtistMatch = candArtists.some(ca => likedArtists.some(la => la === ca || ca.includes(la) || la.includes(ca)));

    if (isLikedArtistMatch) {
        prefScore += 6.0;
        explanations.push(`Favorite artist: ${candidate.artist}`);
    } else if (isSeedArtistMatch || candidate.source === 'seed_artist') {
        prefScore += 5.0;
        explanations.push(`More by ${candidate.artist}`);
    } else if (candidate.source === 'related_artist') {
        prefScore += 4.5;
        explanations.push(candidate.explanation || `Similar artist: ${candidate.artist}`);
    } else if (candidate.source === 'watch_next') {
        prefScore += 4.0;
        explanations.push('From YouTube Watch Next related mix');
    } else if (candidate.source === 'related_search') {
        prefScore += 3.5;
        explanations.push(candidate.explanation || `Similar style: ${candFam}`);
    } else {
        prefScore += 1.5;
    }
    const preferencePoints = Number(Math.min(wPreference, prefScore).toFixed(2));

    // ── 5. Penalties (Recent, Alternate, Multi-Artist Saturation) ──
    let penalties = 0.0;
    const candidateKey = candidate.canonicalKey || `${(candArtists[0] || 'unknown')}:::${(candidate.title || '').toLowerCase()}`;
    const recentKeys = new Set((userProfile.recentTrackKeys || []).map(k => k.toLowerCase()));
    const skippedArtists = (userProfile.skippedArtists || []).map(a => a.toLowerCase().trim());

    if (recentKeys.has(candidateKey.toLowerCase())) {
        penalties += 15.0;
        explanations.push('Penalty: recently played');
    }
    if (candArtists.some(ca => skippedArtists.includes(ca))) {
        penalties += 20.0;
        explanations.push('Penalty: previously skipped artist');
    }
    if (candidate.isAlternate) {
        penalties += 25.0;
        explanations.push('Penalty: alternate version');
    }

    // Multi-Artist queue saturation: check how many selected tracks feature this candidate's artists
    let maxSaturationForArtist = 0;
    for (const ca of candArtists) {
        const count = selectedSoFar.filter(s => {
            const sArtists = s.profile?.primaryArtists || extractArtistList(s.artist);
            return sArtists.includes(ca);
        }).length;
        if (count > maxSaturationForArtist) {
            maxSaturationForArtist = count;
        }
    }

    if (maxSaturationForArtist === 1) {
        penalties += 8.0; // Spacing penalty for 2nd track
    } else if (maxSaturationForArtist === 2) {
        penalties += 20.0; // Heavy penalty for 3rd track
    } else if (maxSaturationForArtist >= 3) {
        penalties += 50.0; // Hard diversity cap: max 3 per artist in Up Next
    }

    const rawTotal = acousticPoints + genrePoints + moodEnergyPoints + preferencePoints - penalties;
    const totalScore = Math.max(0.0, Math.min(100.0, Number(rawTotal.toFixed(2))));

    return {
        totalScore,
        breakdown: {
            acousticSimilarity: acousticPoints,
            genreCompatibility: genrePoints,
            moodEnergyCompatibility: moodEnergyPoints,
            artistAndPreference: preferencePoints,
            penalties: Number((-penalties).toFixed(2))
        },
        primaryExplanation: explanations[0] || `Recommended track (${candFam})`
    };
}

module.exports = {
    GENRE_FAMILIES,
    GENRE_COMPATIBILITY_MATRIX,
    ARTIST_GENRE_MAP,
    TITLE_GENRE_CUES,
    extractArtistList,
    buildAcousticVector,
    computeAcousticSimilarity,
    buildTrackProfile,
    checkEligibilityGate,
    scoreTrackProfile
};
