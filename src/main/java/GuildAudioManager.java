import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.voice.VoiceConnection;

import java.util.Map;

public class GuildAudioManager {
    protected final AudioPlayer ap;
    protected final LavaPlayerAudioProvider provider;
    protected final TrackScheduler scheduler;
    protected VoiceConnection voiceConnection;
    protected MessageChannel messageChannel;
    protected boolean isSingleLooping;
    protected boolean isQueueLooping;

    protected GuildAudioManager(final AudioPlayerManager APM, final Map<Snowflake, GuildAudioManager> GUILD_AUDIOS, Snowflake id) {
        ap = APM.createPlayer();
        provider = new LavaPlayerAudioProvider(ap);
        scheduler = new TrackScheduler(ap, GUILD_AUDIOS, id);
        ap.addListener(scheduler);
        isSingleLooping = false;
        isQueueLooping = false;
    }
}
