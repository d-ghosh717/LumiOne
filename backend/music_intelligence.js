/**
 * LumiOne Music Intelligence & Categorical Profiling Engine
 *
 * Implements:
 * Genre, subgenre, and mood tags come from provider metadata, artist taxonomy, or title cues.
 * The 0–100 point model does not infer or fabricate acoustic features.
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
        mood: ['melancholic', 'uplifting'],
        relatedArtists: ['avicii', 'the chainsmokers', 'marshmello', 'kygo', 'martin garrix', 'k-391', 'alok', 'illenium']
    },
    'avicii': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['edm', 'progressive_house', 'melodic_edm'],
        mood: ['uplifting', 'energetic'],
        relatedArtists: ['alan walker', 'kygo', 'martin garrix', 'alessoo', 'david guetta']
    },
    'the chainsmokers': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['dance_pop', 'edm', 'electropop'],
        mood: ['melancholic', 'romantic'],
        relatedArtists: ['alan walker', 'marshmello', 'halsey', 'zedd']
    },
    'marshmello': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['future_bass', 'edm', 'dance_pop'],
        mood: ['happy', 'energetic'],
        relatedArtists: ['the chainsmokers', 'alan walker', 'bastille', 'martin garrix']
    },
    'martin garrix': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['progressive_house', 'edm'],
        mood: ['energetic', 'uplifting'],
        relatedArtists: ['avicii', 'tiesto', 'david guetta', 'alan walker']
    },
    'calvin harris': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['dance_pop', 'house', 'edm'],
        mood: ['energetic', 'happy'],
        relatedArtists: ['david guetta', 'tiesto', 'alok', 'dua lipa']
    },
    'dj snake': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['trap_edm', 'dance_pop'],
        mood: ['energetic'],
        relatedArtists: ['major lazer', 'diplo', 'skrillex']
    },
    'kygo': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['tropical_house', 'melodic_house'],
        mood: ['chill', 'uplifting'],
        relatedArtists: ['alan walker', 'avicii', 'gryffin', 'sam feldt']
    },
    'alok': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['brazilian_bass', 'edm', 'house'],
        mood: ['energetic'],
        relatedArtists: ['alan walker', 'tiesto', 'martin garrix']
    },
    'k-391': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['electro_house', 'edm'],
        mood: ['uplifting', 'melancholic'],
        relatedArtists: ['alan walker', 'julie bergan']
    },
    'illenium': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['future_bass', 'melodic_dubstep'],
        mood: ['melancholic', 'emotional'],
        relatedArtists: ['alan walker', 'seven lions', 'said the sky']
    },
    'zedd': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['electro_house', 'dance_pop'],
        mood: ['energetic', 'uplifting'],
        relatedArtists: ['the chainsmokers', 'alessia cara', 'martin garrix']
    },
    'tiesto': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['house', 'edm'],
        mood: ['energetic'],
        relatedArtists: ['martin garrix', 'david guetta', 'calvin harris']
    },
    'david guetta': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['dance_pop', 'edm', 'house'],
        mood: ['energetic', 'happy'],
        relatedArtists: ['calvin harris', 'tiesto', 'sia']
    },
    'lady gaga': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['dance_pop', 'electropop', 'pop'],
        mood: ['energetic', 'dramatic'],
        relatedArtists: ['madonna', 'kylie minogue', 'britney spears', 'dua lipa', 'the weeknd']
    },
    'jennifer lopez': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['dance_pop', 'latin_pop', 'pop'],
        mood: ['energetic', 'party'],
        relatedArtists: ['shakira', 'pitbull', 'madonna', 'dua lipa', 'calvin harris']
    },
    'skrillex': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['dubstep', 'bass', 'edm'],
        mood: ['energetic', 'dark'],
        relatedArtists: ['diplo', 'fred again', 'boys noize']
    },
    'fred again': {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        subgenres: ['uk_garage', 'house', 'electronic'],
        mood: ['emotional', 'energetic'],
        relatedArtists: ['skrillex', 'four tet', 'bicep']
    },

    // Devotional / Spiritual
    'gulshan kumar': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'aarti', 'chalisa'],
        mood: ['devotional', 'spiritual'],
        relatedArtists: ['hariharan', 'anuradha paudwal', 'anup jalota']
    },
    'hansraj raghuwanshi': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['shiv_bhajan', 'devotional'],
        mood: ['devotional', 'spiritual'],
        relatedArtists: ['lakhbir singh lakha', 'kailash kher']
    },
    'anup jalota': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'devotional'],
        mood: ['devotional', 'serene'],
        relatedArtists: ['hariharan', 'anuradha paudwal', 'jagjit singh']
    },
    'sadhu tiwari': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['devotional', 'bhajan'],
        mood: ['devotional'],
        relatedArtists: ['hansraj raghuwanshi', 'gulshan kumar']
    },
    'anuradha paudwal': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['aarti', 'bhajan'],
        mood: ['devotional', 'serene'],
        relatedArtists: ['gulshan kumar', 'hariharan', 'suresh wadkar']
    },
    'lakhbir singh lakha': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'mata_bhajan'],
        mood: ['devotional'],
        relatedArtists: ['gulshan kumar', 'hansraj raghuwanshi']
    },
    'suresh wadkar': {
        family: GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL,
        subgenres: ['bhajan', 'mantra'],
        mood: ['serene', 'devotional'],
        relatedArtists: ['hariharan', 'anuradha paudwal']
    },

    // Indian Regional / Haryanvi / Bhojpuri / Desi Party
    'mc square': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['haryanvi_hiphop', 'desi_hiphop'],
        mood: ['energetic'],
        relatedArtists: ['diler kharkiya', 'renuka panwar', 'fazilpuria']
    },
    'dhanda nyoliwala': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['haryanvi_hiphop', 'desi_hiphop'],
        mood: ['energetic'],
        relatedArtists: ['mc square', 'diler kharkiya']
    },
    'yo yo honey singh': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['desi_hiphop', 'punjabi_pop'],
        mood: ['energetic', 'party'],
        relatedArtists: ['badshah', 'guru randhawa', 'raftaar']
    },
    'kr$na': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['desi_hiphop'],
        mood: ['energetic', 'dark'],
        relatedArtists: ['raftaar', 'seedhe maut']
    },
    'krsna': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['desi_hiphop'],
        mood: ['energetic', 'dark'],
        relatedArtists: ['raftaar', 'seedhe maut']
    },
    'khesari lal yadav': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['bhojpuri'],
        mood: ['energetic'],
        relatedArtists: ['pawan singh', 'shilpi raj']
    },
    'pawan singh': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['bhojpuri'],
        mood: ['energetic'],
        relatedArtists: ['khesari lal yadav', 'shilpi raj']
    },
    'diler kharkiya': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['haryanvi'],
        mood: ['energetic'],
        relatedArtists: ['renuka panwar', 'mc square']
    },
    'renuka panwar': {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        subgenres: ['haryanvi'],
        mood: ['energetic'],
        relatedArtists: ['diler kharkiya', 'mc square']
    },

    // Bollywood / Desi Filmi
    'arijit singh': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['bollywood_romantic', 'filmi'],
        mood: ['romantic', 'melancholic'],
        relatedArtists: ['jubin nautiyal', 'atif aslam', 'pritam', 'shreya ghoshal']
    },
    'b praak': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['punjabi_sad', 'bollywood_sad'],
        mood: ['sad', 'emotional'],
        relatedArtists: ['jaani', 'arijit singh', 'jubin nautiyal']
    },
    'jubin nautiyal': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['bollywood_romantic'],
        mood: ['romantic'],
        relatedArtists: ['arijit singh', 'darshan raval', 'armaan malik']
    },
    'shreya ghoshal': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['filmi', 'bollywood_romantic'],
        mood: ['romantic', 'sweet'],
        relatedArtists: ['arijit singh', 'sonu nigam', 'sunidhi chauhan']
    },
    'sonu nigam': {
        family: GENRE_FAMILIES.BOLLYWOOD_DESI,
        subgenres: ['filmi', 'bollywood_classic'],
        mood: ['romantic', 'emotional'],
        relatedArtists: ['shreya ghoshal', 'arijit singh', 'udit narayan']
    },

    // Pop / Mainstream / Acoustic
    'passenger': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['indie_folk', 'acoustic_pop'],
        mood: ['melancholic', 'romantic'],
        relatedArtists: ['ed sheeran', 'adele', 'john legend', 'james arthur', 'lewis capaldi']
    },
    'the weeknd': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['synthpop', 'contemporary_rnb', 'pop'],
        mood: ['dark', 'melancholic', 'energetic'],
        relatedArtists: ['dua lipa', 'post malone', 'daft punk', 'zayn']
    },
    'dua lipa': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['dance_pop', 'nu_disco'],
        mood: ['energetic', 'happy'],
        relatedArtists: ['calvin harris', 'the weeknd', 'bebe rexha']
    },
    'zayn': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['electropop', 'pop', 'rnb'],
        mood: ['melancholic', 'emotional'],
        relatedArtists: ['sia', 'the weeknd', 'halsey']
    },
    'sia': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['electropop', 'pop'],
        mood: ['uplifting', 'emotional'],
        relatedArtists: ['zayn', 'david guetta', 'adele']
    },
    'ed sheeran': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['pop', 'acoustic_pop'],
        mood: ['romantic', 'happy'],
        relatedArtists: ['passenger', 'shawn mendes', 'adele', 'james arthur']
    },
    'adele': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['soul_pop', 'acoustic_ballad'],
        mood: ['melancholic', 'emotional'],
        relatedArtists: ['passenger', 'sam smith', 'lewis capaldi', 'ed sheeran']
    },
    'john legend': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['soul', 'piano_pop'],
        mood: ['romantic', 'emotional'],
        relatedArtists: ['adele', 'sam smith', 'alicia keys', 'ed sheeran']
    },
    'taylor swift': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['pop', 'synthpop', 'country_pop'],
        mood: ['romantic', 'melancholic'],
        relatedArtists: ['olivia rodrigo', 'sabrina carpenter', 'ed sheeran']
    },
    'rihanna': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['pop', 'dance_pop', 'rnb'],
        mood: ['uplifting'],
        relatedArtists: ['calvin harris', 'drake', 'beyonce']
    },
    'akon': {
        family: GENRE_FAMILIES.POP_MAINSTREAM,
        subgenres: ['dance_pop', 'rnb'],
        mood: ['happy', 'energetic'],
        relatedArtists: ['pitbull', 'ne-yo', 'eminem']
    },

    // Hip-Hop / Rap
    'drake': {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        subgenres: ['hiphop', 'trap', 'melodic_rap'],
        mood: ['confident', 'chill'],
        relatedArtists: ['travis scott', 'future', '21 savage', 'kendrick lamar']
    },
    'kendrick lamar': {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        subgenres: ['conscious_rap', 'hiphop'],
        mood: ['intense', 'confident'],
        relatedArtists: ['j cole', 'drake', 'baby keem']
    },
    'eminem': {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        subgenres: ['hardcore_rap', 'hiphop'],
        mood: ['aggressive', 'energetic'],
        relatedArtists: ['dr dre', '50 cent', 'snoop dogg']
    },
    'travis scott': {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        subgenres: ['trap', 'psychedelic_rap'],
        mood: ['dark', 'energetic'],
        relatedArtists: ['drake', 'kanye west', 'don toliver']
    },

    // Chill / Ambient / Lo-Fi
    'brian eno': {
        family: GENRE_FAMILIES.CHILL_AMBIENT,
        subgenres: ['ambient', 'minimalism'],
        mood: ['serene', 'calm'],
        relatedArtists: ['stars of the lid', 'aphex twin', 'steve roach']
    },
    'powfu': {
        family: GENRE_FAMILIES.CHILL_AMBIENT,
        subgenres: ['lofi_hiphop', 'sad_rap'],
        mood: ['melancholic', 'chill'],
        relatedArtists: ['keshi', 'joji', 'boywithuke']
    },
    'keshi': {
        family: GENRE_FAMILIES.CHILL_AMBIENT,
        subgenres: ['lofi', 'indie_rnb'],
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
    },
    {
        family: GENRE_FAMILIES.CHILL_AMBIENT,
        regex: /\b(lo-?fi|chill\s*beats|ambient|meditation|sleep\s*music|calm\s*piano|study\s*beats|relaxing\s*piano|soundscape|downtempo|peaceful)\b/i,
        subgenres: ['ambient', 'lofi', 'downtempo'],
    },
    {
        family: GENRE_FAMILIES.ELECTRONIC_EDM,
        regex: /\b(edm|club\s*mix|extended\s*mix|future\s*bass|dubstep|techno|trance|house\s*music|progressive\s*house|festival\s*mix|synthwave|electronic|dnb|drum\s*and\s*bass|bass\s*boosted|rave)\b/i,
        subgenres: ['edm', 'electronic'],
    },
    {
        family: GENRE_FAMILIES.INDIAN_REGIONAL_DESI,
        regex: /\b(bhojpuri|haryanvi|ragni|tupka|casa|chora|desi\s*party|dhol\s*mix|dj\s*remix\s*desi)\b/i,
        subgenres: ['indian_regional'],
    },
    {
        family: GENRE_FAMILIES.HIPHOP_RAP,
        regex: /\b(freestyle|cypher|rap\s*music|drill\s*mix|trap\s*beat|diss\s*track)\b/i,
        subgenres: ['hiphop', 'rap'],
    }
];

// ─── 5. Track Profile Builder with Transparent Confidence ────────────────
function buildTrackProfile(track = {}) {
    const rawTitle = (track.title || '').trim();
    const rawArtist = (track.artist || '').trim();
    const parsedArtists = extractArtistList(rawArtist);
    let primaryGenre = null;
    let subgenres = [];
    let moodTags = [];
    let confidence = 0;
    let provenance = 'metadata_missing';
    let relatedArtists = [];

    // This taxonomy contains artist-level genre/style claims only. No audio feature values are inferred.
    for (const artist of parsedArtists) {
        const match = ARTIST_GENRE_MAP[artist];
        if (match) {
            primaryGenre = match.family;
            subgenres = [...match.subgenres];
            moodTags = [...(match.mood || [])];
            relatedArtists = [...(match.relatedArtists || [])];
            confidence = 0.65;
            provenance = `artist_taxonomy:${artist}`;
            break;
        }
    }

    for (const cue of TITLE_GENRE_CUES) {
        if (cue.regex.test(rawTitle) && (cue.family === GENRE_FAMILIES.DEVOTIONAL_SPIRITUAL || confidence < 0.65)) {
            primaryGenre = cue.family;
            subgenres = [...cue.subgenres];
            confidence = Math.max(confidence, 0.60);
            provenance = `title_cues:${cue.family}`;
            break;
        }
    }

    const explicitGenre = track.genre || track.primaryGenre;
    if (explicitGenre) {
        primaryGenre = explicitGenre;
        subgenres = Array.isArray(track.subgenres) ? [...track.subgenres] : subgenres;
        moodTags = Array.isArray(track.moodTags) ? [...track.moodTags] : moodTags;
        confidence = Number(track.confidence) || 0.85;
        provenance = track.provenance || 'provider_metadata';
    }

    return { title: rawTitle, artist: rawArtist, primaryArtists: parsedArtists, primaryGenre, subgenres, moodTags, confidence, provenance, relatedArtists };
}

// ─── 6. Genre and Mood Eligibility Gate ─────────────────────────────────
function checkEligibilityGate(seedProfile, candidateProfile) {
    if (!seedProfile || !candidateProfile || !seedProfile.primaryGenre || !candidateProfile.primaryGenre) {
        return { eligible: true, rejectionReason: null };
    }
    const familyScores = GENRE_COMPATIBILITY_MATRIX[seedProfile.primaryGenre];
    const compatibility = familyScores?.[candidateProfile.primaryGenre];
    if (compatibility === 0 && seedProfile.confidence >= 0.6 && candidateProfile.confidence >= 0.6) {
        return { eligible: false, rejectionReason: `Incompatible genre family: ${candidateProfile.primaryGenre} is disallowed for ${seedProfile.primaryGenre}` };
    }
    return { eligible: true, rejectionReason: null };
}

// ─── 7. Weighted 0–100 Point-Scoring System ─────────────────────────────
function scoreTrackProfile(candidate, seedProfile, userProfile = {}, selectedSoFar = []) {
    const profile = candidate.profile || buildTrackProfile(candidate);
    const sources = [...new Set([...(candidate.retrievalSources || []), candidate.source].filter(Boolean))];
    const evidenceWeights = { watch_next: 30, related_artist_search: 25, same_artist_catalog: 23, genre_search: 15 };
    const relatedEvidence = Math.max(0, ...sources.map(source => evidenceWeights[source] || 0));
    const familyScores = seedProfile?.primaryGenre && profile.primaryGenre ? GENRE_COMPATIBILITY_MATRIX[seedProfile.primaryGenre] : null;
    const familyMatch = familyScores?.[profile.primaryGenre];
    const subgenreOverlap = (seedProfile?.subgenres || []).some(g => (profile.subgenres || []).includes(g));
    const genreCompatibility = familyMatch === undefined ? 0 : Math.min(25, familyMatch * 20 + (subgenreOverlap ? 5 : 0));
    const sharedMoods = (seedProfile?.moodTags || []).filter(m => (profile.moodTags || []).includes(m));
    const moodStyleCompatibility = seedProfile?.confidence >= 0.6 && profile.confidence >= 0.6 && sharedMoods.length
        ? Math.min(20, 10 + 5 * Math.min(sharedMoods.length, 2)) : 0;
    const clean = value => String(value || '').toLowerCase().replace(/\b(vevo|topic|official|channel|records|music)\b/g, ' ').replace(/[^a-z0-9]+/g, ' ').trim();
    const seedArtists = (userProfile.seedArtists || []).map(clean);
    const candidateArtists = extractArtistList(candidate.artist).map(clean);
    const sameArtist = candidateArtists.some(a => seedArtists.includes(a));
    const relatedArtists = (seedProfile?.relatedArtists || []).map(clean);
    const relatedArtist = candidateArtists.some(a => relatedArtists.includes(a));
    const artistRelationship = sameArtist ? 15 : relatedArtist ? 10 : 0;
    const candidateIdentity = candidate.canonicalKey || `${clean(candidate.artist)}:::${clean(candidate.title)}`;
    const candidateKey = String(candidateIdentity).toLowerCase();
    const likedKeys = (userProfile.likedTrackKeys || []).map(key => String(key).toLowerCase());
    const likedArtists = (userProfile.likedArtists || []).map(clean);
    const likedArtist = candidateArtists.some(a => likedArtists.includes(a));
    const contextualSimilarity = candidate.recordingRelationship === true || likedKeys.includes(candidateKey) ? 10 : likedArtist ? 5 : 0;
    const duplicate = selectedSoFar.some(item => (item.canonicalKey || `${clean(item.artist)}:::${clean(item.title)}`) === candidateIdentity);
    const skippedArtists = (userProfile.skippedArtists || []).map(clean);
    const recentKeys = (userProfile.recentTrackKeys || []).map(key => String(key).toLowerCase());
    const skipped = candidateArtists.some(a => skippedArtists.includes(a));
    const recent = recentKeys.includes(candidateKey);
    const penalties = duplicate ? 100 : skipped ? 25 : recent ? 15 : candidate.isAlternate ? 12 : 0;
    const breakdown = { relatedEvidence, genreCompatibility: Number(genreCompatibility.toFixed(2)), moodStyleCompatibility, artistRelationship, contextualSimilarity, penalties: -penalties };
    const totalScore = Math.max(0, Math.min(100, Number(Object.values(breakdown).reduce((a, b) => a + b, 0).toFixed(2))));
    return { totalScore, breakdown, primaryExplanation: `Evidence ${relatedEvidence}; genre ${breakdown.genreCompatibility}; mood/style ${moodStyleCompatibility}; artist ${artistRelationship}` };
}

module.exports = {
    GENRE_FAMILIES,
    GENRE_COMPATIBILITY_MATRIX,
    ARTIST_GENRE_MAP,
    TITLE_GENRE_CUES,
    extractArtistList,
    buildTrackProfile,
    checkEligibilityGate,
    scoreTrackProfile
};
