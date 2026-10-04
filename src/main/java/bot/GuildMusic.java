package bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Player + fila de um servidor. Também envia o áudio para o Discord. */
public class GuildMusic extends AudioEventAdapter implements AudioSendHandler {

    public final AudioPlayer player;
    public volatile MessageChannel channel;
    private final BlockingQueue<AudioTrack> queue = new LinkedBlockingQueue<>();
    private final ByteBuffer buffer = ByteBuffer.allocate(1024);
    private final MutableAudioFrame frame = new MutableAudioFrame();

    public GuildMusic(AudioPlayerManager manager) {
        this.player = manager.createPlayer();
        this.player.addListener(this);
        this.frame.setBuffer(buffer);
    }

    /** Toca agora se estiver livre, senão entra na fila. */
    public void add(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        }
    }

    /** Pula para a próxima (ou para tudo se a fila estiver vazia). */
    public void skip() {
        player.startTrack(queue.poll(), false);
    }

    public void clear() {
        queue.clear();
        player.stopTrack();
    }

    public List<AudioTrack> getQueue() {
        return new ArrayList<>(queue);
    }

    @Override
    public void onTrackEnd(AudioPlayer p, AudioTrack track, AudioTrackEndReason reason) {
        if (reason.mayStartNext) {
            skip();
        }
    }

    @Override
public void onTrackException(AudioPlayer p, AudioTrack track, FriendlyException ex) {
    Throwable root = ex;
    while (root.getCause() != null) root = root.getCause();
    ex.printStackTrace();
    if (channel != null) {
        channel.sendMessage("❌ Não consegui tocar **" + track.getInfo().title + "**: " + root.getMessage()).queue();
    }
}// ---- AudioSendHandler (JDA pede um pedaço de 20ms de áudio) ----

    @Override
    public boolean canProvide() {
        buffer.clear();
        return player.provide(frame);
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        return buffer.flip();
    }

    @Override
    public boolean isOpus() {
        return true;
    }
}
