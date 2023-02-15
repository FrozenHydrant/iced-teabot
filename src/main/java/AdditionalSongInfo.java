
import discord4j.common.util.Snowflake;
import reactor.util.annotation.NonNull;

/*
 Contains info about the query as well as the song
*/
public final class AdditionalSongInfo {
    @NonNull
    public final Snowflake requesterId;
    public AdditionalSongInfo(@NonNull Snowflake requesterId) {
        this.requesterId = requesterId;
    }
}
