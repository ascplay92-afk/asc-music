package com.ascmusic

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

private val Canvas = Color(0xFF101210)
private val Panel = Color(0xFF191D1A)
private val Leaf = Color(0xFFC8FA72)
private val Muted = Color(0xFF969D96)

private fun countLabel(count: Int, singular: String, plural: String) =
    "$count ${if (count == 1) singular else plural}"

class MainActivity : ComponentActivity() {
    private val library = mutableStateListOf<AudioTrack>()
    private val playlists = mutableStateListOf<MusicPlaylist>()
    private var mediaController by mutableStateOf<MediaController?>(null)
    private var currentTrack by mutableStateOf<AudioTrack?>(null)
    private var isPlaying by mutableStateOf(false)
    private var libraryLoaded by mutableStateOf(false)
    private var playlistsLoaded = false
    private var queueRevision by mutableStateOf(0)
    private var folderUris by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var foldersMissingAccess by mutableStateOf<Set<String>>(emptySet())
    private var history by mutableStateOf<List<AudioTrack>>(emptyList())
    private var accountSession by mutableStateOf<SupabaseSession?>(null)
    private var legacyDataAvailable by mutableStateOf(false)
    private var syncStatus by mutableStateOf("Offline")
    private var passwordRecoveryMode by mutableStateOf(false)
    private val authClient by lazy { SupabaseAuthClient(SecureSessionStore(this)) }
    private val googleDriveClient by lazy { GoogleDriveClient(this) }
    private val spotifyClient by lazy { SpotifyClient(this) }
    private var driveConnected by mutableStateOf(false)
    private var spotifyConnected by mutableStateOf(false)
    private var driveFolders by mutableStateOf<List<GoogleDriveFile>>(emptyList())
    private var spotifyPlaylists by mutableStateOf<List<SpotifyPlaylist>>(emptyList())
    private var providerProgress by mutableStateOf<String?>(null)
    private var providerOperation: Job? = null
    private var syncJob: Job? = null
    private var activeWorkerSync: WorkerSyncClient? = null
    private var requestedPage by mutableStateOf("")
    private var playbackFuture: ListenableFuture<MediaController>? = null
    private val preferences by lazy { getSharedPreferences("asc-music-index", Context.MODE_PRIVATE) }
    private val libraryDatabaseDelegate = lazy { LibraryDatabase(this) }
    private val libraryDatabase: LibraryDatabase by libraryDatabaseDelegate
    private val audioIndexer by lazy { SafAudioIndexer(this) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val (result, permissionWarnings) = withContext(Dispatchers.IO) {
                var permissionWarnings = 0
                uris.map { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (error: SecurityException) {
                        permissionWarnings++
                    }

                    readAudioTrack(uri)
                } to permissionWarnings
            }
            val indexed = library.associateBy { it.id }.toMutableMap()
            result.forEach { track -> indexed[track.id] = track.copy(favorite = indexed[track.id]?.favorite == true) }
            library.clear()
            library.addAll(indexed.values)
            persistLibrary(result.map { track -> track.copy(favorite = indexed[track.id]?.favorite == true) })
            if (permissionWarnings > 0) {
                Toast.makeText(this@MainActivity, countLabel(permissionWarnings, "arquivo precisará ser escolhido", "arquivos precisarão ser escolhidos") + " novamente após reiniciar.", Toast.LENGTH_LONG).show()
            }
            Toast.makeText(this@MainActivity, countLabel(result.size, "arquivo adicionado", "arquivos adicionados") + " à biblioteca.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePassword(password: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { authClient.updatePassword(password) }
                passwordRecoveryMode = false
                syncStatus = "Senha atualizada"
                Toast.makeText(this@MainActivity, "Senha atualizada com segurança.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Não foi possível atualizar a senha.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val persisted = persistSafReadGrant {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val label = withContext(Dispatchers.IO) { folderLabel(uri) }
                if (persisted) withContext(Dispatchers.IO) { libraryDatabase.saveFolder(uri.toString(), label) }
                refreshFolder(uri.toString(), label, persistFolder = persisted)
                refreshFolderList()
                if (!persisted) {
                    Toast.makeText(this@MainActivity, "A pasta foi indexada nesta sessão, mas o provedor não permitiu salvar o acesso. Selecione-a novamente após fechar o aplicativo.", Toast.LENGTH_LONG).show()
                }
            } catch (error: SecurityException) {
                Toast.makeText(this@MainActivity, "O acesso persistente à pasta foi recusado: ${error.message}", Toast.LENGTH_LONG).show()
            } catch (error: RuntimeException) {
                Toast.makeText(this@MainActivity, "A pasta não pôde ser indexada: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val csvPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val csv = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Não foi possível ler o arquivo CSV.")
                    input.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_PLAYLIST_CSV_BYTES) throw IOException("O CSV excede o limite de 10 MiB.")
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
                val imported = parseCsvPlaylist(
                    csv,
                    uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Playlist importada",
                )
                playlists.add(0, imported)
                persistPlaylists(imported)
                Toast.makeText(this@MainActivity, countLabel(imported.tracks.size, "faixa importada", "faixas importadas") + ".", Toast.LENGTH_SHORT).show()
            } catch (error: IOException) {
                Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_LONG).show()
            } catch (error: IllegalArgumentException) {
                Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        driveConnected = googleDriveClient.hasStoredSession()
        spotifyConnected = spotifyClient.hasStoredSession()
        val isAuthCallback = intent?.data?.let { it.scheme == "ascmusic" && it.host == "auth" } == true
        lifecycleScope.launch {
            try {
                if (isAuthCallback) return@launch
                accountSession = withContext(Dispatchers.IO) { authClient.validSession() }
                syncStatus = if (accountSession == null) "Entre para sincronizar" else "Pronto para sincronizar"
                if (accountSession != null && libraryLoaded && playlistsLoaded) {
                    refreshAccountLibraryState()
                }
            } catch (error: Exception) {
                syncStatus = "Sessão indisponível: ${error.message}"
            }
        }
        handleAuthCallback(intent)
        restoreLibrary()
        restorePlaylists()
        refreshFolderList()
        loadHistory()
        connectPlaybackService()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Leaf,
                onPrimary = Color(0xFF141811),
                background = Canvas,
                surface = Panel,
                onSurface = Color(0xFFF0F3EF),
                secondary = Color(0xFF96B85C),
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
                    MusicApp(
                        library = library,
                        playlists = playlists,
                        player = mediaController,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        queueRevision = queueRevision,
                        onQueueChanged = { queueRevision++ },
                        externalPage = requestedPage,
                        onPageConsumed = { requestedPage = "" },
                        folders = folderUris,
                        foldersMissingAccess = foldersMissingAccess,
                        history = history,
                        accountEmail = accountSession?.email,
                        legacyDataAvailable = legacyDataAvailable,
                        driveConnected = driveConnected,
                        spotifyConnected = spotifyConnected,
                        driveFolders = driveFolders,
                        spotifyPlaylists = spotifyPlaylists,
                        providerProgress = providerProgress,
                        syncStatus = syncStatus,
                        passwordRecoveryMode = passwordRecoveryMode,
                        onSignIn = { email, password -> authenticate(email, password, signUp = false) },
                        onSignUp = { email, password -> authenticate(email, password, signUp = true) },
                        onRecover = { email -> recoverAccount(email) },
                        onPasswordReset = { password -> updatePassword(password) },
                        onSignOut = { signOut() },
                        onImportLegacy = { importLegacyAccountData() },
                        onSync = { synchronizeAccount() },
                        onCancelSync = { cancelAccountSync() },
                        onConnectDrive = { connectGoogleDrive() },
                        onDisconnectDrive = { disconnectGoogleDrive() },
                        onListDriveFolders = { listGoogleDriveFolders() },
                        onIndexDriveFolder = { folder -> indexGoogleDriveFolder(folder) },
                        onConnectSpotify = { connectSpotify() },
                        onDisconnectSpotify = { disconnectSpotify() },
                        onListSpotifyPlaylists = { listSpotifyPlaylists() },
                        onImportSpotifyPlaylist = { playlist -> importSpotifyPlaylist(playlist) },
                        onCancelProvider = { cancelProviderOperation() },
                        onChooseFiles = {
                            if (libraryLoaded) audioPicker.launch(arrayOf("audio/*"))
                            else Toast.makeText(this, "Aguarde a abertura da biblioteca local.", Toast.LENGTH_SHORT).show()
                        },
                        onChooseFolder = { folderPicker.launch(null) },
                        onRepairFolderAccess = { uri -> folderPicker.launch(Uri.parse(uri)) },
                        onRefreshFolders = { refreshAllFolders() },
                        onRemoveFolder = { uri -> removeFolder(uri) },
                        onRemoveTrack = { track -> removeTrackFromLibrary(track) },
                        onToggleFavorite = { track -> toggleFavorite(track) },
                        onOpenPlayer = { setPage("Player") },
                        onOpenQueue = { setPage("Fila") },
                        onImportCsv = { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.ms-excel")) },
                        onCreatePlaylist = { name, text -> createPlaylist(name, text) },
                        onPlay = { track -> playTrack(track) },
                        onPlayPlaylist = { playlist -> playPlaylist(playlist) },
                        onPlayQueueIndex = { index -> playQueueIndex(index) },
                        onAddToQueue = { track -> addToQueue(track) },
                        onVerify = { verifyLocalLibrary() },
                    )
                }

            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    private fun connectGoogleDrive() {
        try {
            startActivity(googleDriveClient.authorizationIntent())
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Configure o Client ID do Google Drive.", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnectGoogleDrive() {
        cancelProviderOperation()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { googleDriveClient.signOut() }
            driveConnected = false
            driveFolders = emptyList()
            Toast.makeText(this@MainActivity, "Google Drive desconectado neste aparelho.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listGoogleDriveFolders() {
        googleDriveClient.cancel()
        providerOperation?.cancel()
        providerOperation = lifecycleScope.launch {
            providerProgress = "Carregando pastas do Google Drive…"
            try {
                driveFolders = withContext(Dispatchers.IO) { googleDriveClient.listFolders() }
                driveConnected = true
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Não foi possível listar as pastas do Google Drive.", Toast.LENGTH_LONG).show()
            } finally {
                providerProgress = null
            }
        }
    }

    private fun indexGoogleDriveFolder(folder: GoogleDriveFile) {
        googleDriveClient.cancel()
        providerOperation?.cancel()
        providerOperation = lifecycleScope.launch {
            providerProgress = "Lendo a pasta ${folder.name}…"
            try {
                val files = withContext(Dispatchers.IO) { googleDriveClient.listAudioFiles(folder.id) }
                val folderUri = "gdrive://folder/${folder.id}"
                accountSession?.userId?.let { ownerId ->
                    preferences.edit().putString("drive-folder-$ownerId", folder.id).apply()
                }
                val existing = library.filter { it.folderUri == folderUri }.associateBy { it.id }
                val indexed = files.map { file ->
                    val id = "gdrive:${file.id}"
                    existing[id]?.copy(
                        name = file.name,
                        title = existing[id]?.title?.ifBlank { file.name.substringBeforeLast('.') } ?: file.name.substringBeforeLast('.'),
                        revision = file.revision,
                        folderUri = folderUri,
                    ) ?: AudioTrack(
                        id = id,
                        uri = "ascdrive://file/${file.id}",
                        name = file.name,
                        title = file.name.substringBeforeLast('.'),
                        artist = "",
                        album = "",
                        durationMs = 0,
                        format = file.name.substringAfterLast('.', ""),
                        metadataError = "Metadados serão lidos quando disponíveis; confirme correspondências manualmente.",
                        revision = file.revision,
                        folderUri = folderUri,
                    )
                }
                val removed = library.filter { it.folderUri == folderUri && indexed.none { next -> next.id == it.id } }.map { it.id }
                library.removeAll { it.folderUri == folderUri }
                library.addAll(indexed)
                persistLibrary(indexed, removed)
                driveConnected = true
                Toast.makeText(this@MainActivity, "${indexed.size} arquivo(s) do Drive indexado(s).", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Não foi possível indexar a pasta do Drive.", Toast.LENGTH_LONG).show()
            } finally {
                providerProgress = null
            }
        }
    }

    private fun cancelProviderOperation() {
        googleDriveClient.cancel()
        spotifyClient.cancel()
        providerOperation?.cancel()
        providerOperation = null
        providerProgress = null
    }

    private fun connectSpotify() {
        try {
            startActivity(spotifyClient.authorizationIntent())
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Configure o Client ID do Spotify.", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnectSpotify() {
        spotifyClient.disconnect()
        spotifyConnected = false
        spotifyPlaylists = emptyList()
        Toast.makeText(this, "Spotify desconectado neste aparelho.", Toast.LENGTH_SHORT).show()
    }

    private fun listSpotifyPlaylists() {
        spotifyClient.cancel()
        providerOperation?.cancel()
        providerOperation = lifecycleScope.launch {
            providerProgress = "Carregando playlists do Spotify…"
            try {
                spotifyPlaylists = withContext(Dispatchers.IO) { spotifyClient.listPlaylists() }
                spotifyConnected = true
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Não foi possível listar as playlists do Spotify.", Toast.LENGTH_LONG).show()
            } finally {
                providerProgress = null
            }
        }
    }

    private fun importSpotifyPlaylist(playlist: SpotifyPlaylist) {
        spotifyClient.cancel()
        providerOperation?.cancel()
        providerOperation = lifecycleScope.launch {
            providerProgress = "Importando ${playlist.name}…"
            try {
                val tracks = withContext(Dispatchers.IO) { spotifyClient.readPlaylistTracks(playlist.id) }
                val imported = MusicPlaylist(
                    id = UUID.randomUUID().toString(),
                    name = playlist.name,
                    source = "Spotify",
                    tracks = tracks.map { track ->
                        PlaylistTrack(UUID.randomUUID().toString(), track.title, track.artist, track.album, track.durationMs, isrc = track.isrc)
                    },
                )
                playlists.add(0, imported)
                persistPlaylists(imported)
                Toast.makeText(this@MainActivity, "Playlist Spotify importada.", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Não foi possível importar a playlist.", Toast.LENGTH_LONG).show()
            } finally {
                providerProgress = null
            }
        }
    }

    private fun handleAuthCallback(intent: Intent?) {
        val callbackUri = intent?.data ?: return
        if (callbackUri.scheme == "ascmusic" && callbackUri.host == "drive") {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { googleDriveClient.completeAuthorization(callbackUri) }
                    driveConnected = true
                    Toast.makeText(this@MainActivity, "Google Drive conectado.", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(this@MainActivity, error.message ?: "Não foi possível conectar o Google Drive.", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (callbackUri.scheme == "ascmusic" && callbackUri.host == "spotify") {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { spotifyClient.completeAuthorization(callbackUri) }
                    spotifyConnected = true
                    Toast.makeText(this@MainActivity, "Spotify conectado.", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(this@MainActivity, error.message ?: "Não foi possível conectar o Spotify.", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val callback = try {
            parseSupabaseAuthCallback(callbackUri.toString())
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, "O link de autenticação do ASC Music é inválido ou expirou.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { authClient.acceptAuthCallback(callback) }
                accountSession = session
                passwordRecoveryMode = callback.type == "recovery"
                syncStatus = if (passwordRecoveryMode) "Defina uma nova senha" else "Pronto para sincronizar"
                if (!passwordRecoveryMode && libraryLoaded && playlistsLoaded) refreshAccountLibraryState()
                reloadPlaybackForCurrentAccount()
                Toast.makeText(
                    this@MainActivity,
                    if (passwordRecoveryMode) "Link confirmado. Defina sua nova senha." else "E-mail confirmado. Conta conectada.",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "Não foi possível concluir o link de autenticação.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun authenticate(email: String, password: String, signUp: Boolean) {
        lifecycleScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    if (signUp) authClient.signUp(email, password) else authClient.signIn(email, password)
                }
                accountSession = session
                if (session == null) {
                    syncStatus = "Confirme seu e-mail para entrar."
                    Toast.makeText(this@MainActivity, "Cadastro enviado. Confirme o e-mail antes de entrar.", Toast.LENGTH_LONG).show()
                } else {
                    syncStatus = "Pronto para sincronizar"
                    if (libraryLoaded && playlistsLoaded) refreshAccountLibraryState()
                    reloadPlaybackForCurrentAccount()
                    Toast.makeText(this@MainActivity, "Conta conectada.", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                syncStatus = "Falha na conta"
                Toast.makeText(this@MainActivity, error.message ?: "Não foi possível autenticar.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun recoverAccount(email: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { authClient.recover(email) }
                Toast.makeText(this@MainActivity, "Se a conta existir, as instruções de recuperação serão enviadas.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Não foi possível solicitar a recuperação.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { authClient.signOut() }
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, "Sessão removida deste aparelho; encerramento remoto indisponível: ${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                accountSession = null
                syncStatus = "Entre para sincronizar"
                    if (libraryLoaded && playlistsLoaded) refreshAccountLibraryState()
                reloadPlaybackForCurrentAccount()
            }
        }
    }

    private data class AccountView(
        val tracks: List<AudioTrack>,
        val playlists: List<MusicPlaylist>,
        val history: List<AudioTrack>,
        val session: SupabaseSession?,
        val legacyAvailable: Boolean,
    )

    private fun refreshAccountLibraryState() {
        lifecycleScope.launch {
            try {
                val view = withContext(Dispatchers.IO) {
                    val session = authClient.validSession()
                    val localTracks = libraryDatabase.readTracks()
                    if (session == null) {
                        AccountView(
                            tracks = localTracks.map { it.copy(favorite = false) },
                            playlists = emptyList(),
                            history = emptyList(),
                            session = null,
                            legacyAvailable = false,
                        )
                    } else {
                        val ownerId = session.userId
                        val favorites = libraryDatabase.readAccountFavoriteStates(ownerId)
                        val accountTracks = localTracks.map { track ->
                            val identity = stableTrackId(track.title, track.artist, track.album)
                            track.copy(favorite = favorites[identity] == true)
                        }
                        AccountView(
                            tracks = accountTracks,
                            playlists = libraryDatabase.readAccountPlaylists(ownerId),
                            history = libraryDatabase.readAccountHistory(ownerId),
                            session = session,
                            legacyAvailable = !preferences.getBoolean("legacy-imported-$ownerId", false) && (
                                libraryDatabase.readPlaylists().isNotEmpty()
                                    || localTracks.any { it.favorite }
                                    || libraryDatabase.readHistory().isNotEmpty()
                                    || libraryDatabase.readPlaybackSnapshot().trackIds.isNotEmpty()
                            ),
                        )
                    }
                }
                accountSession = view.session
                library.clear()
                library.addAll(view.tracks)
                playlists.clear()
                playlists.addAll(view.playlists)
                history = view.history
                legacyDataAvailable = view.legacyAvailable
                reloadPlaybackForCurrentAccount()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, "Os dados da conta não puderam ser carregados: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playlistPayload(playlist: MusicPlaylist): JSONObject = JSONObject()
        .put("name", playlist.name)
        .put("source", playlist.source.ifBlank { "manual" })
        .put("tracks", JSONArray().apply {
            playlist.tracks.forEach { track ->
                put(
                    JSONObject()
                        .put("title", track.title)
                        .put("artist", track.artist)
                        .put("album", track.album)
                        .put("durationSeconds", track.durationMs / 1000.0)
                        .put("version", track.version)
                        .put("isrc", track.isrc),
                )
            }
        })

    private fun synchronizeAccount() {
        val session = accountSession
        if (session == null) {
            syncStatus = "Entre para sincronizar"
            return
        }
        if (syncJob?.isActive == true) return
        syncJob = lifecycleScope.launch {
            syncStatus = "Sincronizando"
            try {
                val worker = WorkerSyncClient(authClient, libraryDatabase)
                activeWorkerSync = worker
                val outcome = withContext(Dispatchers.IO) {
                    worker.synchronize()
                }
                refreshAccountLibraryState()
                syncStatus = if (outcome.pendingChanges == 0) "Sincronizado" else "${outcome.pendingChanges} alterações pendentes"
            } catch (error: WorkerSyncClient.SyncConflictException) {
                syncStatus = "Conflito: dados locais preservados"
                showSyncConflictDialog(error)
            } catch (error: Exception) {
                syncStatus = "Sincronização interrompida"
                Toast.makeText(this@MainActivity, error.message ?: "Falha ao sincronizar.", Toast.LENGTH_LONG).show()
            } finally {
                activeWorkerSync = null
                syncJob = null
            }
        }
    }

    private fun cancelAccountSync() {
        activeWorkerSync?.cancel()
        syncJob?.cancel()
        syncJob = null
        syncStatus = "Sincronização pausada; alterações preservadas"
    }

    private fun showSyncConflictDialog(conflict: WorkerSyncClient.SyncConflictException) {
        val remote = conflict.remote
        val ownerId = accountSession?.userId
        if (remote == null || ownerId == null) {
            Toast.makeText(this, conflict.message, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Conflito de sincronização")
            .setMessage("A entidade ${conflict.change.type} / ${conflict.change.id} também foi alterada em outra sessão.")
            .setNegativeButton("Manter minha versão") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            libraryDatabase.rebaseSyncChange(ownerId, conflict.change, remote.optLong("version"))
                        }
                        synchronizeAccount()
                    } catch (error: Exception) {
                        Toast.makeText(this@MainActivity, error.message ?: "Não foi possível reprocessar o conflito.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setPositiveButton("Usar versão remota") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val cursor = libraryDatabase.readSyncCursor(ownerId)
                            libraryDatabase.applyRemoteChanges(ownerId, listOf(remote), cursor)
                            libraryDatabase.acknowledgeSyncChange(ownerId, conflict.change, remote.optLong("version"))
                        }
                        refreshAccountLibraryState()
                    } catch (error: Exception) {
                        Toast.makeText(this@MainActivity, error.message ?: "Não foi possível aceitar a versão remota.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun connectPlaybackService() {
        val serviceComponent = ComponentName(this, MusicPlaybackService::class.java)
        val token: SessionToken = SessionToken(this, serviceComponent)
        val future: ListenableFuture<MediaController> = MediaController.Builder(this, token).buildAsync()
        playbackFuture = future
        future.addListener({
            try {
                mediaController = future.get()
                mediaController?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentTrack = library.find { it.id == mediaItem?.mediaId }
                        queueRevision++
                        loadHistory()
                    }

                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        queueRevision++
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val controller = mediaController
                        Toast.makeText(this@MainActivity, "Falha ao reproduzir o arquivo; verificando a próxima faixa: ${error.message}", Toast.LENGTH_LONG).show()
                        if (controller?.hasNextMediaItem() == true) {
                            controller.seekToNextMediaItem()
                            controller.prepare()
                            controller.play()
                        }
                    }
                })
                mediaController?.let { controller ->
                    isPlaying = controller.isPlaying
                    currentTrack = library.find { it.id == controller.currentMediaItem?.mediaId }
                    queueRevision++
                }
            } catch (error: Exception) {
                Toast.makeText(this, "O player em segundo plano não pôde iniciar: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun reloadPlaybackForCurrentAccount() {
        startService(
            Intent(this, MusicPlaybackService::class.java).setAction(
                MusicPlaybackService.ACTION_RELOAD_ACCOUNT,
            ),
        )
    }

    private fun readAudioTrack(uri: Uri): AudioTrack = try {
        audioIndexer.readAudioFile(uri)
    } catch (error: SecurityException) {
        unreadableTrack(uri, error)
    } catch (error: RuntimeException) {
        unreadableTrack(uri, error)
    }

    private fun unreadableTrack(uri: Uri, error: RuntimeException): AudioTrack {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "Arquivo sem nome"
        return AudioTrack(
            id = uri.toString(),
            uri = uri.toString(),
            name = name,
            title = name.substringBeforeLast("."),
            artist = "",
            album = "",
            durationMs = 0,
            format = name.substringAfterLast(".", ""),
            metadataError = error.message ?: "Não foi possível ler os metadados. Verifique o acesso ao arquivo.",
        )
    }

    private fun restoreLibrary() {
        lifecycleScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val indexed = libraryDatabase.readTracks()
                    if (indexed.isNotEmpty()) {
                        preferences.edit().remove("library").apply()
                        indexed
                    } else {
                        val legacy = JSONArray(preferences.getString("library", "[]") ?: "[]")
                        val tracks = (0 until legacy.length()).map { index ->
                            val item = legacy.getJSONObject(index)
                            AudioTrack(
                                id = item.getString("id"),
                                uri = item.getString("uri"),
                                name = item.getString("name"),
                                title = item.optString("title"),
                                artist = item.optString("artist"),
                                album = item.optString("album"),
                                durationMs = item.optLong("durationMs"),
                                format = item.optString("format"),
                                metadataError = item.optString("metadataError"),
                                version = item.optString("version"),
                                revision = item.optString("revision"),
                                isrc = item.optString("isrc"),
                            )
                        }
                        if (tracks.isNotEmpty()) libraryDatabase.applyChanges(tracks)
                        preferences.edit().remove("library").apply()
                        tracks
                    }
                }
                library.clear()
                library.addAll(restored)
                libraryLoaded = true
                currentTrack = library.find { it.id == mediaController?.currentMediaItem?.mediaId }
                refreshAllFolders(showResult = false)
                if (playlistsLoaded) refreshAccountLibraryState()
            } catch (error: org.json.JSONException) {
                libraryLoaded = true
                Toast.makeText(this@MainActivity, "O índice local antigo estava inválido e não pôde ser migrado.", Toast.LENGTH_LONG).show()
            } catch (error: android.database.sqlite.SQLiteException) {
                libraryLoaded = true
                Toast.makeText(this@MainActivity, "O banco do índice local não pôde ser aberto: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun persistLibrary(
        tracks: List<AudioTrack> = library.toList(),
        removedIds: Collection<String> = emptyList(),
    ) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val existingTracks = libraryDatabase.readTracks()
                    val existingById = existingTracks.associateBy { it.id }
                    val localFavorites = existingTracks.associate { it.id to it.favorite }
                    val deviceTracks = tracks.map { track ->
                        track.copy(favorite = localFavorites[track.id] ?: track.favorite)
                    }
                    libraryDatabase.applyChanges(deviceTracks, removedIds)
                    val ownerId = authClient.validSession()?.userId
                    if (ownerId != null) enqueueLibraryMetadataSync(ownerId, deviceTracks, removedIds, existingById)
                }
            } catch (error: android.database.sqlite.SQLiteException) {
                Toast.makeText(this@MainActivity, "Não foi possível salvar o índice local: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun librarySyncPayload(track: AudioTrack): JSONObject = JSONObject()
        .put("source", if (track.uri.startsWith("content://")) "local" else "drive")
        .put("name", track.name)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album)
        .put("durationSeconds", track.durationMs / 1000.0)
        .put("format", track.format)
        .put("revision", track.revision)
        .put("isrc", track.isrc)
        .put("artists", JSONArray())

    private fun enqueueLibraryMetadataSync(
        ownerId: String,
        tracks: List<AudioTrack>,
        removedIds: Collection<String>,
        existingById: Map<String, AudioTrack>,
    ) {
        tracks.forEach { track ->
            libraryDatabase.enqueueSyncChange(
                ownerId,
                "library",
                stableTrackId(track.title, track.artist, track.album),
                librarySyncPayload(track),
            )
        }
        removedIds.mapNotNull { existingById[it] }.forEach { track ->
            libraryDatabase.enqueueSyncChange(
                ownerId,
                "library",
                stableTrackId(track.title, track.artist, track.album),
                JSONObject(),
                deleted = true,
            )
        }
    }

    private fun restorePlaylists() {
        lifecycleScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val ownerId = authClient.validSession()?.userId
                    if (ownerId == null) emptyList() else libraryDatabase.readAccountPlaylists(ownerId)
                }
                playlists.clear()
                playlists.addAll(restored)
                playlistsLoaded = true
                if (libraryLoaded) refreshAccountLibraryState()
            } catch (error: org.json.JSONException) {
                Toast.makeText(this@MainActivity, "As playlists locais antigas não puderam ser migradas: ${error.message}", Toast.LENGTH_LONG).show()
            } catch (error: android.database.sqlite.SQLiteException) {
                Toast.makeText(this@MainActivity, "As playlists SQLite não puderam ser carregadas: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshFolderList() {
        lifecycleScope.launch {
            try {
                val folders = withContext(Dispatchers.IO) { libraryDatabase.readFolders() }
                val persistedReadUris = contentResolver.persistedUriPermissions
                    .filter { it.isReadPermission }
                    .map { it.uri.toString() }
                    .toSet()
                folderUris = folders
                foldersMissingAccess = missingPersistedFolderAccess(folders, persistedReadUris)
            } catch (error: android.database.sqlite.SQLiteException) {
                Toast.makeText(this@MainActivity, "As pastas da biblioteca não puderam ser carregadas: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshAllFolders(showResult: Boolean = true) {
        lifecycleScope.launch { refreshAllFoldersNow(showResult) }
    }

    private suspend fun refreshAllFoldersNow(showResult: Boolean) {
        try {
            val folders = withContext(Dispatchers.IO) { libraryDatabase.readFolders() }
            if (folders.isEmpty()) {
                if (showResult) Toast.makeText(this@MainActivity, "Adicione uma pasta de músicas para indexar.", Toast.LENGTH_SHORT).show()
                return
            }
            folders.forEach { (uri, label) -> refreshFolder(uri, label, showResult = false) }
            if (showResult) Toast.makeText(this@MainActivity, "Verificação das pastas concluída.", Toast.LENGTH_SHORT).show()
        } catch (error: RuntimeException) {
            Toast.makeText(this@MainActivity, "As pastas não puderam ser verificadas: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun refreshFolder(
        uriValue: String,
        label: String,
        showResult: Boolean = true,
        persistFolder: Boolean = true,
    ) {
        val uri = Uri.parse(uriValue)
        val existing = library.toList()
        val (scan, excludedIds) = withContext(Dispatchers.IO) {
            val excluded = libraryDatabase.readExcludedTrackIds()
            audioIndexer.scanFolder(uri, existing, excluded) to excluded
        }
        val reconciliation = reconcileFolderScan(existing, uriValue, scan, excludedIds)
        withContext(Dispatchers.IO) {
            libraryDatabase.applyChanges(reconciliation.scannedTracks, reconciliation.removedIds, clearExclusions = false)
            if (persistFolder) libraryDatabase.saveFolder(uriValue, label)
            authClient.validSession()?.userId?.let { ownerId ->
                enqueueLibraryMetadataSync(
                    ownerId,
                    reconciliation.scannedTracks,
                    reconciliation.removedIds,
                    existing.associateBy { it.id },
                )
            }
        }
        library.clear()
        library.addAll(reconciliation.tracks)
        libraryLoaded = true
        if (showResult) {
            val detail = if (scan.complete) "" else " Alguns arquivos não puderam ser examinados; itens antigos foram preservados."
            Toast.makeText(this, "${countLabel(reconciliation.scannedTracks.size, "música indexada", "músicas indexadas")} em \"$label\".${if (scan.failures > 0) " ${countLabel(scan.failures, "falha", "falhas")}." else ""}$detail", Toast.LENGTH_LONG).show()
        }
    }

    private fun folderLabel(treeUri: Uri): String {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        } ?: "Pasta de músicas"
    }

    private fun removeFolder(uri: String) {
        lifecycleScope.launch {
            try {
                val removedTracks = library.filter { it.folderUri == uri }
                withContext(Dispatchers.IO) {
                    libraryDatabase.removeFolder(uri)
                    authClient.validSession()?.userId?.let { ownerId ->
                        enqueueLibraryMetadataSync(ownerId, emptyList(), removedTracks.map { it.id }, removedTracks.associateBy { it.id })
                    }
                }
                val removedTrackIds = library.filter { it.folderUri == uri }.map { it.id }.toSet()
                library.removeAll { it.folderUri == uri }
                removeTrackIdsFromQueue(removedTrackIds)
                loadHistory()
                refreshFolderList()
                try {
                    contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (error: SecurityException) {
                    Toast.makeText(this@MainActivity, "A pasta saiu do índice, mas a permissão não pôde ser liberada: ${error.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(this@MainActivity, "Pasta removida do índice. Os arquivos originais não foram alterados.", Toast.LENGTH_LONG).show()
            } catch (error: android.database.sqlite.SQLiteException) {
                Toast.makeText(this@MainActivity, "A pasta não pôde ser removida do índice: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun removeTrackFromLibrary(track: AudioTrack) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    libraryDatabase.removeTrackFromIndex(track.id)
                    authClient.validSession()?.userId?.let { ownerId ->
                        enqueueLibraryMetadataSync(ownerId, emptyList(), listOf(track.id), mapOf(track.id to track))
                    }
                }
                library.removeAll { it.id == track.id }
                removeTrackIdsFromQueue(setOf(track.id))
                loadHistory()
                Toast.makeText(this@MainActivity, "Removida da biblioteca; o arquivo original foi mantido.", Toast.LENGTH_SHORT).show()
            } catch (error: android.database.sqlite.SQLiteException) {
                Toast.makeText(this@MainActivity, "A música não pôde ser removida do índice: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun removeTrackIdsFromQueue(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        val player = mediaController ?: return
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(index).mediaId in trackIds) player.removeMediaItem(index)
        }
        queueRevision++
    }

    private fun toggleFavorite(track: AudioTrack) {
        val updated = track.copy(favorite = !track.favorite)
        lifecycleScope.launch {
            try {
                val ownerId = withContext(Dispatchers.IO) { authClient.validSession()?.userId }
                    ?: throw IllegalStateException("Entre na conta ASC Music para alterar favoritos.")
                withContext(Dispatchers.IO) {
                    val identity = stableTrackId(track.title, track.artist, track.album)
                    libraryDatabase.setAccountFavorite(ownerId, identity, updated.favorite)
                    libraryDatabase.enqueueSyncChange(
                        ownerId,
                        "favorite",
                        identity,
                        JSONObject().put("favorite", updated.favorite),
                    )
                }
                val index = library.indexOfFirst { it.id == track.id }
                if (index >= 0) library[index] = updated
                history = history.map { if (it.id == track.id) updated else it }
            } catch (error: android.database.sqlite.SQLiteException) {
                Toast.makeText(this@MainActivity, "O favorito não pôde ser salvo: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            try {
                history = withContext(Dispatchers.IO) {
                    val ownerId = authClient.validSession()?.userId
                    if (ownerId == null) emptyList() else libraryDatabase.readAccountHistory(ownerId)
                }
            } catch (error: android.database.sqlite.SQLiteException) {
                Toast.makeText(this@MainActivity, "O histórico não pôde ser carregado: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importLegacyAccountData() {
        lifecycleScope.launch {
            try {
                val ownerId = withContext(Dispatchers.IO) { authClient.validSession()?.userId }
                    ?: throw IllegalStateException("Entre na conta antes de importar dados legados.")
                withContext(Dispatchers.IO) {
                    val legacyPlaylists = libraryDatabase.readPlaylists()
                    val mergedPlaylists = libraryDatabase.readAccountPlaylists(ownerId).toMutableList()
                    val usedIds = mergedPlaylists.map { it.id }.toMutableSet()
                    legacyPlaylists.forEach { legacy ->
                        val id = if (usedIds.add(legacy.id)) legacy.id else "legacy-${UUID.randomUUID()}"
                        usedIds.add(id)
                        mergedPlaylists += legacy.copy(id = id)
                    }
                    libraryDatabase.saveAccountPlaylists(ownerId, mergedPlaylists)

                    val legacyTracks = libraryDatabase.readTracks()
                    legacyTracks.filter { it.favorite }.forEach { track ->
                        val trackId = stableTrackId(track.title, track.artist, track.album)
                        libraryDatabase.setAccountFavorite(ownerId, trackId, true)
                        libraryDatabase.enqueueSyncChange(ownerId, "favorite", trackId, JSONObject().put("favorite", true))
                    }
                    libraryDatabase.readHistory().forEach { track ->
                        val playedAt = System.currentTimeMillis()
                        val trackId = stableTrackId(track.title, track.artist, track.album)
                        libraryDatabase.addAccountHistory(
                            ownerId,
                            trackId,
                            playedAt,
                            Triple(track.title.ifBlank { track.name }, track.artist, track.album),
                        )
                        libraryDatabase.enqueueSyncChange(
                            ownerId,
                            "history",
                            "history-${UUID.randomUUID()}",
                            JSONObject()
                                .put("trackId", trackId)
                                .put("title", track.title.ifBlank { track.name })
                                .put("artist", track.artist)
                                .put("album", track.album)
                                .put("playedAt", playedAt),
                        )
                    }
                    val legacyQueue = libraryDatabase.readPlaybackSnapshot()
                    if (legacyQueue.trackIds.isNotEmpty()) {
                        libraryDatabase.saveAccountQueue(ownerId, legacyQueue)
                        libraryDatabase.enqueueSyncChange(ownerId, "setting", "shuffle", JSONObject().put("value", legacyQueue.shuffleEnabled))
                        libraryDatabase.enqueueSyncChange(ownerId, "setting", "repeat", JSONObject().put("value", legacyQueue.repeatMode.name.lowercase()))
                        libraryDatabase.enqueueSyncChange(ownerId, "setting", "volume", JSONObject().put("value", legacyQueue.volume.toDouble()))
                    }
                    mergedPlaylists.forEach { playlist ->
                        libraryDatabase.enqueueSyncChange(ownerId, "playlist", playlist.id, playlistPayload(playlist))
                    }
                    preferences.edit().putBoolean("legacy-imported-$ownerId", true).apply()
                }
                legacyDataAvailable = false
                refreshAccountLibraryState()
                synchronizeAccount()
                Toast.makeText(this@MainActivity, "Dados legados importados para esta conta; as tabelas antigas foram preservadas.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Não foi possível importar os dados legados.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setPage(page: String) {
        requestedPage = page
    }

    private fun parseLegacyPlaylists(): List<MusicPlaylist> {
        val saved = preferences.getString("playlists", "[]") ?: "[]"
        val records = JSONArray(saved)
        return (0 until records.length()).map { index ->
            val item = records.getJSONObject(index)
            val tracks = item.getJSONArray("tracks")
            val playlistTracks = (0 until tracks.length()).map { position ->
                val track = tracks.getJSONObject(position)
                PlaylistTrack(
                    id = track.getString("id"),
                    title = track.getString("title"),
                    artist = track.optString("artist"),
                    album = track.optString("album"),
                    durationMs = track.optLong("durationMs"),
                    version = track.optString("version"),
                    isrc = track.optString("isrc"),
                )
            }
            MusicPlaylist(item.getString("id"), item.getString("name"), item.optString("source"), playlistTracks)
        }
    }

    private fun persistPlaylists(changedPlaylist: MusicPlaylist? = null) {
        val snapshot = playlists.toList()
        lifecycleScope.launch {
            try {
                val ownerId = withContext(Dispatchers.IO) { authClient.validSession()?.userId }
                    ?: throw IllegalStateException("Entre na conta ASC Music para salvar playlists.")
                withContext(Dispatchers.IO) {
                    if (changedPlaylist != null) {
                        libraryDatabase.saveAccountPlaylist(ownerId, changedPlaylist)
                        libraryDatabase.storeRemoteSyncEntity(
                            ownerId,
                            JSONObject()
                                .put("type", "playlist")
                                .put("id", changedPlaylist.id)
                                .put("version", 0)
                                .put("deleted", false)
                                .put("payload", playlistPayload(changedPlaylist)),
                        )
                        libraryDatabase.enqueueSyncChange(ownerId, "playlist", changedPlaylist.id, playlistPayload(changedPlaylist))
                    } else {
                        libraryDatabase.saveAccountPlaylists(ownerId, snapshot)
                    }
                }
            } catch (error: android.database.sqlite.SQLiteException) {
                Toast.makeText(this@MainActivity, "As playlists não puderam ser salvas: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createPlaylist(name: String, text: String) {
        val playlist = try {
            createManualPlaylist(name, text)
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message, Toast.LENGTH_SHORT).show()
            return
        }
        playlists.add(0, playlist)
        persistPlaylists(playlist)
    }

    private fun verifyLocalLibrary() {
        if (!libraryLoaded) {
            Toast.makeText(this, "Aguarde a abertura da biblioteca local.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            refreshAllFoldersNow(showResult = false)
            val current = library.toList()
            val (updated, removed, failed) = withContext(Dispatchers.IO) {
                val next = mutableListOf<AudioTrack>()
                val deletedIds = mutableSetOf<String>()
                var failed = 0
                for (file in current) {
                    val uri = Uri.parse(file.uri)
                    val revision = try {
                        audioIndexer.revision(uri)
                    } catch (_: SecurityException) {
                        failed++
                        next.add(file)
                        continue
                    } catch (_: RuntimeException) {
                        failed++
                        next.add(file)
                        continue
                    }
                    if (revision.isBlank()) {
                        deletedIds.add(file.id)
                    } else if (revision != file.revision) {
                        next.add(readAudioTrack(uri).copy(favorite = file.favorite, folderUri = file.folderUri))
                    } else {
                        next.add(file)
                    }
                }
                Triple(next, deletedIds, failed)
            }
            library.clear()
            library.addAll(updated)
            persistLibrary(updated, removed)
            Toast.makeText(
                this@MainActivity,
                "Biblioteca verificada: ${countLabel(updated.size, "arquivo indexado", "arquivos indexados")}; ${countLabel(removed.size, "removido", "removidos")}; ${countLabel(failed, "não pôde ser verificado", "não puderam ser verificados")}.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun playTrack(track: AudioTrack) {
        ensureNotificationPermission()
        val player = mediaController ?: run {
            Toast.makeText(this, "O player ainda está se conectando.", Toast.LENGTH_SHORT).show()
            return
        }
        player.setMediaItems(listOf(track.toMediaItem()))
        player.prepare()
        player.play()
        currentTrack = track
        queueRevision++
    }

    private fun playPlaylist(playlist: MusicPlaylist) {
        ensureNotificationPermission()
        val player = mediaController ?: run {
            Toast.makeText(this, "O player ainda está se conectando.", Toast.LENGTH_SHORT).show()
            return
        }
        val available = playlist.tracks.mapNotNull { requested ->
            val match = compareTrack(requested, library)
            if (match.status in setOf(MatchStatus.AVAILABLE, MatchStatus.DUPLICATE)) match.candidates.firstOrNull() else null
        }
        if (available.isEmpty()) {
            Toast.makeText(this, "Esta playlist não tem faixas locais disponíveis.", Toast.LENGTH_LONG).show()
            return
        }
        val items = available.map { it.toMediaItem() }
        player.setMediaItems(items)
        player.prepare()
        player.play()
        currentTrack = available.first()
        queueRevision++
    }

    private fun playQueueIndex(index: Int) {
        val player = mediaController ?: return
        if (index !in 0 until player.mediaItemCount) return
        ensureNotificationPermission()
        player.seekTo(index, 0)
        player.prepare()
        player.play()
    }

    private fun addToQueue(track: AudioTrack) {
        val player = mediaController ?: run {
            Toast.makeText(this, "O player ainda está se conectando.", Toast.LENGTH_SHORT).show()
            return
        }
        val item = track.toMediaItem()
        if (player.mediaItemCount == 0) {
            ensureNotificationPermission()
            player.setMediaItem(item)
            player.prepare()
            player.play()
            currentTrack = track
        } else {
            player.addMediaItem(item)
            Toast.makeText(this, "Adicionada à fila.", Toast.LENGTH_SHORT).show()
        }
        queueRevision++
    }

    private fun AudioTrack.toMediaItem() = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkData(albumArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build(),
        )
        .build()

    override fun onDestroy() {
        playbackFuture?.let(MediaController::releaseFuture)
        mediaController = null
        if (libraryDatabaseDelegate.isInitialized()) libraryDatabase.close()
        super.onDestroy()
    }
}

@Composable
private fun MusicApp(
    library: List<AudioTrack>,
    playlists: List<MusicPlaylist>,
    player: MediaController?,
    currentTrack: AudioTrack?,
    isPlaying: Boolean,
    accountEmail: String?,
    legacyDataAvailable: Boolean,
    syncStatus: String,
    passwordRecoveryMode: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onRecover: (String) -> Unit,
    onPasswordReset: (String) -> Unit,
    onSignOut: () -> Unit,
    onImportLegacy: () -> Unit,
    onSync: () -> Unit,
    queueRevision: Int,
    onQueueChanged: () -> Unit,
    externalPage: String,
    onPageConsumed: () -> Unit,
    folders: List<Pair<String, String>>,
    foldersMissingAccess: Set<String>,
    history: List<AudioTrack>,
    onChooseFiles: () -> Unit,
    driveConnected: Boolean,
    spotifyConnected: Boolean,
    driveFolders: List<GoogleDriveFile>,
    spotifyPlaylists: List<SpotifyPlaylist>,
    providerProgress: String?,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit,
    onListDriveFolders: () -> Unit,
    onIndexDriveFolder: (GoogleDriveFile) -> Unit,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onListSpotifyPlaylists: () -> Unit,
    onImportSpotifyPlaylist: (SpotifyPlaylist) -> Unit,
    onCancelProvider: () -> Unit,
    onCancelSync: () -> Unit,
    onChooseFolder: () -> Unit,
    onRepairFolderAccess: (String) -> Unit,
    onRefreshFolders: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRemoveTrack: (AudioTrack) -> Unit,
    onToggleFavorite: (AudioTrack) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    onImportCsv: () -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onPlay: (AudioTrack) -> Unit,
    onPlayPlaylist: (MusicPlaylist) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onAddToQueue: (AudioTrack) -> Unit,
    onVerify: () -> Unit,
) {
    var page by remember { mutableStateOf("Início") }
    var detailPlaylist by remember { mutableStateOf<MusicPlaylist?>(null) }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(passwordRecoveryMode) }
    var accountMode by remember { mutableStateOf("login") }
    var accountEmailInput by remember { mutableStateOf("") }
    var accountPasswordInput by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("") }
    var playlistText by remember { mutableStateOf("") }
    var currentPosition by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(player?.volume ?: 1f) }
    var seekPreview by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(passwordRecoveryMode) {
        if (passwordRecoveryMode) {
            accountMode = "reset"
            showAccountDialog = true
        }
    }
    val allTracks = playlists.flatMap { it.tracks }
    val availability = allTracks.map { track -> compareTrack(track, library) }
    val matchSummary = summarizeMatches(availability)
    val missingAndReviewCount = matchSummary.missing + matchSummary.possible
    val duration = player?.duration?.takeIf { it > 0 } ?: currentTrack?.durationMs ?: 0L

    LaunchedEffect(player, currentTrack, queueRevision) {
        while (true) {
            currentPosition = player?.currentPosition?.coerceAtLeast(0)?.toFloat() ?: 0f
            volume = player?.volume ?: volume
            delay(500)
        }
    }

    LaunchedEffect(externalPage) {
        if (externalPage.isNotBlank()) {
            page = externalPage
            onPageConsumed()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ASC", color = Leaf, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            Text("  MUSIC", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showAccountDialog = true }) {
                Text(if (accountEmail == null) "Entrar" else "Conta", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf("Início", "Biblioteca", "Buscar", "Artistas", "Álbuns", "Playlists", "Fila", "ASC Match", "Faltantes", "Favoritas", "Histórico", "Descobrir").forEach { label ->
                val selected = page == label
                TextButton(
                    onClick = {
                        page = label
                        detailPlaylist = null
                        selectedArtist = null
                        selectedAlbum = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = if (selected) Leaf else Muted),
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            detailPlaylist != null -> PlaylistScreen(
                playlist = detailPlaylist!!,
                library = library,
                onPlayTrack = onPlay,
                onPlay = { onPlayPlaylist(detailPlaylist!!) },
                onBack = { detailPlaylist = null },
            )
            page == "Início" -> {
                Text("MÚSICA, SEM PONTAS SOLTAS", color = Leaf, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text("Sua música,\npor inteiro.", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, lineHeight = 42.sp)
                Spacer(Modifier.height(10.dp))
                Text("Compare playlists com seus arquivos e saiba o que está faltando antes de apertar play.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(19.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = onChooseFolder) { Text("＋ Selecionar pasta") }
                    OutlinedButton(onClick = onChooseFiles) { Text("Arquivos individuais") }
                }
                OutlinedButton(onClick = onVerify) { Text("⟳ Atualizar biblioteca") }
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(17.dp)) {
                        Text("PANORAMA ASC MATCH", color = Leaf, fontSize = 9.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            matchSummary.completeness?.let { "$it% disponíveis nas playlists" }
                                ?: "Importe uma playlist para calcular a disponibilidade",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 21.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${library.size} ${if (library.size == 1) "arquivo indexado" else "arquivos indexados"} · ${matchSummary.analyzed} músicas analisadas",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                        Text(
                            "${matchSummary.found} encontradas · ${matchSummary.missing} faltantes",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                        Text(
                            "${matchSummary.possible} para confirmar · ${matchSummary.duplicates} duplicadas (incluídas nas encontradas)",
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Seu próximo passo", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showImportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Importar playlist por texto ou CSV")
                }
                Spacer(Modifier.height(13.dp))
                Text("Reprodução Android usa Media3; os controles continuam na notificação e tela bloqueada.", color = Muted, fontSize = 10.sp)
            }
            page == "Biblioteca" -> {
                PageHeader("Sua coleção", "${library.size} ${if (library.size == 1) "arquivo indexado" else "arquivos indexados"}")
                val duplicates = duplicateTrackIds(library)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Button(onClick = onChooseFolder) { Text("＋ Selecionar pasta") }
                    OutlinedButton(onClick = onChooseFiles) { Text("Arquivos") }
                    OutlinedButton(onClick = onRefreshFolders) { Text("Atualizar") }
                    OutlinedButton(onClick = onVerify) { Text("Verificar") }
                }
                folders.forEach { (uri, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = Muted, fontSize = 10.sp)
                            if (uri in foldersMissingAccess) {
                                Text("Permissão de leitura não está salva", color = Color(0xFFEAC27E), fontSize = 9.sp)
                            }
                        }
                        if (uri in foldersMissingAccess) {
                            TextButton(onClick = { onRepairFolderAccess(uri) }) { Text("Reautorizar") }
                        }
                        TextButton(onClick = { onRemoveFolder(uri) }) { Text("Remover pasta") }
                    }
                }
                Spacer(Modifier.height(9.dp))
                if (library.any { it.metadataError.isNotBlank() }) {
                    Text("Alguns arquivos não têm metadados. O nome original foi preservado; confirme correspondências manualmente.", color = Color(0xFFEAC27E), fontSize = 10.sp)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(library, key = { _, track -> track.id }) { index, track ->
                        val note = if (track.id in duplicates) "Arquivo duplicado" else track.metadataError
                        TrackCard("${index + 1}. ${track.title}", track.artist.ifBlank { "Artista não identificado" }, track.album, note, onClick = { onPlay(track) }, showPlay = true, albumArt = track.albumArt)
                        TrackActions(track, onToggleFavorite, onAddToQueue, onRemoveTrack)
                    }
                }
            }
            page == "Artistas" -> {
                val artists = library.groupBy { it.artist.ifBlank { "Artista não identificado" } }.toSortedMap()
                selectedArtist?.let { artist ->
                    PageHeader(artist, countLabel(artists[artist]?.size ?: 0, "música", "músicas"))
                    OutlinedButton(onClick = { selectedArtist = null }) { Text("Voltar aos artistas") }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(artists[artist].orEmpty(), key = { _, track -> track.id }) { _, track ->
                            TrackCard(track.title, track.artist, track.album, track.metadataError, onClick = { onPlay(track) }, showPlay = true, albumArt = track.albumArt)
                            TrackActions(track, onToggleFavorite, onAddToQueue, onRemoveTrack)
                        }
                    }
                } ?: run {
                    PageHeader("Artistas", countLabel(artists.size, "artista", "artistas"))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        artists.forEach { (artist, tracks) ->
                            item(key = artist) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable { selectedArtist = artist },
                                    colors = CardDefaults.cardColors(containerColor = Panel),
                                ) {
                                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("♫", color = Leaf, fontSize = 20.sp)
                                        Text(artist, color = Color.White, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                        Text(countLabel(tracks.size, "música", "músicas"), color = Muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            page == "Buscar" -> {
                PageHeader("Buscar na biblioteca", countLabel(library.size, "arquivo indexado", "arquivos indexados"))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Título, artista, álbum ou arquivo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val query = searchQuery.trim()
                val results = if (query.isBlank()) emptyList() else library.filter { track ->
                    listOf(track.title, track.artist, track.album, track.name).any { value ->
                        value.contains(query, ignoreCase = true)
                    }
                }
                Text(
                    if (query.isBlank()) "Digite para pesquisar em toda a biblioteca."
                    else countLabel(results.size, "resultado", "resultados"),
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(results, key = { _, track -> track.id }) { _, track ->
                        TrackCard(track.title, track.artist.ifBlank { "Artista não identificado" }, track.album, track.metadataError, onClick = { onPlay(track) }, showPlay = true, albumArt = track.albumArt)
                        TrackActions(track, onToggleFavorite, onAddToQueue, onRemoveTrack)
                    }
                }
            }
            page == "Álbuns" -> {
                val albums = library.groupBy { it.album.ifBlank { "Álbum não identificado" } }.toSortedMap()
                selectedAlbum?.let { album ->
                    PageHeader(album, countLabel(albums[album]?.size ?: 0, "música", "músicas"))
                    OutlinedButton(onClick = { selectedAlbum = null }) { Text("Voltar aos álbuns") }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(albums[album].orEmpty(), key = { _, track -> track.id }) { _, track ->
                            TrackCard(track.title, track.artist, track.album, track.metadataError, onClick = { onPlay(track) }, showPlay = true, albumArt = track.albumArt)
                            TrackActions(track, onToggleFavorite, onAddToQueue, onRemoveTrack)
                        }
                    }
                } ?: run {
                    PageHeader("Álbuns", countLabel(albums.size, "álbum", "álbuns"))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        albums.forEach { (album, tracks) ->
                            item(key = album) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable { selectedAlbum = album },
                                    colors = CardDefaults.cardColors(containerColor = Panel),
                                ) {
                                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AlbumArt(tracks.firstOrNull()?.albumArt, 44.dp)
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(album, color = Color.White)
                                            Text(tracks.firstOrNull()?.artist.orEmpty(), color = Muted, fontSize = 10.sp)
                                        }
                                        Text(countLabel(tracks.size, "música", "músicas"), color = Muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            page == "Favoritas" -> {
                val favorites = library.filter { it.favorite }
                PageHeader("Favoritas", countLabel(favorites.size, "música", "músicas"))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(favorites, key = { _, track -> track.id }) { _, track ->
                        TrackCard(track.title, track.artist, track.album, track.metadataError, onClick = { onPlay(track) }, showPlay = true, albumArt = track.albumArt)
                        TrackActions(track, onToggleFavorite, onAddToQueue, onRemoveTrack)
                    }
                }
            }
            page == "Histórico" -> {
                PageHeader("Histórico", countLabel(history.size, "reprodução recente", "reproduções recentes"))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(history, key = { index, track -> "$index:${track.id}" }) { _, track ->
                        TrackCard(track.title, track.artist, track.album, "", onClick = { onPlay(track) }, showPlay = true, albumArt = track.albumArt)
                        TrackActions(track, onToggleFavorite, onAddToQueue, null)
                    }
                }
            }
            page == "Fila" -> {
                val queueSize = player?.mediaItemCount ?: 0
                PageHeader("Fila de reprodução", countLabel(queueSize, "faixa", "faixas"))
                if (queueSize == 0) {
                    Text("A fila está vazia. Reproduza uma música ou adicione faixas da biblioteca.", color = Muted, fontSize = 12.sp)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(queueSize, key = { index -> "${player?.getMediaItemAt(index)?.mediaId}:$index" }) { index ->
                            val item = player?.getMediaItemAt(index)
                            val track = library.firstOrNull { it.id == item?.mediaId }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TrackCard(
                                    "${if (index == player?.currentMediaItemIndex) "▶ " else ""}${track?.title ?: item?.mediaMetadata?.title ?: "Arquivo indisponível"}",
                                    track?.artist ?: item?.mediaMetadata?.artist?.toString().orEmpty(),
                                    track?.album ?: item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
                                    if (track == null) "Arquivo não está mais indexado" else "",
                                    onClick = { onPlayQueueIndex(index) },
                                    showPlay = true,
                                    albumArt = track?.albumArt,
                                )
                                TextButton(onClick = {
                                    player?.removeMediaItem(index)
                                    onQueueChanged()
                                }) { Text("×", color = Muted, fontSize = 20.sp) }
                            }
                        }
                    }
                }
            }
            page == "Player" -> {
                ExpandedPlayer(
                    track = currentTrack,
                    player = player,
                    durationMs = duration,
                    volume = volume,
                    onPositionPreview = { seekPreview = it },
                    onSeek = {
                        player?.seekTo(it.toLong())
                        currentPosition = it
                        seekPreview = null
                    },
                    displayedPosition = seekPreview ?: currentPosition,
                    onVolume = {
                        volume = it
                        player?.setVolume(it)
                    },
                    onOpenQueue = onOpenQueue,
                    onToggleFavorite = currentTrack?.let { track -> { onToggleFavorite(track) } },
                    onNext = { player?.seekToNextMediaItem() },
                    onPrevious = { player?.seekToPreviousMediaItem() },
                )
            }
            page == "Playlists" -> {
                PageHeader("Playlists", countLabel(playlists.size, "lista pessoal", "listas pessoais"))
                Button(onClick = { showImportDialog = true }) { Text("＋ Criar lista manual") }
                OutlinedButton(onClick = onImportCsv, modifier = Modifier.fillMaxWidth()) { Text("Importar arquivo CSV") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { detailPlaylist = playlist },
                            colors = CardDefaults.cardColors(containerColor = Panel),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("♫", color = Leaf, fontSize = 22.sp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(playlist.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("${playlist.tracks.size} faixas · ${playlist.source}", color = Muted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
            page == "ASC Match" -> {
                PageHeader("Completude da coleção", "${matchSummary.analyzed} músicas analisadas · ${library.size} ${if (library.size == 1) "arquivo indexado" else "arquivos indexados"}")
                Text(
                    matchSummary.completeness?.let { "$it% disponíveis · ${matchSummary.found} encontradas · ${matchSummary.missing} faltantes · ${matchSummary.possible} para confirmar · ${matchSummary.duplicates} duplicadas (incluídas nas encontradas)" }
                        ?: "Importe uma playlist para analisar as músicas da biblioteca.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                playlists.forEach { playlist ->
                    val percent = availabilityPercent(playlist, library)
                    OutlinedButton(onClick = { detailPlaylist = playlist }, modifier = Modifier.fillMaxWidth()) {
                        Text("${playlist.name} · $percent%")
                    }
                }
            }
            page == "Faltantes" -> {
                PageHeader("Músicas faltantes", "$missingAndReviewCount ${if (missingAndReviewCount == 1) "música para verificar" else "músicas para verificar"}")
                val rows = playlists.flatMap { playlist ->
                    playlist.tracks.mapNotNull { track ->
                        val match = compareTrack(track, library)
                        if (match.status in setOf(MatchStatus.AVAILABLE, MatchStatus.DUPLICATE)) null
                        else Triple(playlist, track, match)
                    }
                }
                if (rows.isEmpty()) Text("Nenhuma música pendente. Importe uma playlist para começar.", color = Muted, fontSize = 12.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.second.id }) { _, row ->
                        val (playlist, track, match) = row
                        TrackCard(
                            track.title,
                            "${track.artist.ifBlank { "Artista não identificado" }} · ${playlist.name}",
                            "",
                            if (match.status == MatchStatus.POSSIBLE_MATCH) "Possível correspondência — confirme a versão" else "Faltando",
                            onClick = { detailPlaylist = playlist },
                        )
                    }
                }
            }
            else -> {
                PageHeader("Descobrir", "Fontes online dependem de configuração")
                Text("Explore rankings e lançamentos na versão Web do ASC Music. As fontes mostram origem e data de atualização; não há scraping nem dados inventados.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(9.dp))
                OutlinedButton(onClick = onImportCsv) { Text("Importar playlist por CSV") }
            }
        }
        Spacer(Modifier.weight(1f, fill = false))
        currentTrack?.let { track ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(track.albumArt, 42.dp)
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(track.artist.ifBlank { "Artista não identificado" }, color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = onOpenQueue) { Text("≡", color = Leaf, fontSize = 19.sp) }
                    TextButton(onClick = onOpenPlayer) { Text("⤢", color = Leaf, fontSize = 18.sp) }
                    TextButton(onClick = { if (isPlaying) player?.pause() else player?.play() }) {
                        Text(if (isPlaying) "Ⅱ" else "▶", color = Leaf, fontSize = 20.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text(if (accountEmail == null) "Conta ASC Music" else "Conta e sincronização") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (accountEmail != null && !passwordRecoveryMode) {
                        Text(accountEmail)
                        Text(syncStatus, color = Muted)
                        if (syncStatus == "Sincronizando") {
                            TextButton(onClick = onCancelSync) { Text("Cancelar sincronização") }
                        }
                        Text("Integrações", color = Leaf, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (driveConnected) {
                                OutlinedButton(onClick = onListDriveFolders) { Text("Listar Drive") }
                                OutlinedButton(onClick = onDisconnectDrive) { Text("Desconectar") }
                            } else {
                                OutlinedButton(onClick = onConnectDrive) { Text("Conectar Drive") }
                            }
                            if (spotifyConnected) {
                                OutlinedButton(onClick = onListSpotifyPlaylists) { Text("Listar Spotify") }
                                OutlinedButton(onClick = onDisconnectSpotify) { Text("Desconectar") }
                            } else {
                                OutlinedButton(onClick = onConnectSpotify) { Text("Conectar Spotify") }
                            }
                        }
                        providerProgress?.let { progress ->
                            Text(progress, color = Muted, fontSize = 11.sp)
                            TextButton(onClick = onCancelProvider) { Text("Cancelar operação") }
                        }
                        driveFolders.take(20).forEach { folder ->
                            OutlinedButton(onClick = { onIndexDriveFolder(folder) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Usar Drive: ${folder.name}", maxLines = 1)
                            }
                        }
                        spotifyPlaylists.take(20).forEach { playlist ->
                            OutlinedButton(onClick = { onImportSpotifyPlaylist(playlist) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Importar Spotify: ${playlist.name}", maxLines = 1)
                            }
                        }
                        if (legacyDataAvailable) {
                            Text("Existem dados pessoais antigos preservados neste dispositivo.", color = Muted, fontSize = 11.sp)
                            OutlinedButton(onClick = onImportLegacy) { Text("Importar dados antigos") }
                        }
                    } else {
                        if (accountEmail != null) Text("Redefina a senha de $accountEmail")
                        OutlinedTextField(
                            value = if (passwordRecoveryMode) accountPasswordInput else accountEmailInput,
                            onValueChange = {
                                if (passwordRecoveryMode) accountPasswordInput = it else accountEmailInput = it
                            },
                            label = { Text(if (passwordRecoveryMode) "Nova senha (8 caracteres ou mais)" else "E-mail") },
                            singleLine = true,
                        )
                        if (accountMode != "recover" && !passwordRecoveryMode) {
                            OutlinedTextField(
                                value = accountPasswordInput,
                                onValueChange = { accountPasswordInput = it },
                                label = { Text("Senha (8 caracteres ou mais)") },
                                singleLine = true,
                            )
                        }
                        if (accountEmail == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { accountMode = "login" }) { Text("Entrar") }
                                TextButton(onClick = { accountMode = "signup" }) { Text("Criar conta") }
                                TextButton(onClick = { accountMode = "recover" }) { Text("Recuperar") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (accountEmail == null || passwordRecoveryMode) {
                    Button(onClick = {
                        when {
                            passwordRecoveryMode -> onPasswordReset(accountPasswordInput)
                            accountMode == "signup" -> onSignUp(accountEmailInput, accountPasswordInput)
                            accountMode == "recover" -> onRecover(accountEmailInput)
                            else -> onSignIn(accountEmailInput, accountPasswordInput)
                        }
                        if (!passwordRecoveryMode) {
                            if (accountMode != "recover") accountPasswordInput = ""
                            showAccountDialog = false
                        }
                    }) {
                        Text(
                            when {
                                passwordRecoveryMode -> "Atualizar senha"
                                accountMode == "signup" -> "Criar"
                                accountMode == "recover" -> "Enviar instruções"
                                else -> "Entrar"
                            },
                        )
                    }
                } else {
                    Button(onClick = { onSync() }) { Text("Sincronizar agora") }
                }
            },
            dismissButton = {
                Row {
                    if (accountEmail != null) {
                        TextButton(onClick = {
                            onSignOut()
                            showAccountDialog = false
                        }) { Text("Sair") }
                    }
                    TextButton(onClick = { showAccountDialog = false }) { Text("Fechar") }
                }
            },
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Montar uma playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(playlistName, { playlistName = it }, label = { Text("Nome da playlist") }, singleLine = true)
                    OutlinedTextField(
                        playlistText,
                        { playlistText = it },
                        label = { Text("Artista — Música (uma por linha)") },
                        minLines = 5,
                    )
                    Text("Sem autenticação Spotify? Cole as músicas ou importe um CSV.", color = Muted, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCreatePlaylist(playlistName, playlistText)
                    playlistName = ""
                    playlistText = ""
                    showImportDialog = false
                }) { Text("Criar playlist", color = Leaf) }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("Cancelar") } },
            containerColor = Panel,
            titleContentColor = Color.White,
            textContentColor = Color.White,
        )
    }
}

@Composable
private fun AlbumArt(data: ByteArray?, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(data) {
        data?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    if (bitmap == null) {
        Card(
            modifier = Modifier.size(size),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF252B22)),
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text("♫", color = Leaf, fontSize = (size.value * 0.42f).sp)
            }
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Capa do álbum",
            modifier = Modifier.size(size),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

@Composable
private fun TrackActions(
    track: AudioTrack,
    onToggleFavorite: (AudioTrack) -> Unit,
    onAddToQueue: (AudioTrack) -> Unit,
    onRemove: ((AudioTrack) -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onToggleFavorite(track) }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(if (track.favorite) "♥ Favorita" else "♡ Favoritar", color = if (track.favorite) Leaf else Muted, fontSize = 10.sp)
        }
        TextButton(onClick = { onAddToQueue(track) }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("＋ Fila", color = Leaf, fontSize = 10.sp)
        }
        if (onRemove != null) {
            TextButton(onClick = { onRemove(track) }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Remover", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ExpandedPlayer(
    track: AudioTrack?,
    player: MediaController?,
    durationMs: Long,
    volume: Float,
    displayedPosition: Float,
    onPositionPreview: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onOpenQueue: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val hasTrack = track != null && (player?.mediaItemCount ?: 0) > 0
    val displayPosition = displayedPosition.coerceIn(0f, durationMs.coerceAtLeast(0).toFloat())
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("TOCANDO AGORA", color = Leaf, fontSize = 9.sp, letterSpacing = 2.sp)
                Text(track?.album?.ifBlank { "ASC MUSIC" } ?: "ASC MUSIC", color = Muted, fontSize = 10.sp, maxLines = 1)
            }
            TextButton(onClick = onOpenQueue, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("≡ Fila", color = Leaf)
            }
        }
        Spacer(Modifier.height(15.dp))
        AlbumArt(track?.albumArt, 248.dp)
        Spacer(Modifier.height(20.dp))
        Text(track?.title ?: "Nenhuma música selecionada", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(track?.artist?.ifBlank { "Artista não identificado" } ?: "Escolha uma faixa da biblioteca", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Slider(
            value = displayPosition,
            onValueChange = onPositionPreview,
            onValueChangeFinished = { onSeek(displayPosition) },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            enabled = hasTrack && durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(displayPosition.toLong()), color = Muted, fontSize = 10.sp)
            Text(formatPlaybackTime(durationMs), color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { player?.shuffleModeEnabled = !(player?.shuffleModeEnabled ?: false) },
                enabled = hasTrack,
                modifier = Modifier.size(48.dp),
            ) { Text("⤨", color = if (player?.shuffleModeEnabled == true) Leaf else Muted, fontSize = 21.sp) }
            TextButton(onClick = onPrevious, enabled = hasTrack, modifier = Modifier.size(52.dp)) {
                Text("◂", color = Leaf, fontSize = 27.sp)
            }
            Button(
                onClick = { if (player?.isPlaying == true) player.pause() else player?.play() },
                enabled = hasTrack,
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(32.dp),
            ) { Text(if (player?.isPlaying == true) "Ⅱ" else "▶", fontSize = 21.sp) }
            TextButton(onClick = onNext, enabled = hasTrack, modifier = Modifier.size(52.dp)) {
                Text("▸", color = Leaf, fontSize = 27.sp)
            }
            TextButton(
                onClick = { player?.let { it.repeatMode = (it.repeatMode + 1) % 3 } },
                enabled = hasTrack,
                modifier = Modifier.size(48.dp),
            ) {
                val repeat = player?.repeatMode ?: Player.REPEAT_MODE_OFF
                Text(if (repeat == Player.REPEAT_MODE_ONE) "↻¹" else "↻", color = if (repeat == Player.REPEAT_MODE_OFF) Muted else Leaf, fontSize = 19.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("◖", color = Muted, fontSize = 17.sp)
            Slider(value = volume.coerceIn(0f, 1f), onValueChange = onVolume, valueRange = 0f..1f, modifier = Modifier.weight(1f))
            Text("${(volume * 100).toInt()}%", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 7.dp))
            TextButton(onClick = { onToggleFavorite?.invoke() }, enabled = track != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (track?.favorite == true) "♥" else "♡", color = if (track?.favorite == true) Leaf else Muted, fontSize = 21.sp)
            }
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    if (milliseconds <= 0) return "0:00"
    val totalSeconds = milliseconds / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(5.dp))
    Text(subtitle, color = Muted, fontSize = 11.sp)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun TrackCard(
    title: String,
    artist: String,
    album: String,
    note: String,
    onClick: (() -> Unit)? = null,
    showPlay: Boolean = false,
    albumArt: ByteArray? = null,
) {
    val cardModifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clickable { onClick() }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = Panel),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(albumArt, 42.dp)
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(listOf(artist, album).filter(String::isNotBlank).joinToString(" · "), color = Muted, fontSize = 9.sp)
                if (note.isNotBlank()) Text(note, color = Color(0xFFE8BF7B), fontSize = 9.sp)
            }
            if (showPlay) Text("▶", color = Leaf, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PlaylistScreen(
    playlist: MusicPlaylist,
    library: List<AudioTrack>,
    onPlayTrack: (AudioTrack) -> Unit,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(playlist.name, "${countLabel(playlist.tracks.size, "faixa", "faixas")} · ${availabilityPercent(playlist, library)}% disponíveis")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlay) { Text("▶ Tocar disponíveis") }
        OutlinedButton(onClick = onBack) { Text("Voltar") }
    }
    Spacer(Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        itemsIndexed(playlist.tracks, key = { _, track -> track.id }) { index, track ->
            val match = compareTrack(track, library)
            val playableTrack = if (match.status in setOf(MatchStatus.AVAILABLE, MatchStatus.DUPLICATE)) {
                match.candidates.firstOrNull()
            } else {
                null
            }
            val label = when (match.status) {
                MatchStatus.AVAILABLE -> "Disponível"
                MatchStatus.DUPLICATE -> "Duplicada"
                MatchStatus.POSSIBLE_MATCH -> "Possível correspondência — confirmar"
                MatchStatus.MISSING -> "Faltando"
            }
            TrackCard(
                "${index + 1}. ${track.title}",
                track.artist.ifBlank { "Artista não identificado" },
                track.album,
                label,
                onClick = playableTrack?.let { candidate -> { onPlayTrack(candidate) } },
                showPlay = playableTrack != null,
            )
        }
    }
}
