package bot;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MusicListener extends ListenerAdapter {

    private static final String PREFIX = "!";

    private static final String AJUDA = """
            🎵 **Comandos do bot de música**
            
            `!p <nome ou link>` — busca a música e toca (ou adiciona na fila). Aceita links do YouTube, inclusive Mix e playlist (toca todas)!
            `!pular` — pula para a próxima música
            `!pausar` — pausa a música
            `!continuar` — continua a música pausada
            `!parar` — para tudo, limpa a fila e sai do canal de voz
            `!fila` — mostra a fila de músicas
            `!tocando` — mostra a música que está tocando agora
            `!volume <0-100>` — muda o volume
            `!ajuda` — mostra esta lista
            """;

    /** Título e duração que vêm do yt-dlp (o player só enxerga um link "cru"). */
    private record Meta(String title, String duration) {}

    private record Resolved(String url, Meta meta) {}

    /** Máximo de músicas carregadas de uma playlist/mix (o Mix do YouTube é infinito). */
    private static final int MAX_PLAYLIST = 25;

    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private final Map<Long, GuildMusic> guilds = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public MusicListener() {
        // SoundCloud, Bandcamp, links diretos etc. O YouTube é feito pelo yt-dlp (veja abaixo).
        AudioSourceManagers.registerRemoteSources(playerManager,
                com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);
    }

    private GuildMusic getMusic(Guild guild) {
        return guilds.computeIfAbsent(guild.getIdLong(), id -> {
            GuildMusic music = new GuildMusic(playerManager);
            guild.getAudioManager().setSendingHandler(music);
            return music;
        });
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent e) {
        if (e.getAuthor().isBot() || !e.isFromGuild()) return;

        String raw = e.getMessage().getContentRaw().trim();
        if (!raw.startsWith(PREFIX)) return;

        String[] parts = raw.substring(PREFIX.length()).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "p", "play", "tocar" -> play(e, arg);
            case "pular", "skip" -> skip(e);
            case "pausar", "pause" -> pause(e, true);
            case "continuar", "resume" -> pause(e, false);
            case "parar", "stop", "sair" -> stop(e);
            case "fila", "queue" -> queue(e);
            case "tocando", "np" -> nowPlaying(e);
            case "volume", "vol" -> volume(e, arg);
            case "ajuda", "help" -> reply(e, AJUDA);
            default -> { /* comando desconhecido: ignora */ }
        }
    }

    // ---------------- comandos ----------------

    private void play(MessageReceivedEvent e, String arg) {
        if (arg.isEmpty()) {
            reply(e, "Use: `!p nome da música` ou `!p link`");
            return;
        }
        GuildVoiceState voice = e.getMember().getVoiceState();
        if (voice == null || !voice.inAudioChannel()) {
            reply(e, "⚠️ Entre em um canal de voz primeiro.");
            return;
        }

        AudioManager audio = e.getGuild().getAudioManager();
        if (!audio.isConnected()) {
            audio.openAudioConnection(voice.getChannel());
        }

        GuildMusic music = getMusic(e.getGuild());
        music.channel = e.getChannel();

        boolean isLink = arg.startsWith("http://") || arg.startsWith("https://");
        if (isLink && isYoutube(arg) && arg.contains("list=")) {
            playYoutubePlaylist(e, music, arg);               // Mix / playlist -> toca todas
        } else if (isLink && isYoutube(arg)) {
            playYoutube(e, music, arg, null);                 // link do YouTube -> yt-dlp
        } else if (isLink) {
            load(e, music, arg, null);                        // outro link -> player direto
        } else {
            playYoutube(e, music, "ytsearch1:" + arg, arg);   // texto -> busca no YouTube; se falhar, SoundCloud
        }
    }

    private static boolean isYoutube(String url) {
        String u = url.toLowerCase();
        return u.contains("youtube.com/") || u.contains("youtu.be/") || u.contains("music.youtube.com/");
    }

    // ---------------- YouTube via yt-dlp ----------------

    private void playYoutube(MessageReceivedEvent e, GuildMusic music, String query, String soundcloudFallback) {
        reply(e, "🔎 Buscando...");
        pool.submit(() -> {
            try {
                Resolved r = resolveWithYtdlp(query);
                playerManager.loadItemOrdered(e.getGuild().getIdLong(), r.url(), new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        track.setUserData(r.meta());
                        music.add(track);
                        reply(e, "➕ Adicionada: **" + title(track) + "** `" + fmt(track) + "`");
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        if (playlist.getTracks().isEmpty()) {
                            noMatches();
                        } else {
                            trackLoaded(playlist.getTracks().get(0));
                        }
                    }

                    @Override
                    public void noMatches() {
                        youtubeFailed(e, music, soundcloudFallback, "formato de áudio não reconhecido");
                    }

                    @Override
                    public void loadFailed(FriendlyException ex) {
                        youtubeFailed(e, music, soundcloudFallback, ex.getMessage());
                    }
                });
            } catch (IOException ex) {
                youtubeFailed(e, music, soundcloudFallback,
                        "o yt-dlp não está instalado ou não está no PATH");
            } catch (Exception ex) {
                youtubeFailed(e, music, soundcloudFallback, ex.getMessage());
            }
        });
    }

    private void youtubeFailed(MessageReceivedEvent e, GuildMusic music, String soundcloudFallback, String reason) {
        System.err.println("[yt-dlp] falhou: " + reason);
        if (soundcloudFallback != null) {
            load(e, music, "scsearch:" + soundcloudFallback, null);
        } else {
            reply(e, "❌ Não consegui carregar do YouTube: " + reason);
        }
    }

    /** Pergunta ao yt-dlp o título, a duração e o link direto do áudio. */
    private Resolved resolveWithYtdlp(String query) throws Exception {
        ProcessBuilder pb = Tools.process(
                "--no-playlist", "--no-warnings",
                "-f", "bestaudio[ext=webm]/bestaudio",
                "--print", "title", "--print", "duration_string", "--print", "urls",
                "--", query);                       // "--" impede que o texto vire opção do yt-dlp
        pb.redirectError(ProcessBuilder.Redirect.INHERIT); // erros do yt-dlp aparecem no terminal
        Process p = pb.start();

        if (!p.waitFor(45, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new Exception("o yt-dlp demorou demais");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String[] lines = out.split("\\R");
        if (p.exitValue() != 0 || lines.length < 3) {
            throw new Exception("o yt-dlp não conseguiu pegar o áudio (veja o terminal)");
        }
        String duration = lines[1].trim();
        if (duration.equals("NA")) duration = null;
        return new Resolved(lines[2].trim(), new Meta(lines[0].trim(), duration));
    }

    // ---------------- Mix / playlist do YouTube ----------------

    private void playYoutubePlaylist(MessageReceivedEvent e, GuildMusic music, String url) {
        reply(e, "📃 Mix/playlist detectada! Carregando as músicas (até " + MAX_PLAYLIST
                + "). A primeira toca logo, as outras entram na fila aos poucos.");
        pool.submit(() -> {
            List<String> ids;
            try {
                ids = listPlaylistIds(url);
            } catch (IOException ex) {
                reply(e, "❌ O yt-dlp não está instalado ou não está no PATH.");
                return;
            } catch (Exception ex) {
                reply(e, "❌ Não consegui ler a playlist: " + ex.getMessage());
                return;
            }
            if (ids.isEmpty()) {
                reply(e, "❌ Não encontrei músicas nessa playlist.");
                return;
            }

            int added = 0;
            for (String id : ids) {
                // se o bot foi desligado do canal (!parar), para de carregar
                if (!e.getGuild().getAudioManager().isConnected()) return;
                try {
                    Resolved r = resolveWithYtdlp("https://www.youtube.com/watch?v=" + id);
                    AudioTrack track = loadTrack(r.url());
                    if (track == null) continue;
                    track.setUserData(r.meta());
                    music.add(track);
                    added++;
                    if (added == 1) {
                        reply(e, "➕ Primeira: **" + title(track) + "** `" + fmt(track) + "`");
                    }
                } catch (Exception ex) {
                    System.err.println("[yt-dlp] pulei " + id + ": " + ex.getMessage());
                }
            }
            reply(e, "✅ " + added + " de " + ids.size() + " músicas da playlist foram para a fila.");
        });
    }

    /** Lista só os IDs dos vídeos da playlist/mix (rápido, sem baixar nada). */
    private List<String> listPlaylistIds(String url) throws Exception {
        ProcessBuilder pb = Tools.process(
                "--no-warnings", "--yes-playlist", "--flat-playlist",
                "--playlist-end", String.valueOf(MAX_PLAYLIST),
                "--print", "id",
                "--", url);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();

        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new Exception("o yt-dlp demorou demais");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        List<String> ids = new ArrayList<>();
        for (String line : out.split("\\R")) {
            String id = line.trim();
            if (id.matches("[A-Za-z0-9_-]{11}")) ids.add(id);
        }
        if (ids.isEmpty() && p.exitValue() != 0) {
            throw new Exception("o yt-dlp falhou (veja o terminal)");
        }
        return ids;
    }

    /** Carrega um link direto no player e espera o resultado. */
    private AudioTrack loadTrack(String url) throws Exception {
        CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        playerManager.loadItem(url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                future.complete(playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0));
            }

            @Override
            public void noMatches() {
                future.complete(null);
            }

            @Override
            public void loadFailed(FriendlyException ex) {
                future.completeExceptionally(ex);
            }
        });
        return future.get(30, TimeUnit.SECONDS);
    }

    // ---------------- outros links / SoundCloud ----------------

    private void load(MessageReceivedEvent e, GuildMusic music, String identifier, String ignored) {
        playerManager.loadItemOrdered(e.getGuild().getIdLong(), identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                music.add(track);
                reply(e, "➕ Adicionada: **" + title(track) + "** `" + fmt(track) + "`");
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    noMatches();
                    return;
                }
                if (playlist.isSearchResult()) {
                    trackLoaded(playlist.getTracks().get(0)); // primeiro resultado da busca
                    return;
                }
                playlist.getTracks().forEach(music::add);
                reply(e, "📃 Playlist **" + playlist.getName() + "** adicionada ("
                        + playlist.getTracks().size() + " músicas).");
            }

            @Override
            public void noMatches() {
                reply(e, "❌ Não encontrei nada.");
            }

            @Override
            public void loadFailed(FriendlyException ex) {
                reply(e, "❌ Erro ao carregar: " + ex.getMessage());
            }
        });
    }

    private void skip(MessageReceivedEvent e) {
        GuildMusic music = getMusic(e.getGuild());
        if (music.player.getPlayingTrack() == null) {
            reply(e, "Não tem nada tocando.");
            return;
        }
        music.skip();
        reply(e, "⏭️ Música pulada.");
    }

    private void pause(MessageReceivedEvent e, boolean paused) {
        GuildMusic music = getMusic(e.getGuild());
        if (music.player.getPlayingTrack() == null) {
            reply(e, "Não tem nada tocando.");
            return;
        }
        music.player.setPaused(paused);
        reply(e, paused ? "⏸️ Pausado." : "▶️ Continuando.");
    }

    private void stop(MessageReceivedEvent e) {
        getMusic(e.getGuild()).clear();
        e.getGuild().getAudioManager().closeAudioConnection();
        reply(e, "⏹️ Parei tudo e saí do canal.");
    }

    private void queue(MessageReceivedEvent e) {
        GuildMusic music = getMusic(e.getGuild());
        AudioTrack current = music.player.getPlayingTrack();
        List<AudioTrack> next = music.getQueue();

        if (current == null && next.isEmpty()) {
            reply(e, "A fila está vazia.");
            return;
        }

        StringBuilder sb = new StringBuilder("📜 **Fila**\n");
        if (current != null) {
            sb.append("▶️ ").append(title(current)).append('\n');
        }
        int max = Math.min(next.size(), 10);
        for (int i = 0; i < max; i++) {
            sb.append(i + 1).append(". ").append(title(next.get(i)))
                    .append(" `").append(fmt(next.get(i))).append("`\n");
        }
        if (next.size() > max) {
            sb.append("... e mais ").append(next.size() - max).append(" músicas.");
        }
        reply(e, sb.toString());
    }

    private void nowPlaying(MessageReceivedEvent e) {
        AudioTrack current = getMusic(e.getGuild()).player.getPlayingTrack();
        if (current == null) {
            reply(e, "Não tem nada tocando.");
            return;
        }
        reply(e, "🎶 Tocando agora: **" + title(current) + "** `" + fmt(current.getPosition())
                + " / " + fmt(current) + "`");
    }

    private void volume(MessageReceivedEvent e, String arg) {
        try {
            int vol = Integer.parseInt(arg);
            if (vol < 0 || vol > 100) throw new NumberFormatException();
            getMusic(e.getGuild()).player.setVolume(vol);
            reply(e, "🔊 Volume: **" + vol + "**");
        } catch (NumberFormatException ex) {
            reply(e, "Use: `!volume 0-100`");
        }
    }

    // ---------------- utilidades ----------------

    private void reply(MessageReceivedEvent e, String text) {
        e.getChannel().sendMessage(text).queue();
    }

    private static String title(AudioTrack track) {
        return track.getUserData() instanceof Meta m ? m.title() : track.getInfo().title;
    }

    private static String fmt(AudioTrack track) {
        if (track.getUserData() instanceof Meta m && m.duration() != null) return m.duration();
        return track.getInfo().isStream ? "AO VIVO" : fmt(track.getDuration());
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }
}
