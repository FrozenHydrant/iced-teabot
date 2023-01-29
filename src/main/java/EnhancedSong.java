import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import discord4j.core.object.entity.Message;
import reactor.util.annotation.NonNull;
import reactor.util.annotation.Nullable;

/*
 Contains info about the query as well as the song
*/
public final class EnhancedSong {
    @Nullable
    public final Message message;

    @NonNull
    public final AudioTrack song;

    public EnhancedSong(@Nullable Message message, @NonNull AudioTrack song) {
        this.message = message;
        this.song = song;
    }
}
