import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Guild;
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

    protected final Guild guild;

    protected GuildAudioManager(final AudioPlayerManager APM, final Map<Snowflake, GuildAudioManager> GUILD_AUDIOS, Snowflake id, Guild guild) {
        ap = APM.createPlayer();
        provider = new LavaPlayerAudioProvider(ap);
        this.guild = guild;
        scheduler = new TrackScheduler(ap, GUILD_AUDIOS, id, guild);
        ap.addListener(scheduler);
        isSingleLooping = false;
        isQueueLooping = false;
    }
}
